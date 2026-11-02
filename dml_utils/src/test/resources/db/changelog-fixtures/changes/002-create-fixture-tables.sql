CREATE TABLE dml_utils_fixtures.test_bigint
(
    id      bigint PRIMARY KEY,
    payload text
);

COMMENT ON TABLE dml_utils_fixtures.test_bigint IS
    'Fixture: a table with a single bigint primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_bigint.id IS
    'Fixture primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_bigint.payload IS
    'Fixture payload column.';

-- A second bigint-keyed table, for tests that must tell two driving tables
-- apart (for example resuming a run against a different table).
CREATE TABLE dml_utils_fixtures.test_other
(
    id      bigint PRIMARY KEY,
    payload text
);

COMMENT ON TABLE dml_utils_fixtures.test_other IS
    'Fixture: a second table with a single bigint primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_other.id IS
    'Fixture primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_other.payload IS
    'Fixture payload column.';

CREATE TABLE dml_utils_fixtures.test_integer
(
    id      integer PRIMARY KEY,
    payload text
);

COMMENT ON TABLE dml_utils_fixtures.test_integer IS
    'Fixture: a table with a single integer primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_integer.id IS
    'Fixture primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_integer.payload IS
    'Fixture payload column.';

CREATE TABLE dml_utils_fixtures.test_smallint
(
    id      smallint PRIMARY KEY,
    payload text
);

COMMENT ON TABLE dml_utils_fixtures.test_smallint IS
    'Fixture: a table with a single smallint primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_smallint.id IS
    'Fixture primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_smallint.payload IS
    'Fixture payload column.';

CREATE TABLE dml_utils_fixtures.test_text
(
    id      text PRIMARY KEY,
    payload text
);

COMMENT ON TABLE dml_utils_fixtures.test_text IS
    'Fixture: a table with a single text primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_text.id IS
    'Fixture primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_text.payload IS
    'Fixture payload column.';

CREATE TABLE dml_utils_fixtures.test_uuid
(
    id      uuid PRIMARY KEY,
    payload text
);

COMMENT ON TABLE dml_utils_fixtures.test_uuid IS
    'Fixture: a table with a single uuid primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_uuid.id IS
    'Fixture primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_uuid.payload IS
    'Fixture payload column.';

-- The primary-key column is not named id, to prove the routines read the name
-- from the catalog.
CREATE TABLE dml_utils_fixtures.test_key
(
    key     bigint PRIMARY KEY,
    payload text
);

COMMENT ON TABLE dml_utils_fixtures.test_key IS
    'Fixture: a table whose primary key is named key, not id.';
COMMENT ON COLUMN dml_utils_fixtures.test_key.key IS
    'Fixture primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_key.payload IS
    'Fixture payload column.';

CREATE TABLE dml_utils_fixtures.test_no_pk
(
    id      bigint,
    payload text
);

COMMENT ON TABLE dml_utils_fixtures.test_no_pk IS
    'Fixture: a table without a primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_no_pk.id IS
    'Fixture id column.';
COMMENT ON COLUMN dml_utils_fixtures.test_no_pk.payload IS
    'Fixture payload column.';

CREATE TABLE dml_utils_fixtures.test_composite_pk
(
    a       bigint,
    b       bigint,
    payload text,
    PRIMARY KEY (a, b)
);

COMMENT ON TABLE dml_utils_fixtures.test_composite_pk IS
    'Fixture: a table with a composite primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_pk.a IS
    'Fixture primary-key part.';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_pk.b IS
    'Fixture primary-key part.';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_pk.payload IS
    'Fixture payload column.';

-- A two-column, mixed-kind primary key (bigint + text), to exercise a
-- heterogeneous key at the shortest composite arity.
CREATE TABLE dml_utils_fixtures.test_composite_mixed
(
    a       bigint,
    b       text,
    payload text,
    PRIMARY KEY (a, b)
);

COMMENT ON TABLE dml_utils_fixtures.test_composite_mixed IS
    'Fixture: a table with a two-column, mixed-kind primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_mixed.a IS
    'Fixture first primary-key column (bigint).';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_mixed.b IS
    'Fixture second primary-key column (text).';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_mixed.payload IS
    'Fixture payload column.';

-- A two-column primary key whose text column uses an ICU collation, so the key
-- order differs from byte order (for example '_' sorts before letters and 'a'
-- before 'B'); proves the chunk predicate uses the column's collation rather
-- than assuming byte order.
CREATE TABLE dml_utils_fixtures.test_composite_collated
(
    a       bigint,
    b       text COLLATE "en-x-icu",
    payload text,
    PRIMARY KEY (a, b)
);

COMMENT ON TABLE dml_utils_fixtures.test_composite_collated IS
    'Fixture: a two-column primary key whose text column uses an ICU collation.';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_collated.a IS
    'Fixture first primary-key column (bigint).';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_collated.b IS
    'Fixture second primary-key column (text, ICU-collated).';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_collated.payload IS
    'Fixture payload column.';

CREATE TABLE dml_utils_fixtures.test_numeric
(
    id      numeric PRIMARY KEY,
    payload text
);

COMMENT ON TABLE dml_utils_fixtures.test_numeric IS
    'Fixture: a table with a numeric primary key, which is not a supported key type.';
COMMENT ON COLUMN dml_utils_fixtures.test_numeric.id IS
    'Fixture primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_numeric.payload IS
    'Fixture payload column.';

-- A three-column primary key that mixes key kinds (integer, text, uuid), to
-- prove multi-column keys and heterogeneous kinds both work. The physical
-- column order (a, b, c) differs from the key order (b, a, c), so a routine
-- that reads column order instead of the index order would return the wrong
-- sequence; the key order is b (integer), a (text), c (uuid).
CREATE TABLE dml_utils_fixtures.test_composite_three
(
    a       text    NOT NULL,
    b       integer NOT NULL,
    c       uuid    NOT NULL,
    payload text,
    PRIMARY KEY (b, a, c)
);

COMMENT ON TABLE dml_utils_fixtures.test_composite_three IS
    'Fixture: a table with a three-column, mixed-kind primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_three.b IS
    'Fixture first primary-key column (integer).';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_three.a IS
    'Fixture second primary-key column (text).';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_three.c IS
    'Fixture third primary-key column (uuid).';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_three.payload IS
    'Fixture payload column.';

-- A two-column key whose second column is literally named id1, so the top-level
-- ORDER BY in populate_migration_boundaries would resolve the output alias id1
-- (the first key column) instead of the input column if the terminal scan were
-- built from raw column names rather than the idN aliases.
CREATE TABLE dml_utils_fixtures.test_composite_id1
(
    x       bigint,
    id1     bigint,
    payload text,
    PRIMARY KEY (x, id1)
);

COMMENT ON TABLE dml_utils_fixtures.test_composite_id1 IS
    'Fixture: a two-column key whose second column is named id1, to catch alias/column ORDER BY collisions.';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_id1.x IS
    'Fixture first primary-key column.';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_id1.id1 IS
    'Fixture second primary-key column, deliberately named id1.';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_id1.payload IS
    'Fixture payload column.';

-- Four primary-key columns, one more than the supported maximum.
CREATE TABLE dml_utils_fixtures.test_composite_four
(
    a       bigint,
    b       bigint,
    c       bigint,
    d       bigint,
    payload text,
    PRIMARY KEY (a, b, c, d)
);

COMMENT ON TABLE dml_utils_fixtures.test_composite_four IS
    'Fixture: a table with four primary-key columns, more than the supported maximum.';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_four.a IS
    'Fixture primary-key part.';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_four.b IS
    'Fixture primary-key part.';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_four.c IS
    'Fixture primary-key part.';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_four.d IS
    'Fixture primary-key part.';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_four.payload IS
    'Fixture payload column.';

-- A partitioned table, to prove block chunking rejects a relation that has no
-- single physical heap (ctid is per-partition, and the parent has no storage).
CREATE TABLE dml_utils_fixtures.test_partitioned
(
    id      bigint NOT NULL,
    payload text
) PARTITION BY RANGE (id);

COMMENT ON TABLE dml_utils_fixtures.test_partitioned IS
    'Fixture: a partitioned table, which block chunking must reject.';
COMMENT ON COLUMN dml_utils_fixtures.test_partitioned.id IS
    'Fixture partition key.';
COMMENT ON COLUMN dml_utils_fixtures.test_partitioned.payload IS
    'Fixture payload column.';

CREATE TABLE dml_utils_fixtures.test_partitioned_p1 PARTITION OF dml_utils_fixtures.test_partitioned
    FOR VALUES FROM (0) TO (1000);

COMMENT ON TABLE dml_utils_fixtures.test_partitioned_p1 IS
    'Fixture: the first partition of test_partitioned.';

-- A partitioned parent with an index, to prove ordered-key resolution rejects a
-- relation that has no single physical heap or ordered storage of its own.
CREATE UNIQUE INDEX test_partitioned_id_idx
    ON dml_utils_fixtures.test_partitioned (id);

COMMENT ON INDEX dml_utils_fixtures.test_partitioned_id_idx IS
    'Fixture index on the partitioned parent, which ordered-key resolution must reject.';

-- A table with the index shapes the ordered-key resolver accepts and rejects.
-- The valid keys are (created_at, id) and (external_id); the other indexes each
-- violate one contract rule so a test can pin the corresponding rejection.
CREATE TABLE dml_utils_fixtures.test_ordered_key
(
    id             bigint                  NOT NULL,
    created_at     timestamptz             NOT NULL,
    external_id    uuid                    NOT NULL,
    label          text                    NOT NULL,
    collated_label text COLLATE "en-x-icu" NOT NULL,
    numeric_key    numeric                 NOT NULL,
    nullable_key   bigint,
    payload        text,
    PRIMARY KEY (id)
);

COMMENT ON TABLE dml_utils_fixtures.test_ordered_key IS
    'Fixture: a table with ordered-key indexes in both valid and invalid shapes.';
COMMENT ON COLUMN dml_utils_fixtures.test_ordered_key.id IS
    'Fixture primary key and second part of the (created_at, id) ordered key.';
COMMENT ON COLUMN dml_utils_fixtures.test_ordered_key.created_at IS
    'Fixture timestamptz column, the leading part of the (created_at, id) ordered key.';
COMMENT ON COLUMN dml_utils_fixtures.test_ordered_key.external_id IS
    'Fixture uuid column with a single-column unique index.';
COMMENT ON COLUMN dml_utils_fixtures.test_ordered_key.label IS
    'Fixture text column used by the partial, expression, non-unique, hash and opclass indexes.';
COMMENT ON COLUMN dml_utils_fixtures.test_ordered_key.collated_label IS
    'Fixture ICU-collated text column, used by the collation-mismatch index.';
COMMENT ON COLUMN dml_utils_fixtures.test_ordered_key.numeric_key IS
    'Fixture numeric column, an unsupported ordered-key type.';
COMMENT ON COLUMN dml_utils_fixtures.test_ordered_key.nullable_key IS
    'Fixture nullable bigint column, which cannot be an ordered-key column.';
COMMENT ON COLUMN dml_utils_fixtures.test_ordered_key.payload IS
    'Fixture payload column.';

CREATE UNIQUE INDEX test_ordered_key_created_at_id_idx
    ON dml_utils_fixtures.test_ordered_key (created_at, id);

COMMENT ON INDEX dml_utils_fixtures.test_ordered_key_created_at_id_idx IS
    'Fixture: the valid two-column ordered key (timestamptz, bigint).';

CREATE UNIQUE INDEX test_ordered_key_external_id_idx
    ON dml_utils_fixtures.test_ordered_key (external_id);

COMMENT ON INDEX dml_utils_fixtures.test_ordered_key_external_id_idx IS
    'Fixture: the valid single-column uuid ordered key.';

-- INCLUDE columns are not part of the key order, so this resolves to the same
-- two columns as test_ordered_key_created_at_id_idx.
CREATE UNIQUE INDEX test_ordered_key_include_idx
    ON dml_utils_fixtures.test_ordered_key (created_at, id) INCLUDE (label);

COMMENT ON INDEX dml_utils_fixtures.test_ordered_key_include_idx IS
    'Fixture: a valid key with a non-key INCLUDE column that resolution must ignore.';

CREATE INDEX test_ordered_key_nonunique_idx
    ON dml_utils_fixtures.test_ordered_key (label);

COMMENT ON INDEX dml_utils_fixtures.test_ordered_key_nonunique_idx IS
    'Fixture: a non-unique index, which ordered-key resolution must reject.';

CREATE INDEX test_ordered_key_hash_idx
    ON dml_utils_fixtures.test_ordered_key USING hash (label);

COMMENT ON INDEX dml_utils_fixtures.test_ordered_key_hash_idx IS
    'Fixture: a hash index, which ordered-key resolution must reject.';

CREATE UNIQUE INDEX test_ordered_key_expression_idx
    ON dml_utils_fixtures.test_ordered_key (pg_catalog.lower(label));

COMMENT ON INDEX dml_utils_fixtures.test_ordered_key_expression_idx IS
    'Fixture: an expression index, which ordered-key resolution must reject.';

CREATE UNIQUE INDEX test_ordered_key_partial_idx
    ON dml_utils_fixtures.test_ordered_key (label) WHERE label <> '';

COMMENT ON INDEX dml_utils_fixtures.test_ordered_key_partial_idx IS
    'Fixture: a partial index, which ordered-key resolution must reject.';

CREATE UNIQUE INDEX test_ordered_key_desc_idx
    ON dml_utils_fixtures.test_ordered_key (created_at DESC, id);

COMMENT ON INDEX dml_utils_fixtures.test_ordered_key_desc_idx IS
    'Fixture: a DESC index, which ordered-key resolution must reject.';

CREATE UNIQUE INDEX test_ordered_key_nulls_first_idx
    ON dml_utils_fixtures.test_ordered_key (created_at NULLS FIRST, id);

COMMENT ON INDEX dml_utils_fixtures.test_ordered_key_nulls_first_idx IS
    'Fixture: a NULLS FIRST index, which ordered-key resolution must reject.';

CREATE UNIQUE INDEX test_ordered_key_nullable_idx
    ON dml_utils_fixtures.test_ordered_key (nullable_key);

COMMENT ON INDEX dml_utils_fixtures.test_ordered_key_nullable_idx IS
    'Fixture: a unique index on a nullable column, which ordered-key resolution must reject.';

CREATE UNIQUE INDEX test_ordered_key_four_idx
    ON dml_utils_fixtures.test_ordered_key (created_at, id, external_id, label);

COMMENT ON INDEX dml_utils_fixtures.test_ordered_key_four_idx IS
    'Fixture: a four-column index, more than the supported maximum.';

CREATE UNIQUE INDEX test_ordered_key_collation_idx
    ON dml_utils_fixtures.test_ordered_key (collated_label COLLATE "C");

COMMENT ON INDEX dml_utils_fixtures.test_ordered_key_collation_idx IS
    'Fixture: an index whose collation differs from its column, which resolution must reject.';

CREATE UNIQUE INDEX test_ordered_key_opclass_idx
    ON dml_utils_fixtures.test_ordered_key (label text_pattern_ops);

COMMENT ON INDEX dml_utils_fixtures.test_ordered_key_opclass_idx IS
    'Fixture: a non-default btree opclass, which ordered-key resolution must reject.';

CREATE UNIQUE INDEX test_ordered_key_numeric_idx
    ON dml_utils_fixtures.test_ordered_key (numeric_key);

COMMENT ON INDEX dml_utils_fixtures.test_ordered_key_numeric_idx IS
    'Fixture: a unique index on an unsupported key type, which resolution must reject.';

-- The case ordered_key exists for: a random UUID primary key with a useful
-- time/ID order supplied by a separate unique index.
CREATE TABLE dml_utils_fixtures.test_ordered_key_uuid
(
    id         uuid PRIMARY KEY,
    created_at timestamptz NOT NULL,
    payload    text
);

COMMENT ON TABLE dml_utils_fixtures.test_ordered_key_uuid IS
    'Fixture: a UUIDv4 primary key with a (created_at, id) ordered key.';
COMMENT ON COLUMN dml_utils_fixtures.test_ordered_key_uuid.id IS
    'Fixture random-ish UUID primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_ordered_key_uuid.created_at IS
    'Fixture timestamptz, the leading part of the ordered key.';
COMMENT ON COLUMN dml_utils_fixtures.test_ordered_key_uuid.payload IS
    'Fixture payload column.';

CREATE UNIQUE INDEX test_ordered_key_uuid_created_at_id_idx
    ON dml_utils_fixtures.test_ordered_key_uuid (created_at, id);

COMMENT ON INDEX dml_utils_fixtures.test_ordered_key_uuid_created_at_id_idx IS
    'Fixture: the valid (timestamptz, uuid) ordered key.';

-- A natural text key whose primary-key index is itself the ordered key.
CREATE TABLE dml_utils_fixtures.test_ordered_key_text
(
    natural_key text PRIMARY KEY,
    payload     text
);

COMMENT ON TABLE dml_utils_fixtures.test_ordered_key_text IS
    'Fixture: a table whose natural text primary key orders the chunks.';
COMMENT ON COLUMN dml_utils_fixtures.test_ordered_key_text.natural_key IS
    'Fixture natural text primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_ordered_key_text.payload IS
    'Fixture payload column.';

-- A single-column timestamptz ordered key with a separate surrogate primary key.
CREATE TABLE dml_utils_fixtures.test_ordered_key_timestamptz
(
    id         bigint PRIMARY KEY,
    created_at timestamptz NOT NULL,
    payload    text
);

COMMENT ON TABLE dml_utils_fixtures.test_ordered_key_timestamptz IS
    'Fixture: a separate unique timestamptz ordered key.';
COMMENT ON COLUMN dml_utils_fixtures.test_ordered_key_timestamptz.id IS
    'Fixture surrogate primary key, not the ordered key.';
COMMENT ON COLUMN dml_utils_fixtures.test_ordered_key_timestamptz.created_at IS
    'Fixture timestamptz, the unique ordered key.';
COMMENT ON COLUMN dml_utils_fixtures.test_ordered_key_timestamptz.payload IS
    'Fixture payload column.';

CREATE UNIQUE INDEX test_ordered_key_timestamptz_created_at_idx
    ON dml_utils_fixtures.test_ordered_key_timestamptz (created_at);

COMMENT ON INDEX dml_utils_fixtures.test_ordered_key_timestamptz_created_at_idx IS
    'Fixture: the valid single-column timestamptz ordered key.';
