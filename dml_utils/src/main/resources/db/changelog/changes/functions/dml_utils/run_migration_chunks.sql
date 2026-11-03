CREATE OR REPLACE FUNCTION dml_utils.run_migration_chunks(
    i_sql_text dml_utils_data.non_null_text,
    i_driving_table_schema_name dml_utils_data.non_null_text,
    i_driving_table_name dml_utils_data.non_null_text,
    i_label dml_utils_data.non_null_text,
    i_chunk_size dml_utils_data.positive_integer DEFAULT 1000,
    i_threads dml_utils_data.positive_integer DEFAULT 1,
    i_driving_table_alias dml_utils_data.non_null_text DEFAULT 't',
    i_chunk_by dml_utils_data.chunking_strategy DEFAULT 'primary_key',
    -- How long pg_background_wait_any waits for a worker before returning so this
    -- coordinator can re-check. 1ms keeps per-chunk detection latency low; a
    -- caller whose chunks take non-trivial time can raise it to cut coordinator
    -- CPU, at the cost of up to ~50ms added detection latency. positive_integer,
    -- so 0 is rejected: pg_background_wait_any treats a non-positive timeout as
    -- "block forever", which would stop this coordinator re-checking its state.
    i_poll_timeout_ms dml_utils_data.positive_integer DEFAULT 1,
    -- Milliseconds to wait after each successfully completed chunk before
    -- launching another. This seeds a new run; a resumed run uses its stored
    -- value. 0 disables the delay.
    i_sleep_ms dml_utils_data.non_negative_integer DEFAULT 0,
    -- The unique index whose order an ordered_key run uses. Required for
    -- ordered_key; rejected for a new run of the other strategies and ignored
    -- with a notice when resuming an existing run.
    i_boundary_key_index name DEFAULT NULL
)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
DECLARE
    l_key_columns           name[];
    l_key_kinds             text[];
    l_chunk_by              dml_utils_data.chunking_strategy;
    l_run_id                bigint;
    l_already_completed     boolean;
    l_effective_sql_text    text;
    l_effective_threads     integer;
    l_effective_sleep_ms    integer;
    l_effective_schema_name text;
    l_effective_table_name  text;
    l_effective_alias       text;
    l_boundary_no           bigint;
    l_start_values          text[];
    l_end_values            text[];
    l_is_final              boolean;
    l_chunk_sql             text;
    -- Run-invariant chunk-render context, prepared once before the loop so the
    -- per-chunk render does not repeat the template validation and quoting.
    l_render_prepared       record;
    -- The workers kept in flight, and the boundary each one was launched for
    -- (parallel arrays, aligned by position).
    l_in_flight             public.pg_background_handle[] := ARRAY []::public.pg_background_handle[];
    l_in_flight_boundaries  bigint[]                      := ARRAY []::bigint[];
    l_worker_index          integer;
    l_aborting              boolean                       := false;
    -- Cancellation state: the run's cancelled_at, polled at most every 100ms.
    l_cancelled_at          timestamptz;
    l_last_cancel_check     timestamptz;
    l_error_sqlstate        text;
    l_error_message         text;
    l_error_boundary_no     bigint;
    -- Per-chunk throttle state. The sleep is sliced so cancellation is noticed
    -- promptly; sleep_ms itself is not re-read (restart-only adjustment).
    l_sleep_remaining_ms    integer;
    l_sleep_slice_ms        integer;
    -- pg_background is installed in the public schema. Qualify its types and
    -- functions explicitly so this routine resolves them regardless of the
    -- caller's (or Liquibase's) search_path.
    l_handle                public.pg_background_handle;
    l_outcome               public.pg_background_outcome;
BEGIN
    -- Validate the template up front so a bad call fails before any run or
    -- worker exists.
    PERFORM dml_utils_lib.assert_chunking_template(i_sql_text => i_sql_text);

    -- Resolve or create the run and the effective inputs (stored values for a
    -- resumed run, the call's inputs for a new one); a new run's boundaries are
    -- populated in a pg_background worker so they commit autonomously.
    SELECT r.o_run_id,
           r.o_already_completed,
           r.o_effective_sql_text,
           r.o_effective_schema_name,
           r.o_effective_table_name,
           r.o_effective_alias,
           r.o_effective_threads,
           r.o_effective_sleep_ms,
           r.o_chunk_by,
           r.o_key_columns,
           r.o_key_kinds
    INTO l_run_id, l_already_completed, l_effective_sql_text,
        l_effective_schema_name, l_effective_table_name, l_effective_alias,
        l_effective_threads, l_effective_sleep_ms, l_chunk_by, l_key_columns, l_key_kinds
    FROM dml_utils_lib.resolve_migration_run(
                 i_sql_text => i_sql_text,
                 i_driving_table_schema_name => i_driving_table_schema_name,
                 i_driving_table_name => i_driving_table_name,
                 i_label => i_label,
                 i_chunk_size => i_chunk_size,
                 i_threads => i_threads,
                 i_driving_table_alias => i_driving_table_alias,
                 i_chunk_by => i_chunk_by,
                 i_sleep_ms => i_sleep_ms,
                 i_boundary_key_index => i_boundary_key_index) AS r;

    IF l_already_completed THEN
        RETURN;
    END IF;

    -- A cancelled run is terminal: a re-run must not silently resume it. The
    -- caller archives it (archive_migration_run) and starts a new run.
    SELECT r.cancelled_at
    INTO l_cancelled_at
    FROM dml_utils_data.migration_run AS r
    WHERE r.run_id = l_run_id;

    IF l_cancelled_at IS NOT NULL THEN
        RAISE NOTICE 'run for label % is cancelled; archive it or use a new label', i_label;
        RETURN;
    END IF;

    -- started_at (the run start) and boundaries_calculated_at (the range
    -- calculation completion) were recorded by populate_migration_boundaries
    -- when the run was created, in its worker's transaction, so they persist
    -- across a failed processing attempt and are not re-stamped on a resume.
    -- Only completed_at, below, is written in the caller's transaction.

    -- Process every unclaimed boundary, up to i_threads workers at a time. Each
    -- worker claims its boundary and runs its chunk SQL in its own transaction,
    -- so progress is durable and a re-run resumes at the first unclaimed
    -- boundary. The coordinator only schedules; it holds no locks on the
    -- driving table.
    --
    -- The chunk's end and final flag come from the run's full ordered boundary
    -- set (boundaries are contiguous 0..N, where N is the terminal high-water
    -- boundary), NOT from the set of still-unclaimed boundaries. Deriving them
    -- from the unclaimed set would misclassify a chunk as final whenever a later
    -- boundary (for example the terminal one) is already completed. The join on
    -- boundary_no + 1 excludes the terminal boundary, so the end key is never
    -- null.
    --
    -- Prepare the run-invariant chunk-render context once: the template is
    -- validated, the driving table and column names are quoted and each text
    -- column's collation is resolved here, so the per-chunk render below only
    -- builds the range predicate and substitutes the clause. A block run renders a
    -- trivial ctid range, so it needs no context.
    IF l_chunk_by <> 'blocks' THEN
        l_render_prepared := dml_utils_lib.prepare_chunk_sql_render(
                i_sql_text => l_effective_sql_text,
                i_schema_name => l_effective_schema_name,
                i_table_name => l_effective_table_name,
                i_table_alias => l_effective_alias,
                i_key_columns => l_key_columns,
                i_key_kinds => l_key_kinds);
    END IF;

    BEGIN
        LOOP
            -- Poll the run's cancellation flag at most every 100ms; the inner wait
            -- loop polls too, so a cancellation is noticed while a long chunk is
            -- still running.
            IF l_cancelled_at IS NULL
                AND (l_last_cancel_check IS NULL
                    OR pg_catalog.clock_timestamp() - l_last_cancel_check >= interval '100 milliseconds')
            THEN
                SELECT r.cancelled_at
                INTO l_cancelled_at
                FROM dml_utils_data.migration_run AS r
                WHERE r.run_id = l_run_id;
                l_last_cancel_check := pg_catalog.clock_timestamp();
            END IF;

            -- A cancelled run stops launching and leaves the loop for the
            -- cancellation handler below.
            EXIT WHEN l_cancelled_at IS NOT NULL;

            -- Keep launching until i_threads are in flight (or there is nothing left
            -- to launch), unless a worker has already failed.
            IF NOT l_aborting THEN
                WHILE pg_catalog.cardinality(l_in_flight) < l_effective_threads
                    LOOP
                    -- Extract the per-position key values of the start and end
                    -- boundaries in one call. Each kind populates a different
                    -- position-aligned array of migration_key; the helper flattens
                    -- both back into a value list each, in key order.
                        SELECT b.boundary_no,
                               pair.o_start_values,
                               pair.o_end_values,
                               next.boundary_no = last.boundary_no
                        INTO l_boundary_no, l_start_values, l_end_values, l_is_final
                        FROM dml_utils_data.migration_boundary AS b
                                 JOIN dml_utils_data.migration_boundary AS next
                                      ON next.run_id = b.run_id
                                          AND next.boundary_no = b.boundary_no + 1
                                 CROSS JOIN LATERAL (
                            SELECT max(boundary_no) AS boundary_no
                            FROM dml_utils_data.migration_boundary
                            WHERE run_id = b.run_id
                            ) AS last
                                 CROSS JOIN LATERAL dml_utils_lib.migration_key_pair_values(
                                i_start_key => b.boundary_id,
                                i_end_key => next.boundary_id,
                                i_key_kinds => l_key_kinds) AS pair
                        WHERE b.run_id = l_run_id
                          AND b.completed_at IS NULL
                          AND b.cancelled_at IS NULL
                          AND b.boundary_no <> ALL (l_in_flight_boundaries)
                        ORDER BY b.boundary_no
                        LIMIT 1;

                        EXIT WHEN NOT FOUND;

                        IF l_chunk_by = 'blocks' THEN
                            -- The boundary values are the start and end block
                            -- numbers; render a half-open ctid range. There is no
                            -- final/inclusive distinction for blocks.
                            l_chunk_sql := dml_utils_lib.render_block_chunk_sql(
                                    i_sql_text => l_effective_sql_text,
                                    i_schema_name => l_effective_schema_name,
                                    i_table_name => l_effective_table_name,
                                    i_table_alias => l_effective_alias,
                                    i_start_block => l_start_values[1]::bigint,
                                    i_end_block => l_end_values[1]::bigint);
                        ELSE
                            l_chunk_sql := dml_utils_lib.render_prepared_chunk_sql(
                                    i_template => l_render_prepared.o_template,
                                    i_schema_name => l_effective_schema_name,
                                    i_table_name => l_effective_table_name,
                                    i_table_alias => l_effective_alias,
                                    i_column_names => l_render_prepared.o_column_names,
                                    i_key_kinds => l_key_kinds,
                                    i_key_collations => l_render_prepared.o_key_collations,
                                    i_start_values => l_start_values,
                                    i_end_values => l_end_values,
                                    i_is_final => l_is_final);
                        END IF;

                        -- pg_background has no USING, so the run id, boundary number and
                        -- chunk SQL are inlined: the first two are bigint (%s), the chunk
                        -- SQL is a literal (%L).
                        l_handle := public.pg_background_launch(
                                pg_catalog.format(
                                        'SELECT dml_utils_lib.process_migration_chunk(%s, %s, %L)',
                                        l_run_id, l_boundary_no, l_chunk_sql),
                                0,
                                pg_catalog.format('run %s chunk %s', l_run_id, l_boundary_no));
                        l_in_flight := l_in_flight || l_handle;
                        l_in_flight_boundaries := l_in_flight_boundaries || l_boundary_no;
                    END LOOP;
            END IF;

            -- Nothing running and nothing left to launch: done (or draining is done).
            EXIT WHEN pg_catalog.cardinality(l_in_flight) = 0;

            -- Wait for any in-flight worker to finish, polling with
            -- i_poll_timeout_ms and letting the loop retry. The default of 1ms
            -- keeps per-chunk detection latency low; a larger value polls less
            -- often and so uses less coordinator CPU, which suits chunks that run
            -- in non-trivial time. pg_background_wait_any adds roughly 50ms of
            -- latency whenever timeout_ms is 2 or more (observed with pg_background
            -- 2.0), which is why the coordinator wraps it in this retry loop
            -- instead of waiting longer inside a single call.
            LOOP
                l_handle := public.pg_background_wait_any(l_in_flight, i_poll_timeout_ms);
                EXIT WHEN l_handle IS NOT NULL;

                IF l_cancelled_at IS NULL
                    AND (l_last_cancel_check IS NULL
                        OR pg_catalog.clock_timestamp() - l_last_cancel_check >= interval '100 milliseconds')
                THEN
                    SELECT r.cancelled_at
                    INTO l_cancelled_at
                    FROM dml_utils_data.migration_run AS r
                    WHERE r.run_id = l_run_id;
                    l_last_cancel_check := pg_catalog.clock_timestamp();
                END IF;
                EXIT WHEN l_cancelled_at IS NOT NULL;
            END LOOP;

            -- Leave the main loop for the cancellation handler.
            EXIT WHEN l_cancelled_at IS NOT NULL;

            -- Recover the boundary the finished worker was launched for, then drop it
            -- from the in-flight set.
            l_worker_index := pg_catalog.array_position(l_in_flight, l_handle);
            l_boundary_no := l_in_flight_boundaries[l_worker_index];
            l_in_flight := l_in_flight[1:l_worker_index - 1]
                || l_in_flight[l_worker_index + 1:];
            l_in_flight_boundaries := l_in_flight_boundaries[1:l_worker_index - 1]
                || l_in_flight_boundaries[l_worker_index + 1:];

            SELECT *
            INTO l_outcome
            FROM public.pg_background_outcome(l_handle.pid, l_handle.cookie);
            PERFORM public.pg_background_detach(l_handle.pid, l_handle.cookie);

            IF l_outcome.has_error THEN
                -- P0C01 is the claim protocol's dedicated code. Treat it as
                -- benign only if the boundary is now completed or cancelled;
                -- that proves another coordinator or the cancellation path won
                -- the race. A P0C01 from user SQL (or a stale claim failure) with
                -- a still-pending boundary must be recorded as an error, not
                -- retried forever.
                IF l_outcome.sqlstate = 'P0C01' THEN
                    IF EXISTS (SELECT 1
                               FROM dml_utils_data.migration_boundary AS b
                               WHERE b.run_id = l_run_id
                                 AND b.boundary_no = l_boundary_no
                                 AND (b.completed_at IS NOT NULL
                                   OR b.cancelled_at IS NOT NULL)) THEN
                        CONTINUE;
                    END IF;
                END IF;

                -- Record the failure first, in its own worker/transaction, so the
                -- row commits autonomously and survives the re-raise below. Recording
                -- is best effort: if it fails (for example the recording worker cannot
                -- be launched), the original worker error is still raised. The ids are
                -- bigint (%s); the SQLSTATE and message are literals (%L).
                BEGIN
                    PERFORM public.pg_background_run(pg_catalog.format(
                            'SELECT dml_utils_lib.record_migration_error(%s, %s, %L, %L)',
                            l_run_id, l_boundary_no, l_outcome.sqlstate, l_outcome.error_message));
                EXCEPTION
                    WHEN OTHERS THEN
                        NULL;
                END;

                -- Remember the first failure and stop launching new chunks: the
                -- workers already in flight finish (and commit) their current chunk,
                -- then this call exits with the error.
                IF NOT l_aborting THEN
                    l_error_sqlstate := l_outcome.sqlstate;
                    l_error_message := l_outcome.error_message;
                    l_error_boundary_no := l_boundary_no;
                END IF;
                l_aborting := true;
            END IF;

            -- Throttle: after a successful chunk, wait before refilling the slot.
            -- Skip the terminal-boundary case (no following chunk) and the drain
            -- path after a failure. The sleep is sliced so cancellation is noticed
            -- promptly; sleep_ms itself is read once per run, not re-read here.
            IF NOT l_aborting
                AND NOT l_outcome.has_error
                AND l_cancelled_at IS NULL
                AND l_effective_sleep_ms > 0
                AND EXISTS (SELECT 1
                            FROM dml_utils_data.migration_boundary AS b
                                     JOIN dml_utils_data.migration_boundary AS next
                                          ON next.run_id = b.run_id
                                              AND next.boundary_no = b.boundary_no + 1
                            WHERE b.run_id = l_run_id
                              AND b.completed_at IS NULL
                              AND b.cancelled_at IS NULL
                              AND b.boundary_no <> ALL (l_in_flight_boundaries))
            THEN
                l_sleep_remaining_ms := l_effective_sleep_ms;
                WHILE l_sleep_remaining_ms > 0
                    LOOP
                        l_sleep_slice_ms := least(l_sleep_remaining_ms, 100);
                        PERFORM pg_catalog.pg_sleep(l_sleep_slice_ms::double precision / 1000.0);
                        l_sleep_remaining_ms := l_sleep_remaining_ms - l_sleep_slice_ms;

                        -- Cancellation is still noticed during a long throttle.
                        SELECT r.cancelled_at
                        INTO l_cancelled_at
                        FROM dml_utils_data.migration_run AS r
                        WHERE r.run_id = l_run_id;
                        l_last_cancel_check := pg_catalog.clock_timestamp();
                        EXIT WHEN l_cancelled_at IS NOT NULL;
                    END LOOP;
                EXIT WHEN l_cancelled_at IS NOT NULL;
            END IF;
        END LOOP;

        -- Cancellation: stop the in-flight workers and record the cancellation.
        IF l_cancelled_at IS NOT NULL THEN
            -- Cancel the workers still in flight. A worker that already finished
            -- is a no-op for cancel; the wait/detach below reaps it.
            FOR l_cancel_index IN 1..pg_catalog.cardinality(l_in_flight)
                LOOP
                    BEGIN
                        PERFORM public.pg_background_cancel(
                                l_in_flight[l_cancel_index].pid,
                                l_in_flight[l_cancel_index].cookie,
                                1000);
                    EXCEPTION
                        WHEN OTHERS THEN
                            NULL;
                    END;
                END LOOP;

            -- Wait for each worker to stop and release its handle. A cancelled
            -- chunk rolls back, leaving its boundary unclaimed.
            FOR l_cancel_index IN 1..pg_catalog.cardinality(l_in_flight)
                LOOP
                    BEGIN
                        PERFORM public.pg_background_wait(
                                l_in_flight[l_cancel_index].pid,
                                l_in_flight[l_cancel_index].cookie,
                                5000);
                        PERFORM public.pg_background_detach(
                                l_in_flight[l_cancel_index].pid,
                                l_in_flight[l_cancel_index].cookie);
                    EXCEPTION
                        WHEN OTHERS THEN
                            NULL;
                    END;
                END LOOP;

            -- Record the cancellation on the run (if the caller's cancel
            -- transaction is not the one that already did) and on every boundary
            -- that did not complete, in an autonomous worker so the marks survive
            -- a caller rollback. completed_at wins for a chunk that finished
            -- before the cancel reached it.
            BEGIN
                PERFORM public.pg_background_run(pg_catalog.format(
                        'SELECT dml_utils_lib.mark_migration_run_cancelled(%s)',
                        l_run_id));
            EXCEPTION
                WHEN OTHERS THEN
                    NULL;
            END;

            RAISE NOTICE 'migration run % for label % cancelled', l_run_id, i_label;
            RETURN;
        END IF;

        -- l_aborting is the failure latch (not l_error_sqlstate, which a worker could
        -- in principle report as NULL).
        IF l_aborting THEN
            RAISE EXCEPTION 'chunk % for run % failed: %', l_error_boundary_no, l_run_id,
                l_error_message
                USING ERRCODE = COALESCE(l_error_sqlstate, 'P0001');
        END IF;
    EXCEPTION
        WHEN OTHERS THEN
            -- An unexpected error (a failed launch, a render error, ...) would
            -- otherwise abandon the workers already in flight. Wait for each to
            -- finish and detach it, so it commits/finishes and its handle does not
            -- leak, then re-raise the original error.
            FOR l_drain_index IN 1..pg_catalog.cardinality(l_in_flight)
                LOOP
                    BEGIN
                        PERFORM public.pg_background_wait(
                                l_in_flight[l_drain_index].pid,
                                l_in_flight[l_drain_index].cookie);
                        PERFORM public.pg_background_detach(
                                l_in_flight[l_drain_index].pid,
                                l_in_flight[l_drain_index].cookie);
                    EXCEPTION
                        WHEN OTHERS THEN
                            NULL;
                    END;
                END LOOP;
            RAISE;
    END;

    -- All boundaries are claimed and every chunk SQL already ran; record the run
    -- completion through the owner-owned helper. This routine stays SECURITY
    -- INVOKER so the worker chunk SQL runs as the caller; only the helper writes
    -- metadata.
    PERFORM dml_utils_lib.complete_migration_run(i_run_id => l_run_id);
END;
$$;

COMMENT ON FUNCTION dml_utils.run_migration_chunks IS
    'Runs the chunk SQL for every chunk of the driving table (primary_key: fixed '
        'rows in primary-key order; ordered_key: fixed rows in the order of '
        'i_boundary_key_index, a unique index on the driving table; blocks: '
        'half-open physical ctid ranges), up to i_threads pg_background workers at '
        'a time, resuming an active run for the label and recording its completion. '
        'A resumed run uses the stored sql_text, chunk size, strategy, key, threads, '
        'sleep_ms, driving table and alias. i_boundary_key_index is required for '
        'ordered_key and rejected for a new run of the other strategies; a resumed '
        'run keeps its stored key and ignores a differing index with a notice. '
        'After each successfully '
        'completed chunk it waits the run''s sleep_ms before launching another '
        '(except after the final chunk); the sleep is cancellation-aware. On a '
        'failed chunk it stops launching, lets the in-flight chunks commit, drains '
        'and detaches them, and re-raises the error; the failure is recorded (best '
        'effort) in dml_utils_data.migration_error first. A boundary already claimed '
        'by another coordinator (P0C01) is treated as benign. A run cancelled with '
        'dml_utils.cancel_migration_run stops launching, cancels the in-flight '
        'workers, marks the run and its remaining boundaries cancelled and '
        'returns; a cancelled run is terminal.';
