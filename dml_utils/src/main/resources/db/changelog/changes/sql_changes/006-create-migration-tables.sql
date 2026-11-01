CREATE TABLE dml_utils_data.migration_run
(
    run_id                          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    label                           text                                NOT NULL,
    sql_text                        text                                NOT NULL,
    chunk_size                      integer                             NOT NULL,
    chunk_by                        dml_utils_data.chunking_strategy    NOT NULL DEFAULT 'primary_key',
    threads                         dml_utils_data.positive_integer     NOT NULL,
    sleep_ms                        dml_utils_data.non_negative_integer NOT NULL DEFAULT 0,
    driving_table_schema_name       dml_utils_data.non_null_text        NOT NULL,
    driving_table_name              dml_utils_data.non_null_text        NOT NULL,
    driving_table_alias             dml_utils_data.non_null_text        NOT NULL DEFAULT 't',
    driving_table_relation_filepath text,
    -- The ordered key for chunk_by = 'ordered_key': the index and a snapshot of
    -- its columns/kinds, so resume and explain use the exact key the boundaries
    -- were built from. Unset for primary_key and blocks runs.
    ordered_key_index               name,
    ordered_key_columns             name[],
    ordered_key_kinds               text[],
    created_at                      timestamptz                         NOT NULL DEFAULT pg_catalog.now(),
    updated_at                      timestamptz                         NOT NULL DEFAULT pg_catalog.now(),
    started_at                      timestamptz,
    boundaries_calculated_at        timestamptz,
    total_chunks                    bigint                              NOT NULL DEFAULT 0,
    total_chunks_completed          bigint                              NOT NULL DEFAULT 0,
    completed_at                    timestamptz,
    cancelled_at                    timestamptz,
    archived_at                     timestamptz,
    CONSTRAINT migration_run_chunk_size_check CHECK (chunk_size > 0),
    -- A block run always records the physical relation filepath it was computed
    -- against, and a primary-key run never does, so a block resume can detect a
    -- heap rewrite (see dml_utils_lib.relation_filepath).
    CONSTRAINT migration_run_chunk_by_filepath_check CHECK (
        (chunk_by = 'blocks') = (driving_table_relation_filepath IS NOT NULL)
        ),
    -- An ordered-key run records the index and the key descriptor; the other
    -- strategies leave all three unset. All-or-nothing: a partly populated
    -- descriptor is rejected too, so a non-ordered run cannot carry a stale key
    -- that explain or resume might later pick up.
    CONSTRAINT migration_run_ordered_key_check CHECK (
        pg_catalog.num_nonnulls(ordered_key_index, ordered_key_columns, ordered_key_kinds)
            = CASE WHEN chunk_by = 'ordered_key' THEN 3 ELSE 0 END
        ),
    CONSTRAINT migration_run_ordered_key_shape_check CHECK (
        ordered_key_columns IS NULL
            OR (pg_catalog.cardinality(ordered_key_columns) BETWEEN 1 AND 3
                AND pg_catalog.cardinality(ordered_key_kinds)
                    = pg_catalog.cardinality(ordered_key_columns)
                AND ordered_key_kinds <@ ARRAY['bigint', 'text', 'uuid', 'timestamptz']::text[])
        ),
    -- The run's milestones are ordered: the boundary calculation cannot finish
    -- before the run started, and the run cannot finish before it started. Each
    -- later stamp may only be set once started_at is set, and never earlier. The
    -- explicit started_at IS NOT NULL is required because a NULL comparison would
    -- otherwise make the check pass as unknown.
    CONSTRAINT migration_run_time_order_check CHECK (
        (completed_at IS NULL
            OR (started_at IS NOT NULL AND completed_at >= started_at))
            AND (boundaries_calculated_at IS NULL
            OR (started_at IS NOT NULL AND boundaries_calculated_at >= started_at))
        ),
    -- A cancelled run is terminal and its cancellation cannot precede the start.
    -- completed_at and cancelled_at are mutually exclusive.
    CONSTRAINT migration_run_cancelled_at_check CHECK (
        cancelled_at IS NULL
            OR (started_at IS NOT NULL AND cancelled_at >= started_at)
        ),
    CONSTRAINT migration_run_completed_or_cancelled_check CHECK (
        completed_at IS NULL OR cancelled_at IS NULL
        ),
    -- The run's progress counters: total_chunks is written once by
    -- populate_migration_boundaries (the boundaries minus the terminal high-water
    -- boundary) and total_chunks_completed is advanced by each chunk worker. Both
    -- are non-negative and a run cannot complete more chunks than it has.
    CONSTRAINT migration_run_total_chunks_check CHECK (total_chunks >= 0),
    CONSTRAINT migration_run_total_chunks_completed_check CHECK (total_chunks_completed >= 0),
    CONSTRAINT migration_run_progress_check CHECK (total_chunks_completed <= total_chunks)
);

COMMENT ON TABLE dml_utils_data.migration_run IS
    'One row per boundary calculation; groups the boundaries of migration_boundary.';
COMMENT ON COLUMN dml_utils_data.migration_run.run_id IS
    'Surrogate primary key identifying the migration run.';
COMMENT ON COLUMN dml_utils_data.migration_run.label IS
    'Caller-supplied run label; unique among the runs that are not archived.';
COMMENT ON COLUMN dml_utils_data.migration_run.sql_text IS
    'The migration SQL recorded for the run; stored as given.';
COMMENT ON COLUMN dml_utils_data.migration_run.chunk_size IS
    'Number of units per chunk used to compute the boundaries: source rows for '
        'chunk_by = primary_key or ordered_key, heap blocks for chunk_by = blocks.';
COMMENT ON COLUMN dml_utils_data.migration_run.chunk_by IS
    'Chunking strategy: primary_key (fixed rows in primary-key order, the '
        'default), ordered_key (fixed rows in a caller-selected unique index '
        'order) or blocks (physical heap block ranges). Immutable.';
COMMENT ON COLUMN dml_utils_data.migration_run.threads IS
    'Number of pg_background workers used to process the run''s chunks; may be '
        'changed with dml_utils.set_migration_run_threads while the run is unfinished.';
COMMENT ON COLUMN dml_utils_data.migration_run.sleep_ms IS
    'Milliseconds to wait after each successfully completed chunk before '
        'launching another; 0 means no delay. Set when the run is created and '
        'changed for later calls with dml_utils.set_migration_run_sleep_ms.';
COMMENT ON COLUMN dml_utils_data.migration_run.driving_table_schema_name IS
    'Schema of the driving table whose primary-key order defines the chunks.';
COMMENT ON COLUMN dml_utils_data.migration_run.driving_table_name IS
    'Driving table whose primary-key order defines the chunks.';
COMMENT ON COLUMN dml_utils_data.migration_run.driving_table_alias IS
    'Alias the driving table is referred to as in the recorded SQL; stored at '
        'creation so a resumed run renders with the same alias.';
COMMENT ON COLUMN dml_utils_data.migration_run.driving_table_relation_filepath IS
    'For chunk_by = blocks, the pg_relation_filepath of the driving table at '
        'boundary-calculation time, used to detect a heap rewrite before a '
        'resume; NULL for primary_key and ordered_key runs. Immutable.';
COMMENT ON COLUMN dml_utils_data.migration_run.ordered_key_index IS
    'For chunk_by = ordered_key, the name of the unique index whose order the '
        'boundaries use; NULL for the other strategies. Immutable.';
COMMENT ON COLUMN dml_utils_data.migration_run.ordered_key_columns IS
    'For chunk_by = ordered_key, the key columns in index order at boundary '
        'time; NULL for the other strategies. Immutable.';
COMMENT ON COLUMN dml_utils_data.migration_run.ordered_key_kinds IS
    'For chunk_by = ordered_key, the boundary kind of each key column in '
        'ordered_key_columns order (bigint, text, uuid or timestamptz); NULL for '
        'the other strategies. Immutable.';
COMMENT ON COLUMN dml_utils_data.migration_run.created_at IS
    'Row creation time.';
COMMENT ON COLUMN dml_utils_data.migration_run.updated_at IS
    'Last update time, maintained by the set_updated_at() trigger.';
COMMENT ON COLUMN dml_utils_data.migration_run.started_at IS
    'Actual server time when the run began, at the start of the boundary '
        'calculation; written by populate_migration_boundaries in the worker''s '
        'transaction, so it commits with the run and boundaries and persists '
        'across a failed processing attempt and a resume.';
COMMENT ON COLUMN dml_utils_data.migration_run.boundaries_calculated_at IS
    'Actual server time when the boundary (chunk range) calculation completed, '
        'after the boundaries were inserted; written in the same worker '
        'transaction as started_at, so it persists across a failed processing '
        'attempt and a resume.';
COMMENT ON COLUMN dml_utils_data.migration_run.total_chunks IS
    'Total number of chunks in the run: the boundary count minus the terminal '
        'high-water boundary, and 0 for an empty source. Written once by '
        'populate_migration_boundaries when the boundaries are calculated.';
COMMENT ON COLUMN dml_utils_data.migration_run.total_chunks_completed IS
    'Number of chunks whose worker recorded a completion; advanced in the same '
        'transaction as the boundary completion, so it cannot drift from '
        'migration_boundary.';
COMMENT ON COLUMN dml_utils_data.migration_run.completed_at IS
    'Actual server time when the run finished processing all chunks; NULL while '
        'the run is unfinished. Written in the caller''s transaction, so a failed '
        'processing attempt rolls it back.';
COMMENT ON COLUMN dml_utils_data.migration_run.cancelled_at IS
    'Actual server time when the run was cancelled; NULL unless cancelled. A '
        'cancelled run is terminal: archive it and create a new run to resume.';
COMMENT ON COLUMN dml_utils_data.migration_run.archived_at IS
    'Set when the run is archived; NULL means the run is active.';

CREATE UNIQUE INDEX migration_run_label_active_idx
    ON dml_utils_data.migration_run (label)
    WHERE archived_at IS NULL;

COMMENT ON INDEX dml_utils_data.migration_run_label_active_idx IS
    'Ensures at most one active (not archived) run per label.';

-- migration_run_summary(label) has no archived_at predicate, so neither partial
-- label index can serve it; a non-partial index does.
CREATE INDEX migration_run_label_idx
    ON dml_utils_data.migration_run (label);

COMMENT ON INDEX dml_utils_data.migration_run_label_idx IS
    'Supports migration_run_summary(label), which matches active and archived runs.';

-- The active-run index above only covers archived_at IS NULL, so the archived-run
-- maintenance routines cannot use it. Index the archived side for both the
-- label-filtered and the whole-table delete.
CREATE INDEX migration_run_label_archived_idx
    ON dml_utils_data.migration_run (label)
    WHERE archived_at IS NOT NULL;

COMMENT ON INDEX dml_utils_data.migration_run_label_archived_idx IS
    'Supports delete_archived_migration_runs(label) over archived runs.';

CREATE INDEX migration_run_archived_idx
    ON dml_utils_data.migration_run (archived_at)
    WHERE archived_at IS NOT NULL;

COMMENT ON INDEX dml_utils_data.migration_run_archived_idx IS
    'Supports delete_archived_migration_runs() over archived runs.';

CREATE TABLE dml_utils_data.migration_boundary
(
    run_id       bigint                       NOT NULL,
    boundary_no  bigint                       NOT NULL,
    boundary_id  dml_utils_data.migration_key NOT NULL,
    created_at   timestamptz                  NOT NULL DEFAULT pg_catalog.now(),
    updated_at   timestamptz                  NOT NULL DEFAULT pg_catalog.now(),
    started_at   timestamptz,
    completed_at timestamptz,
    cancelled_at timestamptz,
    PRIMARY KEY (run_id, boundary_no),
    CONSTRAINT migration_boundary_run_fk
        FOREIGN KEY (run_id)
            REFERENCES dml_utils_data.migration_run (run_id)
            ON DELETE CASCADE,
    -- Boundaries are contiguous 0..N; the runner finds the next chunk with
    -- boundary_no + 1, so a negative number would break that arithmetic.
    CONSTRAINT migration_boundary_no_check CHECK (boundary_no >= 0),
    -- The boundary key must be canonical; see
    -- dml_utils_lib.migration_key_is_canonical for the rule.
    CONSTRAINT migration_boundary_key_check CHECK (
        dml_utils_lib.migration_key_is_canonical(boundary_id)
        ),
    -- A chunk cannot finish before it started; see
    -- migration_run_time_order_check for the rationale. Both NULL means not
    -- started; started_at set with completed_at NULL means in progress.
    CONSTRAINT migration_boundary_time_order_check CHECK (
        completed_at IS NULL
            OR (started_at IS NOT NULL AND completed_at >= started_at)
        )
);

COMMENT ON TABLE dml_utils_data.migration_boundary IS
    'Precomputed fixed-row chunk boundaries: one starting boundary per chunk plus '
        'a final high-water boundary. A boundary is terminal when no following '
        'boundary exists for the run.';
COMMENT ON COLUMN dml_utils_data.migration_boundary.run_id IS
    'Migration run this boundary belongs to.';
COMMENT ON COLUMN dml_utils_data.migration_boundary.boundary_no IS
    'Boundary order within the run; 0 is the first chunk start.';
COMMENT ON COLUMN dml_utils_data.migration_boundary.boundary_id IS
    'Packed primary-key value (position-aligned arrays in migration_key) of the '
        'first row in the chunk, or the captured maximum for the final high-water '
        'boundary.';
COMMENT ON COLUMN dml_utils_data.migration_boundary.created_at IS
    'Row creation time.';
COMMENT ON COLUMN dml_utils_data.migration_boundary.updated_at IS
    'Last update time, maintained by the set_updated_at() trigger.';
COMMENT ON COLUMN dml_utils_data.migration_boundary.started_at IS
    'Actual server time when the chunk for this boundary was claimed and began '
        'processing; NULL until then. Written in the claim''s transaction, so a '
        'chunk that fails rolls it back and the boundary is retried; a boundary '
        'reports started_at only for a chunk that completed.';
COMMENT ON COLUMN dml_utils_data.migration_boundary.completed_at IS
    'Actual server time when the chunk for this boundary finished; NULL until then.';
COMMENT ON COLUMN dml_utils_data.migration_boundary.cancelled_at IS
    'Actual server time when the boundary was cancelled; NULL unless the run was '
        'cancelled before this chunk completed. A boundary with completed_at set '
        'is authoritative even if cancelled_at is also set.';

-- A partial index so the coordinator's next-chunk lookup (the first boundary
-- with completed_at IS NULL) seeks straight to the pending rows instead of
-- scanning the completed prefix on every launch. Created with the table, so it
-- needs no CONCURRENTLY.
CREATE INDEX migration_boundary_pending_idx
    ON dml_utils_data.migration_boundary (run_id, boundary_no)
    WHERE completed_at IS NULL;

COMMENT ON INDEX dml_utils_data.migration_boundary_pending_idx IS
    'Supports the coordinator''s next-chunk lookup (run_migration_chunks): the '
        'partial predicate keeps only pending boundaries, so the first unclaimed '
        'boundary is found without scanning the completed prefix on every launch.';

CREATE TABLE dml_utils_data.migration_error
(
    error_id    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id      bigint      NOT NULL,
    boundary_no bigint      NOT NULL,
    sqlstate    text        NOT NULL,
    message     text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT pg_catalog.now(),
    updated_at  timestamptz NOT NULL DEFAULT pg_catalog.now(),
    CONSTRAINT migration_error_boundary_fk
        FOREIGN KEY (run_id, boundary_no)
            REFERENCES dml_utils_data.migration_boundary (run_id, boundary_no)
            ON DELETE CASCADE
);

COMMENT ON TABLE dml_utils_data.migration_error IS
    'One row per failed chunk worker, recording the SQLSTATE and message so a '
        'run can be diagnosed without the worker logs.';
COMMENT ON COLUMN dml_utils_data.migration_error.error_id IS
    'Surrogate primary key identifying the error record.';
COMMENT ON COLUMN dml_utils_data.migration_error.run_id IS
    'Migration run whose chunk failed.';
COMMENT ON COLUMN dml_utils_data.migration_error.boundary_no IS
    'Boundary (chunk) of the run that failed.';
COMMENT ON COLUMN dml_utils_data.migration_error.sqlstate IS
    'SQLSTATE of the failure, as reported by the chunk worker.';
COMMENT ON COLUMN dml_utils_data.migration_error.message IS
    'Error message of the failure, as reported by the chunk worker.';
COMMENT ON COLUMN dml_utils_data.migration_error.created_at IS
    'Row creation time.';
COMMENT ON COLUMN dml_utils_data.migration_error.updated_at IS
    'Last update time, maintained by the set_updated_at() trigger.';

-- Index the referencing side of the FK so a boundary (or run) delete does not
-- scan migration_error for every cascaded row.
CREATE INDEX migration_error_boundary_idx
    ON dml_utils_data.migration_error (run_id, boundary_no);

-- migration_errors(run_id) orders by error_id; the FK index above is keyed by
-- boundary_no, so it cannot provide that order.
CREATE INDEX migration_error_run_error_idx
    ON dml_utils_data.migration_error (run_id, error_id);
