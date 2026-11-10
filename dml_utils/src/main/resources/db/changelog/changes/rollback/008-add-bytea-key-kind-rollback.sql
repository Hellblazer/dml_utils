ALTER TYPE dml_utils_data.migration_key DROP ATTRIBUTE IF EXISTS bytea_values;

COMMENT ON TYPE dml_utils_data.migration_key IS
    'Packed boundary-key value for a chunk boundary: one position-aligned array '
        'per supported kind (bigint, text, uuid or timestamptz), so exactly one '
        'array element is non-NULL per index and an unused kind''s array is NULL. '
        'Index i holds the value of boundary-key column i when that column has '
        'the array''s kind.';
