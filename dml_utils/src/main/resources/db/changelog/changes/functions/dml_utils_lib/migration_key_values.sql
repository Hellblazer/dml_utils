CREATE OR REPLACE FUNCTION dml_utils_lib.migration_key_values(
    i_key dml_utils_data.migration_key,
    i_key_kinds dml_utils_data.non_null_element_text_array
)
    RETURNS text[]
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
    SET search_path = pg_catalog
AS
$$
DECLARE
    l_values text[] := ARRAY []::text[];
    l_kind   text;
    l_value  text;
BEGIN
    -- Flatten a position-aligned migration_key back into one text value per
    -- boundary-key column, in key order, using i_key_kinds to select the array
    -- for each position. The values are the text form the chunk predicate
    -- re-casts. Reject a NULL, empty, over-long, non-1-dimensional or non-1-based
    -- kinds array: cardinality alone does not catch the last two, and indexing a
    -- non-1-based or multidimensional array would read a slice or an out-of-range
    -- subscript. The migration_key type has one array per supported kind.
    IF i_key_kinds IS NULL
        OR pg_catalog.array_ndims(i_key_kinds) IS DISTINCT FROM 1
        OR pg_catalog.array_lower(i_key_kinds, 1) IS DISTINCT FROM 1
        OR pg_catalog.cardinality(i_key_kinds) NOT BETWEEN 1 AND 3
    THEN
        RAISE EXCEPTION 'key kinds must be a one-dimensional, 1-based array of one to three entries'
            USING ERRCODE = '22023';
    END IF;

    -- The value arrays are indexed with l_position exactly like i_key_kinds, so
    -- a present array must have the same one-dimensional, 1-based shape and the
    -- same arity. A short or reshaped array would otherwise yield a NULL (and
    -- shift the rendered predicate) instead of failing.
    IF ((i_key).bigint_values IS NOT NULL
        AND (pg_catalog.array_ndims((i_key).bigint_values) IS DISTINCT FROM 1
            OR pg_catalog.array_lower((i_key).bigint_values, 1) IS DISTINCT FROM 1
            OR pg_catalog.cardinality((i_key).bigint_values)
                 <> pg_catalog.cardinality(i_key_kinds)))
        OR ((i_key).text_values IS NOT NULL
            AND (pg_catalog.array_ndims((i_key).text_values) IS DISTINCT FROM 1
                OR pg_catalog.array_lower((i_key).text_values, 1) IS DISTINCT FROM 1
                OR pg_catalog.cardinality((i_key).text_values)
                     <> pg_catalog.cardinality(i_key_kinds)))
        OR ((i_key).uuid_values IS NOT NULL
            AND (pg_catalog.array_ndims((i_key).uuid_values) IS DISTINCT FROM 1
                OR pg_catalog.array_lower((i_key).uuid_values, 1) IS DISTINCT FROM 1
                OR pg_catalog.cardinality((i_key).uuid_values)
                     <> pg_catalog.cardinality(i_key_kinds)))
        OR ((i_key).timestamptz_values IS NOT NULL
            AND (pg_catalog.array_ndims((i_key).timestamptz_values) IS DISTINCT FROM 1
                OR pg_catalog.array_lower((i_key).timestamptz_values, 1) IS DISTINCT FROM 1
                OR pg_catalog.cardinality((i_key).timestamptz_values)
                     <> pg_catalog.cardinality(i_key_kinds)))
    THEN
        RAISE EXCEPTION 'migration key arrays must be one-dimensional, 1-based and match the key kinds'
            USING ERRCODE = '22023';
    END IF;

    FOR l_position IN 1..pg_catalog.cardinality(i_key_kinds)
        LOOP
            l_kind := i_key_kinds[l_position];

            -- The argument domain rejects a NULL element.
            IF l_kind NOT IN ('bigint', 'text', 'uuid', 'timestamptz') THEN
                RAISE EXCEPTION 'unsupported key kind % at position %', l_kind, l_position
                    USING ERRCODE = '22023';
            END IF;

            -- Exactly one value array is populated per position; an absent array
            -- or a NULL element at this position yields a NULL here, which keeps
            -- the result position-aligned with the key instead of shifting later
            -- values. render_chunk_sql rejects the NULL when it renders the
            -- predicate, so the hole is caught before any chunk runs.
            l_value := CASE l_kind
                           WHEN 'bigint' THEN (i_key).bigint_values[l_position]::text
                           WHEN 'text' THEN (i_key).text_values[l_position]
                           WHEN 'uuid' THEN (i_key).uuid_values[l_position]::text
                           WHEN 'timestamptz' THEN (i_key).timestamptz_values[l_position]::text
                END;

            l_values := l_values || l_value;
        END LOOP;

    RETURN l_values;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.migration_key_values IS
    'Flattens a position-aligned migration_key into one text value per '
        'primary-key column, in key order, using the key kinds from '
        'primary_key_kinds. Raises 22023 for a malformed kinds or value array.';
