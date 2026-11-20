CREATE DOMAIN dml_utils_data.positive_integer AS integer
    CONSTRAINT positive_integer_check CHECK (value IS NOT NULL AND value > 0);

COMMENT ON DOMAIN dml_utils_data.positive_integer IS
    'integer that is NOT NULL and greater than 0.';

CREATE DOMAIN dml_utils_data.non_negative_integer AS integer
    CONSTRAINT non_negative_integer_check CHECK (value IS NOT NULL AND value >= 0);

COMMENT ON DOMAIN dml_utils_data.non_negative_integer IS
    'integer that is NOT NULL and greater than or equal to 0; used for the '
        'per-chunk sleep so the no-delay value 0 is valid.';

-- Trim ASCII whitespace, including vertical tab via the octal escape \013:
-- PostgreSQL has no \v escape in E-strings (it reads \v as the letter v). The
-- set is deliberately the ASCII whitespace characters only: non-ASCII whitespace
-- (for example NBSP U+00A0) is treated as ordinary text, so a value of only such
-- characters is accepted. Extend the set only if the application treats that
-- character as blank.
CREATE DOMAIN dml_utils_data.non_null_text AS text
    CONSTRAINT non_null_text_check CHECK (
        value IS NOT NULL AND pg_catalog.btrim(value, E' \t\n\r\f\013') <> ''
        );

COMMENT ON DOMAIN dml_utils_data.non_null_text IS
    'text that is NOT NULL and not blank. Blank means empty after trimming the '
        'ASCII whitespace characters (space, tab, LF, CR, FF, vertical tab); '
        'non-ASCII whitespace such as NBSP is not trimmed.';

CREATE DOMAIN dml_utils_data.non_null_bigint AS bigint
    CONSTRAINT non_null_bigint_check CHECK (value IS NOT NULL);

COMMENT ON DOMAIN dml_utils_data.non_null_bigint IS
    'bigint that is NOT NULL, for identifiers and counters whose zero/negative '
        'values are meaningful but whose NULL is not.';

CREATE DOMAIN dml_utils_data.non_negative_bigint AS bigint
    CONSTRAINT non_negative_bigint_check CHECK (value IS NOT NULL AND value >= 0);

COMMENT ON DOMAIN dml_utils_data.non_negative_bigint IS
    'bigint that is NOT NULL and greater than or equal to 0; used for boundary '
        'numbers, row counts and block ranges.';

CREATE DOMAIN dml_utils_data.positive_bigint AS bigint
    CONSTRAINT positive_bigint_check CHECK (value IS NOT NULL AND value > 0);

COMMENT ON DOMAIN dml_utils_data.positive_bigint IS
    'bigint that is NOT NULL and greater than 0; used where a chunk size stays '
        'bigint-sized rather than the integer-bounded positive_integer.';

CREATE DOMAIN dml_utils_data.non_null_timestamptz AS timestamptz
    CONSTRAINT non_null_timestamptz_check CHECK (value IS NOT NULL);

COMMENT ON DOMAIN dml_utils_data.non_null_timestamptz IS
    'timestamptz that is NOT NULL, for a timestamp an argument must carry rather '
        'than an optional one.';

CREATE DOMAIN dml_utils_data.non_null_oid AS oid
    CONSTRAINT non_null_oid_check CHECK (value IS NOT NULL);

COMMENT ON DOMAIN dml_utils_data.non_null_oid IS
    'oid that is NOT NULL, for an object identifier argument.';

-- A boolean whose explicit NULL is rejected. Function signatures may still give
-- it a DEFAULT, so omitting the argument is fine; passing NULL is not, and it
-- cannot silently fall through SQL three-valued logic.
CREATE DOMAIN dml_utils_data.non_null_boolean AS boolean
    CONSTRAINT non_null_boolean_check CHECK (value IS NOT NULL);

COMMENT ON DOMAIN dml_utils_data.non_null_boolean IS
    'boolean that is NOT NULL, so an explicitly passed NULL is rejected instead '
        'of falling through three-valued logic.';

-- The pg_background handle helpers (cancel/wait/detach) are STRICT, so a NULL
-- pid or cookie is a silent no-op. array_position is undefined for
-- multidimensional arrays (it raises rather than returning a result), so require
-- one dimension first; AND short-circuits, so array_position is never evaluated
-- for a multidimension. A NULL array (the no-handles sentinel) and an empty
-- array are allowed: their shape checks evaluate to NULL, which a CHECK passes.
CREATE DOMAIN dml_utils_data.non_null_element_integer_array AS integer[]
    CONSTRAINT non_null_element_integer_array_check CHECK (
        value IS NULL
            OR (pg_catalog.array_ndims(value) = 1
            AND pg_catalog.array_lower(value, 1) = 1
            AND pg_catalog.array_position(value, NULL) IS NULL)
        );

COMMENT ON DOMAIN dml_utils_data.non_null_element_integer_array IS
    'one-dimensional integer array with a lower bound of 1 and no NULL '
        'elements, for a set of pg_background worker pids; a NULL or empty array '
        'is allowed as the no-handles sentinel.';

CREATE DOMAIN dml_utils_data.non_null_element_bigint_array AS bigint[]
    CONSTRAINT non_null_element_bigint_array_check CHECK (
        value IS NULL
            OR (pg_catalog.array_ndims(value) = 1
            AND pg_catalog.array_lower(value, 1) = 1
            AND pg_catalog.array_position(value, NULL) IS NULL)
        );

COMMENT ON DOMAIN dml_utils_data.non_null_element_bigint_array IS
    'one-dimensional bigint array with a lower bound of 1 and no NULL '
        'elements, for a set of pg_background worker cookies; a NULL or empty '
        'array is allowed as the no-handles sentinel.';

-- Packs the primary-key value of a chunk boundary, for a primary key of one to
-- three columns. One array per supported kind, each position-aligned to the
-- primary-key columns: at index i, the array matching column i's kind holds its
-- value and the other arrays hold NULL. An array for a kind that no key column
-- uses is NULL. So every array that is present has the same length as the
-- primary key has columns, and exactly one array element is non-NULL at each
-- index. The "at least one populated array" rule is enforced by the
-- migration_boundary check constraint; the type is not wrapped in a domain
-- because a domain over a UDT makes jOOQ generate a static-init cycle for the
-- schema class.
-- SQLFluff parses a composite type's attributes as bare words, so the
-- layout:type:data_type alignment relaxation (see .sqlfluff) does not apply
-- here and IntelliJ's aligned columns would trip LT01.
-- noqa:disable=LT01
CREATE TYPE dml_utils_data.migration_key AS
(
    bigint_values      bigint[],
    text_values        text[],
    uuid_values        uuid[],
    timestamptz_values timestamptz[]
);
-- noqa:enable=LT01

COMMENT ON TYPE dml_utils_data.migration_key IS
    'Packed boundary-key value for a chunk boundary: one position-aligned array '
        'per supported kind (bigint, text, uuid or timestamptz), so exactly one '
        'array element is non-NULL per index and an unused kind''s array is NULL. '
        'Index i holds the value of boundary-key column i when that column has '
        'the array''s kind.';

-- The chunking strategy a migration run uses. primary_key computes fixed-row
-- chunks from the primary-key order (the default); ordered_key computes
-- fixed-row chunks from a caller-selected unique index; blocks computes chunks
-- from physical heap block ranges (ctid). See the block-chunking limitations in
-- changes/functions/README.md before using blocks.
CREATE TYPE dml_utils_data.chunking_strategy AS ENUM ('primary_key', 'ordered_key', 'blocks');

COMMENT ON TYPE dml_utils_data.chunking_strategy IS
    'Chunking strategy for a migration run: primary_key (fixed rows in '
        'primary-key order), ordered_key (fixed rows in a caller-selected '
        'unique index order) or blocks (physical heap block ranges).';
