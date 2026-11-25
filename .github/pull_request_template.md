## Summary

<!-- What does this change do, and why? -->

## Related issue

<!-- e.g. Closes #123, or a beads id such as dml-p9i.3 -->

## Type of change

- [ ] Bug fix
- [ ] New feature
- [ ] Refactor / cleanup
- [ ] Documentation
- [ ] Build / CI

## Checklist

- [ ] `./mvnw verify` passes locally (Docker is running for Testcontainers and jOOQ codegen)
- [ ] SQLFluff passes; I ran `scripts/sqlfluff-fix.sh` if it did not
- [ ] Schema changes are Liquibase changesets added to `changes/changes.xml`, with forward SQL in `sql_changes/` and
  rollback SQL in `rollback/`
- [ ] Tests are added or updated, have Javadoc, and database tests extend `PostgresTestBase`
- [ ] Database access uses jOOQ's DSL; raw SQL is limited to DDL, catalog
  introspection, session settings, and SQL the DSL cannot express
- [ ] No secrets, credentials, or generated build output are included
- [ ] Documentation is updated where needed

## Notes for reviewers

<!-- Trade-offs, follow-ups, or anything you are unsure about. -->
