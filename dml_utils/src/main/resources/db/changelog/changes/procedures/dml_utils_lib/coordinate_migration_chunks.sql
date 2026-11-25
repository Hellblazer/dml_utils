CREATE OR REPLACE PROCEDURE dml_utils_lib.coordinate_migration_chunks(
    i_sql_text dml_utils_data.non_null_text,
    i_driving_table_schema_name dml_utils_data.non_null_text,
    i_driving_table_name dml_utils_data.non_null_text,
    i_label dml_utils_data.non_null_text,
    i_chunk_size dml_utils_data.positive_integer,
    i_threads dml_utils_data.positive_integer,
    i_driving_table_alias dml_utils_data.non_null_text,
    i_chunk_by dml_utils_data.chunking_strategy,
    i_poll_timeout_ms dml_utils_data.positive_integer,
    i_sleep_ms dml_utils_data.non_negative_integer,
    i_boundary_key_index name,
    i_partition_wise dml_utils_data.non_null_boolean,
    i_max_retries dml_utils_data.non_negative_integer,
    i_retry_backoff_ms dml_utils_data.non_negative_integer,
    -- true: COMMIT after every pass so this session never holds one snapshot (and
    -- so one xmin) across chunks, which needs the caller to have CALLed a procedure
    -- at top level. false: never COMMIT, so a function can call this too.
    i_commit boolean
)
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
DECLARE
    l_key_columns                name[];
    l_key_kinds                  text[];
    l_chunk_by                   dml_utils_data.chunking_strategy;
    l_partition_wise             boolean;
    l_run_id                     bigint;
    l_already_completed          boolean;
    l_effective_sql_text         text;
    l_effective_threads          integer;
    l_effective_sleep_ms         integer;
    l_effective_max_retries      integer;
    l_effective_retry_backoff_ms integer;
    l_effective_schema_name      text;
    l_effective_table_name       text;
    l_effective_alias            text;
    l_boundary_no                bigint;
    l_chunk                      record;
    l_chunk_sql                  text;
    -- Run-invariant chunk-render context, prepared once before the loop so the
    -- per-chunk render does not repeat the template validation and quoting.
    l_render_prepared            record;
    -- The workers kept in flight, and the boundary and chunk SQL each one was
    -- launched for (parallel arrays, aligned by position).
    l_in_flight                  public.pg_background_handle[] := ARRAY []::public.pg_background_handle[];
    l_in_flight_boundaries       bigint[]                      := ARRAY []::bigint[];
    l_in_flight_sql              text[]                        := ARRAY []::text[];
    -- Transient-retry attempts per boundary, aligned parallel arrays.
    l_retry_boundaries           bigint[]                      := ARRAY []::bigint[];
    l_retry_counts               integer[]                     := ARRAY []::integer[];
    l_retry_attempt              integer;
    l_worker_index               integer;
    l_pids                       integer[];
    l_cookies                    bigint[];
    l_aborting                   boolean                       := false;
    -- The run's cancelled_at and paused_at, polled at most every 100ms.
    l_cancelled_at               timestamptz;
    l_paused_at                  timestamptz;
    l_last_state_check           timestamptz;
    l_error_sqlstate             text;
    l_error_message              text;
    l_error_boundary_no          bigint;
    -- pg_background is installed in the public schema. Qualify its types and
    -- functions explicitly so this routine resolves them regardless of the
    -- caller's (or Liquibase's) search_path.
    l_handle                     public.pg_background_handle;
    l_outcome                    record;
    l_mark_outcome               record;
    -- The coordinator is a small state machine, one step per loop pass, so that
    -- the only place a pass can end is the COMMIT after the block (see the loop):
    --   launch  poll cancellation and pause, fill the free worker slots
    --   wait    wait for any in-flight worker (one bounded wait per pass)
    --   settle  record a finished worker's outcome and decide what follows
    --   sleep   the per-chunk delay or a retry backoff (sliced when committing)
    --   cancel  stop the workers and record the cancellation
    --   fail    re-raise the first chunk failure once the workers have drained
    --   pause   record completion if every chunk finished, else return resumable
    --   finish  leave the loop
    l_phase                      text                          := 'launch';
    l_benign_claim               boolean;
    l_retrying                   boolean;
    -- What the sleep step is for: 'throttle' (the delay after a chunk) or
    -- 'retry' (the backoff before a failed chunk is launched again).
    l_sleep_purpose              text;
    l_sleep_remaining_ms         integer;
    l_sleep_slice_ms             integer;
    l_retry_boundary_no          bigint;
    l_retry_sql                  text;
BEGIN
    IF i_commit IS NULL THEN
        RAISE EXCEPTION 'i_commit must not be null'
            USING ERRCODE = '22004';
    END IF;

    -- The coordinator observes workers' autonomous commits and polls the run row
    -- for pause/cancel, which needs a fresh snapshot per statement. When
    -- committing, this runs in the first transaction after the entry point's
    -- COMMIT, so it also rejects a session whose default isolation is stricter.
    PERFORM dml_utils_lib.assert_read_committed();

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
           r.o_effective_max_retries,
           r.o_effective_retry_backoff_ms,
           r.o_chunk_by,
           r.o_partition_wise,
           r.o_key_columns,
           r.o_key_kinds
    INTO l_run_id, l_already_completed, l_effective_sql_text,
        l_effective_schema_name, l_effective_table_name, l_effective_alias,
        l_effective_threads, l_effective_sleep_ms, l_effective_max_retries,
        l_effective_retry_backoff_ms, l_chunk_by, l_partition_wise,
        l_key_columns, l_key_kinds
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
                 i_boundary_key_index => i_boundary_key_index,
                 i_partition_wise => i_partition_wise,
                 i_max_retries => i_max_retries,
                 i_retry_backoff_ms => i_retry_backoff_ms) AS r;

    IF l_already_completed THEN
        RETURN;
    END IF;

    -- A cancelled run is terminal; a paused run is resumable but must not be
    -- auto-resumed. Completion wins when every chunk is already done. The
    -- resolver rejects a run that is already cancelled; this re-read covers a
    -- cancellation that commits between resolve and here, and fails closed
    -- rather than returning a successful no-op.
    SELECT r.cancelled_at, r.paused_at
    INTO l_cancelled_at, l_paused_at
    FROM dml_utils_data.migration_run AS r
    WHERE r.run_id = l_run_id;

    IF l_cancelled_at IS NOT NULL THEN
        RAISE EXCEPTION
            'migration run % for label % is cancelled; archive it with dml_utils.archive_migration_run and create a new run',
            l_run_id, i_label
            USING ERRCODE = '55000';
    END IF;

    IF l_paused_at IS NOT NULL THEN
        -- complete_migration_run requires the boundaries to be calculated; do not
        -- turn an uncalculated partition-wise run into a zero-chunk completion.
        IF EXISTS (SELECT 1
                   FROM dml_utils_data.migration_run AS r
                   WHERE r.run_id = l_run_id
                     AND r.boundaries_calculated_at IS NOT NULL)
        THEN
            PERFORM dml_utils_lib.complete_migration_run(i_run_id => l_run_id);
        END IF;

        IF EXISTS (SELECT 1
                   FROM dml_utils_data.migration_run AS r
                   WHERE r.run_id = l_run_id
                     AND r.completed_at IS NOT NULL)
        THEN
            RETURN;
        END IF;

        RAISE NOTICE 'run for label % is paused; resume it with dml_utils.resume_migration_run', i_label;
        RETURN;
    END IF;

    -- A partition-wise run must finish calculating every leaf before any chunk is
    -- processed. A resumed partial calculation continues at its first pending or
    -- failed leaf; the loop commits each leaf in its own worker transaction, so a
    -- failure keeps the earlier leaves. The live tree is compared with the
    -- manifest before calculating and again before processing.
    IF l_partition_wise THEN
        IF NOT dml_utils_lib.prepare_partition_wise_execution(
                i_run_id => l_run_id,
                i_label => i_label) THEN
            -- A pause that raced with preparation still lets completion win when
            -- every chunk is already done.
            IF EXISTS (SELECT 1
                       FROM dml_utils_data.migration_run AS r
                       WHERE r.run_id = l_run_id
                         AND r.paused_at IS NOT NULL
                         AND r.boundaries_calculated_at IS NOT NULL)
            THEN
                PERFORM dml_utils_lib.complete_migration_run(i_run_id => l_run_id);
            END IF;
            RETURN;
        END IF;
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

    -- One step per pass. The step runs in a block whose handler drains the
    -- workers in flight on an unexpected error (a failed launch, a render error,
    -- ...), so they commit/finish and their handles do not leak, then re-raises.
    -- PostgreSQL forbids COMMIT inside a block that has an EXCEPTION clause, so
    -- the COMMIT sits after the block, never in it: that placement is why the
    -- loop is a state machine and not the straight-line launch/wait/settle code a
    -- function would use. The same body serves the function and the procedure;
    -- i_commit only switches that COMMIT (and the slicing of the delays) on.
    LOOP
        BEGIN
            CASE l_phase
                WHEN 'launch' THEN
                    -- Poll the run's cancellation and pause flags at most every
                    -- 100ms; the wait step polls too, so a change is noticed while
                    -- a long chunk is still running.
                    IF l_cancelled_at IS NULL
                        AND (l_last_state_check IS NULL
                            OR pg_catalog.clock_timestamp() - l_last_state_check >= interval '100 milliseconds')
                    THEN
                        SELECT r.cancelled_at, r.paused_at
                        INTO l_cancelled_at, l_paused_at
                        FROM dml_utils_data.migration_run AS r
                        WHERE r.run_id = l_run_id;
                        l_last_state_check := pg_catalog.clock_timestamp();
                    END IF;

                    -- A cancelled run stops launching and goes to the
                    -- cancellation step. A paused run stops launching but keeps
                    -- waiting for the in-flight workers to commit their current
                    -- chunk.
                    IF l_cancelled_at IS NOT NULL THEN
                        l_phase := 'cancel';
                    ELSE
                        -- Keep launching until i_threads are in flight (or there
                        -- is nothing left to launch), unless a worker has already
                        -- failed or the run is paused.
                        IF NOT l_aborting AND l_paused_at IS NULL THEN
                            WHILE pg_catalog.cardinality(l_in_flight) < l_effective_threads
                                LOOP
                                    SELECT o_boundary_no,
                                           o_chunk_schema_name,
                                           o_chunk_table_name,
                                           o_start_values,
                                           o_end_values,
                                           o_is_final
                                    INTO l_chunk
                                    FROM dml_utils_lib.next_migration_chunk(
                                            i_run_id => l_run_id,
                                            i_partition_wise => l_partition_wise,
                                            i_key_kinds => l_key_kinds,
                                            i_in_flight_boundaries => l_in_flight_boundaries,
                                            i_default_schema_name => l_effective_schema_name,
                                            i_default_table_name => l_effective_table_name);

                                    EXIT WHEN l_chunk.o_boundary_no IS NULL;
                                    l_boundary_no := l_chunk.o_boundary_no;

                                    IF l_chunk_by = 'blocks' THEN
                                        -- The boundary values are the start and end
                                        -- block numbers; render a half-open ctid
                                        -- range. There is no final/inclusive
                                        -- distinction for blocks.
                                        l_chunk_sql := dml_utils_lib.render_block_chunk_sql(
                                                i_sql_text => l_effective_sql_text,
                                                i_schema_name => l_chunk.o_chunk_schema_name,
                                                i_table_name => l_chunk.o_chunk_table_name,
                                                i_table_alias => l_effective_alias,
                                                i_start_block => l_chunk.o_start_values[1]::bigint,
                                                i_end_block => l_chunk.o_end_values[1]::bigint);
                                    ELSE
                                        l_chunk_sql := dml_utils_lib.render_prepared_chunk_sql(
                                                i_template => l_render_prepared.o_template,
                                                i_schema_name => l_chunk.o_chunk_schema_name,
                                                i_table_name => l_chunk.o_chunk_table_name,
                                                i_table_alias => l_effective_alias,
                                                i_column_names => l_render_prepared.o_column_names,
                                                i_key_kinds => l_key_kinds,
                                                i_key_collations => l_render_prepared.o_key_collations,
                                                i_start_values => l_chunk.o_start_values,
                                                i_end_values => l_chunk.o_end_values,
                                                i_is_final => l_chunk.o_is_final);
                                    END IF;

                                    -- pg_background has no USING, so the run id,
                                    -- boundary number and chunk SQL are inlined: the
                                    -- first two are bigint (%s), the chunk SQL is a
                                    -- literal (%L).
                                    l_handle := public.pg_background_launch(
                                            pg_catalog.format(
                                                    'SELECT dml_utils_lib.process_migration_chunk(%s, %s, %L)',
                                                    l_run_id, l_boundary_no, l_chunk_sql),
                                            0,
                                            pg_catalog.format('run %s chunk %s', l_run_id, l_boundary_no));
                                    l_in_flight := l_in_flight || l_handle;
                                    l_in_flight_boundaries := l_in_flight_boundaries || l_boundary_no;
                                    l_in_flight_sql := l_in_flight_sql || l_chunk_sql;
                                END LOOP;
                        END IF;

                        -- Nothing running and nothing left to launch: done (or
                        -- draining is done). A failure wins over a pause, which
                        -- wins over completion.
                        IF pg_catalog.cardinality(l_in_flight) = 0 THEN
                            l_phase := CASE
                                           WHEN l_aborting THEN 'fail'
                                           WHEN l_paused_at IS NOT NULL THEN 'pause'
                                           ELSE 'finish'
                                       END;
                        ELSE
                            l_phase := 'wait';
                        END IF;
                    END IF;

                WHEN 'wait' THEN
                    -- Wait for any in-flight worker to finish, polling with
                    -- i_poll_timeout_ms and staying in this step until one does.
                    -- The default of 1ms keeps per-chunk detection latency low; a
                    -- larger value polls less often and so uses less coordinator
                    -- CPU, which suits chunks that run in non-trivial time.
                    -- pg_background_wait_any adds roughly 50ms of latency whenever
                    -- timeout_ms is 2 or more (observed with pg_background 2.0),
                    -- which is why the coordinator retries a short wait instead of
                    -- waiting longer inside a single call.
                    l_handle := public.pg_background_wait_any(l_in_flight, i_poll_timeout_ms);

                    IF l_handle IS NOT NULL THEN
                        l_phase := 'settle';
                    ELSE
                        IF l_cancelled_at IS NULL
                            AND (l_last_state_check IS NULL
                                OR pg_catalog.clock_timestamp() - l_last_state_check >= interval '100 milliseconds')
                        THEN
                            SELECT r.cancelled_at, r.paused_at
                            INTO l_cancelled_at, l_paused_at
                            FROM dml_utils_data.migration_run AS r
                            WHERE r.run_id = l_run_id;
                            l_last_state_check := pg_catalog.clock_timestamp();
                        END IF;

                        IF l_cancelled_at IS NOT NULL THEN
                            l_phase := 'cancel';
                        END IF;
                    END IF;

                WHEN 'settle' THEN
                    -- Recover the boundary the finished worker was launched for. A
                    -- NULL index would turn every following subscript into NULL
                    -- and corrupt the scheduling arrays (the coordinator could
                    -- then neither launch nor exit), so fail loudly instead of
                    -- assuming the handle is present.
                    l_worker_index := pg_catalog.array_position(l_in_flight, l_handle);
                    IF l_worker_index IS NULL THEN
                        RAISE EXCEPTION 'finished worker % is not in the in-flight set', l_handle
                            USING ERRCODE = '55000';
                    END IF;
                    l_boundary_no := l_in_flight_boundaries[l_worker_index];
                    l_chunk_sql := l_in_flight_sql[l_worker_index];

                    -- Reap and detach before dropping the slot: if either call
                    -- raises, the handler below rebuilds its set from l_in_flight
                    -- and must still contain this handle, or it is never waited on
                    -- or detached.
                    SELECT outcome.has_error,
                           outcome.sqlstate,
                           outcome.error_message
                    INTO l_outcome
                    FROM public.pg_background_outcome(l_handle.pid, l_handle.cookie) AS outcome;
                    PERFORM public.pg_background_detach(l_handle.pid, l_handle.cookie);

                    l_in_flight := l_in_flight[1:l_worker_index - 1]
                        || l_in_flight[l_worker_index + 1:];
                    l_in_flight_boundaries := l_in_flight_boundaries[1:l_worker_index - 1]
                        || l_in_flight_boundaries[l_worker_index + 1:];
                    l_in_flight_sql := l_in_flight_sql[1:l_worker_index - 1]
                        || l_in_flight_sql[l_worker_index + 1:];

                    l_phase := 'launch';
                    l_benign_claim := false;
                    l_retrying := false;

                    IF l_outcome.has_error THEN
                        -- P0C01 is the claim protocol's dedicated code. Treat it as
                        -- benign only if the boundary is now completed or cancelled;
                        -- that proves another coordinator or the cancellation path
                        -- won the race. A P0C01 from user SQL (or a stale claim
                        -- failure) with a still-pending boundary must be recorded
                        -- as an error, not retried forever.
                        IF l_outcome.sqlstate = 'P0C01'
                            AND EXISTS (SELECT 1
                                        FROM dml_utils_data.migration_boundary AS b
                                        WHERE b.run_id = l_run_id
                                          AND b.boundary_no = l_boundary_no
                                          AND (b.completed_at IS NOT NULL
                                            OR b.cancelled_at IS NOT NULL))
                        THEN
                            l_benign_claim := true;
                        ELSE
                            -- Retry a transient failure within the run's configured
                            -- budget, unless an earlier failure has already latched
                            -- the abort.
                            IF NOT l_aborting
                                AND l_outcome.sqlstate IN ('40001', '40P01', '55P03')
                            THEN
                                l_worker_index := pg_catalog.array_position(
                                        l_retry_boundaries, l_boundary_no);
                                IF l_worker_index IS NULL THEN
                                    l_retry_boundaries := l_retry_boundaries || l_boundary_no;
                                    l_retry_counts := l_retry_counts || 1;
                                    l_retry_attempt := 1;
                                ELSE
                                    l_retry_counts[l_worker_index] := l_retry_counts[l_worker_index] + 1;
                                    l_retry_attempt := l_retry_counts[l_worker_index];
                                END IF;

                                IF l_retry_attempt <= l_effective_max_retries THEN
                                    -- Wait the fixed backoff (cancellation- and
                                    -- pause-aware), then launch the chunk again.
                                    l_retrying := true;
                                    l_retry_boundary_no := l_boundary_no;
                                    l_retry_sql := l_chunk_sql;
                                    l_sleep_purpose := 'retry';
                                    l_sleep_remaining_ms := l_effective_retry_backoff_ms;
                                    l_phase := 'sleep';
                                END IF;
                            END IF;

                            IF NOT l_retrying THEN
                                -- Record the failure first, in its own
                                -- worker/transaction, so the row commits
                                -- autonomously and survives the re-raise.
                                PERFORM dml_utils_lib.record_migration_worker_failure(
                                        i_run_id => l_run_id,
                                        i_boundary_no => l_boundary_no,
                                        i_sqlstate => l_outcome.sqlstate,
                                        i_error_message => l_outcome.error_message);

                                -- Remember the first failure and stop launching
                                -- new chunks: the workers already in flight finish
                                -- (and commit) their current chunk, then this call
                                -- exits with the error.
                                IF NOT l_aborting THEN
                                    l_error_sqlstate := l_outcome.sqlstate;
                                    l_error_message := l_outcome.error_message;
                                    l_error_boundary_no := l_boundary_no;
                                END IF;
                                l_aborting := true;
                            END IF;
                        END IF;
                    END IF;

                    -- Throttle: after a successful chunk, wait before refilling the
                    -- slot. Skip the terminal-boundary case (no following chunk),
                    -- the drain path after a failure and the drain path after a
                    -- pause.
                    IF NOT l_benign_claim
                        AND NOT l_retrying
                        AND NOT l_aborting
                        AND NOT l_outcome.has_error
                        AND l_cancelled_at IS NULL
                        AND l_paused_at IS NULL
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
                        l_sleep_purpose := 'throttle';
                        l_sleep_remaining_ms := l_effective_sleep_ms;
                        l_phase := 'sleep';
                    END IF;

                WHEN 'sleep' THEN
                    IF i_commit THEN
                        -- One 100ms slice per pass, so the COMMIT after the block
                        -- falls between slices and the delay does not hold one
                        -- snapshot for its whole length. This is
                        -- sleep_between_migration_chunks' loop body, inlined because
                        -- a snapshot taken for a call to that function would last the
                        -- whole delay.
                        l_sleep_slice_ms := least(l_sleep_remaining_ms, 100);
                        PERFORM pg_catalog.pg_sleep(l_sleep_slice_ms::double precision / 1000.0);
                        l_sleep_remaining_ms := l_sleep_remaining_ms - l_sleep_slice_ms;
                    ELSE
                        -- A function sleeps the whole delay in one call. The helper
                        -- stops early on a pause or a cancel; the flags are re-read
                        -- below.
                        PERFORM dml_utils_lib.sleep_between_migration_chunks(
                                i_run_id => l_run_id,
                                i_sleep_ms => l_sleep_remaining_ms);
                        l_sleep_remaining_ms := 0;
                    END IF;

                    SELECT r.cancelled_at, r.paused_at
                    INTO l_cancelled_at, l_paused_at
                    FROM dml_utils_data.migration_run AS r
                    WHERE r.run_id = l_run_id;
                    l_last_state_check := pg_catalog.clock_timestamp();

                    IF l_cancelled_at IS NOT NULL THEN
                        l_phase := 'cancel';
                    ELSIF l_paused_at IS NOT NULL OR l_sleep_remaining_ms <= 0 THEN
                        -- The delay is over, or a pause cut it short. A retry is
                        -- launched again only when the run is not paused: a pause
                        -- stops the retry, and the main loop still drains the
                        -- other in-flight workers before returning.
                        l_phase := 'launch';
                        IF l_sleep_purpose = 'retry' AND l_paused_at IS NULL THEN
                            RAISE NOTICE 'retrying boundary % of run % after % (attempt % of %)',
                                l_retry_boundary_no, l_run_id, l_outcome.sqlstate,
                                l_retry_attempt, l_effective_max_retries;

                            l_handle := public.pg_background_launch(
                                    pg_catalog.format(
                                            'SELECT dml_utils_lib.process_migration_chunk(%s, %s, %L)',
                                            l_run_id, l_retry_boundary_no, l_retry_sql),
                                    0,
                                    pg_catalog.format('run %s chunk %s retry %s',
                                                      l_run_id, l_retry_boundary_no, l_retry_attempt));
                            l_in_flight := l_in_flight || l_handle;
                            l_in_flight_boundaries := l_in_flight_boundaries || l_retry_boundary_no;
                            l_in_flight_sql := l_in_flight_sql || l_retry_sql;
                        END IF;
                    END IF;

                WHEN 'cancel' THEN
                    -- Cancellation: stop the in-flight workers and record the
                    -- cancellation.
                    l_pids := ARRAY(SELECT (h).pid FROM pg_catalog.unnest(l_in_flight) AS h);
                    l_cookies := ARRAY(SELECT (h).cookie FROM pg_catalog.unnest(l_in_flight) AS h);
                    PERFORM dml_utils_lib.stop_migration_workers(
                            i_pids => l_pids,
                            i_cookies => l_cookies,
                            i_cancel => true,
                            i_wait_ms => 5000);

                    -- Record the cancellation on the run (if the caller's cancel
                    -- transaction is not the one that already did) and on every
                    -- boundary that did not complete, in an autonomous worker so the
                    -- marks survive a caller rollback. completed_at wins for a chunk
                    -- that finished before the cancel reached it.
                    BEGIN
                        SELECT outcome.completed,
                               outcome.has_error,
                               outcome.sqlstate,
                               outcome.error_message
                        INTO l_mark_outcome
                        FROM public.pg_background_run(pg_catalog.format(
                                'SELECT dml_utils_lib.mark_migration_run_cancelled(%L)',
                                l_run_id)
                             ) AS outcome;

                        IF NOT COALESCE(l_mark_outcome.completed, false)
                            OR COALESCE(l_mark_outcome.has_error, false)
                        THEN
                            RAISE WARNING
                                'could not mark the boundaries of cancelled run % cancelled: % (%)',
                                l_run_id, l_mark_outcome.error_message, l_mark_outcome.sqlstate;
                        END IF;
                    EXCEPTION
                        WHEN OTHERS THEN
                            RAISE WARNING
                                'could not launch the cancellation worker for run %: % (%)',
                                l_run_id, SQLERRM, SQLSTATE;
                    END;

                    RAISE NOTICE 'migration run % for label % cancelled', l_run_id, i_label;
                    RETURN;

                WHEN 'fail' THEN
                    -- l_aborting is the failure latch (not l_error_sqlstate, which a
                    -- worker could in principle report as NULL).
                    RAISE EXCEPTION 'chunk % for run % failed: %', l_error_boundary_no, l_run_id,
                        l_error_message
                        USING ERRCODE = COALESCE(l_error_sqlstate, 'P0001');

                WHEN 'pause' THEN
                    -- Pause: the in-flight workers have committed or rolled back
                    -- their current chunk. If every chunk finished before the pause
                    -- reached the coordinator, completion wins; otherwise leave the
                    -- run resumable and return without marking anything cancelled.
                    PERFORM dml_utils_lib.complete_migration_run(i_run_id => l_run_id);

                    IF NOT EXISTS (SELECT 1
                                   FROM dml_utils_data.migration_run AS r
                                   WHERE r.run_id = l_run_id
                                     AND r.completed_at IS NOT NULL)
                    THEN
                        RAISE NOTICE 'migration run % for label % is paused; resume it with dml_utils.resume_migration_run', l_run_id, i_label;
                    END IF;
                    RETURN;

                WHEN 'finish' THEN
                    EXIT;
            END CASE;
        EXCEPTION
            WHEN OTHERS THEN
                -- An unexpected error (a failed launch, a render error, ...) would
                -- otherwise abandon the workers already in flight. Wait for each to
                -- finish and detach it, so it commits/finishes and its handle does
                -- not leak, then re-raise the original error.
                l_pids := ARRAY(SELECT (h).pid FROM pg_catalog.unnest(l_in_flight) AS h);
                l_cookies := ARRAY(SELECT (h).cookie FROM pg_catalog.unnest(l_in_flight) AS h);
                -- Bounded: a worker that cannot finish (for example it is blocked on
                -- a lock held by the caller's own transaction) must not hang the
                -- handler forever. The wait is per worker and sequential, so the
                -- worst case is 30s per in-flight thread. On timeout
                -- stop_migration_workers warns and detaches; because this drain
                -- does not cancel, the worker may still finish and commit its chunk
                -- after the coordinator re-raises. That is safe: a committed
                -- boundary is complete, and a pending one is re-runnable on resume.
                PERFORM dml_utils_lib.stop_migration_workers(
                        i_pids => l_pids,
                        i_cookies => l_cookies,
                        i_cancel => false,
                        i_wait_ms => 30000);
                RAISE;
        END;

        -- The coordinator's session holds no snapshot across passes, so its xmin
        -- is renewed at every step: this is what lets VACUUM reclaim the rows of
        -- every chunk that has already committed while the run is still going.
        IF i_commit THEN
            COMMIT;
        END IF;
    END LOOP;

    -- All boundaries are claimed and every chunk SQL already ran; record the run
    -- completion through the owner-owned helper. This routine stays SECURITY
    -- INVOKER so the worker chunk SQL runs as the caller; only the helper writes
    -- metadata.
    PERFORM dml_utils_lib.complete_migration_run(i_run_id => l_run_id);
END;
$$;

COMMENT ON PROCEDURE dml_utils_lib.coordinate_migration_chunks IS
    'The coordinator behind dml_utils.run_migration_chunks and '
        'dml_utils.run_migration_chunks_committing: resolves or resumes the run for '
        'the label, then launches, waits for and settles the chunk workers (see '
        'run_migration_chunks for the semantics: cancellation, pause, transient '
        'retries, failure recording, throttling, partition-wise). With i_commit '
        'false it never COMMITs, so the function calls it inside the caller''s '
        'transaction. With i_commit true it COMMITs after every pass of its loop, '
        'so it must be CALLed at top level, outside a transaction block and not '
        'from a function, and its session never holds one snapshot across chunks. '
        'The loop is a state machine because PostgreSQL does not allow COMMIT '
        'inside a block with an EXCEPTION clause: the clause drains the in-flight '
        'workers on an unexpected error, so it wraps one step and the COMMIT '
        'follows it. Requires READ COMMITTED (assert_read_committed raises 0A000 '
        'otherwise).';
