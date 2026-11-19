CREATE OR REPLACE FUNCTION dml_utils_lib.migration_run_calculation_lock_key(
    i_run_id dml_utils_data.non_null_bigint
)
    RETURNS bigint
    LANGUAGE sql
    IMMUTABLE
    STRICT
    PARALLEL SAFE
    SECURITY INVOKER
AS
$$
    -- A single bigint advisory-lock key, derived from the run id. The prefix keeps
    -- the namespace distinct from any other advisory lock a caller may take.
    -- Concatenation, not format(): format() is only STABLE (its type output can
    -- depend on session settings), while bigint's text output is immutable, so
    -- this expression can honestly be declared IMMUTABLE.
SELECT pg_catalog.hashtextextended(
               'dml_utils.migration_run.calculation.' || i_run_id::text, 0);
$$;

COMMENT ON FUNCTION dml_utils_lib.migration_run_calculation_lock_key IS
    'Returns the advisory-lock key used to serialize partition-wise boundary '
        'calculation for a run. IMMUTABLE: the prefix concatenation with bigint''s '
        'text output and pg_catalog.hashtextextended are immutable (pg_catalog.format '
        'is only STABLE, so it is not used). STRICT: a NULL argument would yield '
        'NULL instead of collapsing every NULL caller onto one shared key, but the '
        'non-null run-id domain rejects NULL at the call. PARALLEL '
        'SAFE: it reads no state and takes no lock.';
