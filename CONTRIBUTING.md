# Contributing to dml_utils

Thanks for your interest in dml_utils. This document covers how to build, test,
and submit changes.

## Prerequisites

- **JDK 25** (the build targets Java 25).
- **Docker** — required for `./mvnw verify`. Integration tests and jOOQ code
  generation start real PostgreSQL containers via Testcontainers, so the build
  fails without a running Docker daemon.
- **Maven wrapper** — always use `./mvnw`; do not rely on a globally installed
  Maven.
- **Submodule** — `scripts/` is a git submodule of
  [`workflow_library`](https://github.com/eyupmiduck/workflow_library) holding
  the shared build/dev shims. Run `git submodule update --init` after cloning (or clone with `--recurse-submodules`).
- **Python + SQLFluff** — only needed if you touch changelog SQL. The repo's
  `.venv` is the expected environment (see below).
- **GitHub Packages token** — the test-scoped `liquibase-validation` dependency
  is read from GitHub Packages, which requires authentication even for public
  packages. Add a classic personal access token with the `read:packages` scope
  under the `github` server id in `~/.m2/settings.xml`. See the
  [`liquibase_validation`](https://github.com/eyupmiduck/liquibase_validation)
  README for the snippet.

## Build and test

Everything runs through the Maven wrapper:

```sh
./mvnw clean verify                     # full build, tests, SQLFluff, and checks
./mvnw -pl dml_utils -am verify         # one module (plus its reactor deps)
./mvnw -pl dml_utils -am test -Dtest=ExampleTableTest   # one test class
```

`-am` (also make) is required for single-module builds because reactor
dependencies are not installed to the local repository.

### Local database

For manual experimentation, the scripts in `scripts/` run the shared
`scripts/compose.yaml` stack and apply the changelog:

```sh
scripts/start-local-db.sh     # start PostgreSQL and apply the changelog
scripts/stop-local-db.sh      # stop, keep data
scripts/refresh-local-db.sh   # drop the volume and re-apply migrations
```

### PostgreSQL version

The PostgreSQL version the build runs against is a single source of truth
controlled by the `postgres.version` Maven property (default `17-alpine`); the
custom image tag is derived from it. CI builds and tests against PostgreSQL 16,
17, and 18. Build the custom image for the version you need, then pass the
version through:

```sh
scripts/build-postgres-image.sh postgres:16-alpine
./mvnw verify -Dpostgres.version=16-alpine
```

## Database changes

All schema changes go through **Liquibase**. Add the changeset as a
`<changeSet id="NNN-description">` entry in
`dml_utils/src/main/resources/db/changelog/changes/changes.xml`, with the SQL
in separate files:

```
dml_utils/src/main/resources/db/changelog/changes/sql_changes/NNN-description.sql
dml_utils/src/main/resources/db/changelog/changes/rollback/NNN-description-rollback.sql
```

`db.changelog-master.xml` includes `changes.xml`; no per-changeset include is
needed.

Rules:

- **Do not embed SQL in XML.** Reference the `.sql` file with
  `<sqlFile path="..." relativeToChangelogFile="true"/>`, and the rollback with
  a `<rollback>` block pointing at the rollback file.
- Every change needs a working rollback.
- Keep changesets small, focused, and safe to deploy against a live database (see the guidance in `AGENTS.md`).
- Do not modify an already-deployed changeset. Add a new one instead.
- Lint changelog SQL before submitting:

  ```sh
  .venv/bin/sqlfluff lint dml_utils/src/main/resources/db/changelog
  scripts/sqlfluff-fix.sh    # auto-fix where possible
  ```

  SQLFluff runs automatically during `verify`; skip it locally with
  `-Dskip.sqlfluff` when iterating, but make sure it passes before you open a
  PR.
- The changelog linter (`liquibase-validation`) also runs
  during `verify`. It applies rules that combine the SQL with the changeset
  attributes (for example `runInTransaction`), configured in
  `dml_utils/.liquibase-linter.yml`; accept known findings in
  `dml_utils/.liquibase-linter-whitelist.yml` (an unaccepted finding and a stale
  whitelist entry both fail the build); skip it with
  `-Dskip.liquibase-linter` when iterating.

## Java conventions

- Prefer simple, explicit Java over unnecessary abstractions; keep methods
  small and focused.
- Use modern Java 25 features where they improve readability.
- Prefer jOOQ's type-safe DSL over hand-built SQL strings, and use generated
  jOOQ classes where available.
- Do not duplicate schema definitions in Java.
- Avoid adding dependencies or frameworks without a clear benefit.
- Do not add comments that restate the code.

## Tests

- Use JUnit 5.
- Integration tests that need PostgreSQL must use Testcontainers — never H2 or
  another database.
- Database tests extend `PostgresTestBase`, which shares one container and
  clones a template database per test class. Use the inherited `dsl`; do not
  start containers or run Liquibase inside individual tests.
- Prefer the shared contracts from `liquibase-validation` (layout, comments,
  audit columns, routine signatures/documentation, privileges, routine security,
  tracking tables, round trip and re-apply) over copying their assertions. A
  contract subclass returns the one `PostgresTestBase.PROFILE` value, so the
  roles, image, schemas, changelog and installed ddl_utils baseline cannot
  drift. The round-trip and re-apply contracts are live Testcontainers tests and
  run in `verify`.
- Every test class and test method needs Javadoc describing the behavior it
  verifies.
- Add a regression test when fixing a bug.
- The previous-release upgrade test is `BaselineUpgradeTest`. It resolves the
  latest published release below the current revision and compares the upgraded
  catalog with the current changelog. It is enabled in `verify`; disable it only
  while a baseline is edited in place, and re-enable it after the replacement
  release. Any later in-place schema edit repeats that cycle.

## Branches and commits

- Never commit directly to `main`; work on a feature branch.
- Keep commits focused, and write imperative, descriptive messages (e.g.
  `Add rollback for the lock settings changeset`).
- Do not commit generated build output (`target/`), secrets, credentials, or
  local IDE files.

## Pull requests

Open a PR against `main` and fill in the template. Before requesting review:

1. Review your diff and remove dead code and unused imports.
2. Check for accidental API or schema changes.
3. Run the relevant tests, then `./mvnw verify`.
4. Confirm the CI checks are green — the Maven build, the secret scan, and the
   automated code review all run on every PR.

## Reporting bugs and security issues

- Bugs and feature requests: use the
  [issue forms](https://github.com/eyupmiduck/dml_utils/issues/new/choose).
- Security vulnerabilities: **do not** open a public issue. Follow
  [`SECURITY.md`](SECURITY.md).

## License

By contributing, you agree that your contributions are licensed under the
terms of the project's [LICENSE](LICENSE).
