# Stored procedures

One procedure per `.sql` file, grouped by the schema that owns it, loaded by
`changes/procedures.xml` (one `createProcedure` per `runOnChange` changeset; the
matching drop lives in `changes/procedures-rollback/`).

A routine is a procedure only when it has to `COMMIT`: a function cannot commit,
so it can never leak transaction control into code that must run inside the
caller's transaction. Everything else in dml_utils is a function (see
[`../functions/README.md`](../functions/README.md)). Both routines here are
`SECURITY INVOKER`, like the function they serve.

A procedure that `COMMIT`s must be `CALL`ed at top level: not inside an explicit
transaction block (`BEGIN ... COMMIT`), not from a function, and not through a
client that wraps the call in a transaction. PostgreSQL then fails the first
`COMMIT` with `2D000` (`invalid_transaction_termination`).

### `dml_utils.run_migration_chunks_committing(...)`

`dml_utils.run_migration_chunks_committing(i_sql_text, i_driving_table_schema_name, i_driving_table_name, i_label [, i_chunk_size, i_threads, i_driving_table_alias, i_chunk_by, i_poll_timeout_ms, i_sleep_ms, i_boundary_key_index, i_partition_wise, i_max_retries, i_retry_backoff_ms])`

A `PROCEDURE` (`CALL`, not `SELECT`) with the same parameters, defaults and
semantics as `dml_utils.run_migration_chunks`: labels, resume, threads, sleep,
partition-wise, ordered key, error recording, cancellation, pause and resume,
transient retries, rows-affected recording and the `READ COMMITTED` requirement.
`SECURITY INVOKER`. It `COMMIT`s after every launch, wait and settle step, so the
coordinator holds no snapshot across chunks (its `backend_xmin` is renewed every
step) and `VACUUM` can reclaim the rows of every chunk already committed while the
run goes on. `run_migration_chunks` is one statement, so its snapshot holds the
vacuum horizon until it returns.

Its first statement is a `COMMIT`, so a call that cannot commit (inside a
transaction block, or from a function) fails with `2D000` before the run is
created and a misplaced call changes nothing. The coordinator's `READ COMMITTED`
check runs in the transaction that `COMMIT` starts, so a session whose default
isolation is `REPEATABLE READ` or `SERIALIZABLE` is rejected with `0A000`, also
before the run exists. A partition-wise run's boundary calculation happens before
the first pass, in the one transaction the `COMMIT` opened.

### `dml_utils_lib.coordinate_migration_chunks(...)`

`dml_utils_lib.coordinate_migration_chunks(i_sql_text, i_driving_table_schema_name, i_driving_table_name, i_label, i_chunk_size, i_threads, i_driving_table_alias, i_chunk_by, i_poll_timeout_ms, i_sleep_ms, i_boundary_key_index, i_partition_wise, i_max_retries, i_retry_backoff_ms, i_commit)`

A `PROCEDURE`, `SECURITY INVOKER`. Takes `run_migration_chunks`' parameters, with
no defaults, plus `i_commit boolean` (not null). It is the one coordinator behind
`dml_utils.run_migration_chunks` (`i_commit => false`) and
`dml_utils.run_migration_chunks_committing` (`i_commit => true`): it resolves or
resumes the run, then launches, waits for and settles the chunk workers, and
records completion. With `i_commit` false it never `COMMIT`s, so the function can
`CALL` it inside the caller's transaction. With `i_commit` true it `COMMIT`s after
every pass of its loop, so it must itself be `CALL`ed at top level.

The loop is a state machine (phases `launch`, `wait`, `settle`, `sleep`,
`cancel`, `fail`, `pause`, `finish`, one per pass) because PostgreSQL does not
allow `COMMIT` inside a block that has an `EXCEPTION` clause: the clause drains
the in-flight workers on an unexpected error, so it wraps a single step and the
`COMMIT` follows the block. With `i_commit` the per-chunk delay and a retry
backoff are slept one 100ms slice per pass, so a `COMMIT` falls between slices.
