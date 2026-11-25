package io.github.eyupmiduck.dmlutils;

import org.jooq.DSLContext;
import org.jooq.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared support for the tests that check the range predicate against the
 * boundaries: every chunk of a run is rendered from its stored boundaries
 * through {@code dml_utils_lib.render_chunk_sql}, executed, and the rows it
 * selects are compared with the table's rows.
 */
abstract class ChunkRenderingTestBase extends PartitionTestBase {

    /**
     * Renders every chunk of a run as {@code SELECT <rowExpr> FROM <driving_table>
     * WHERE <chunking_clause>} with the run's own boundaries, then executes each
     * and returns every selected value, in chunk order.
     */
    protected List<String> renderedChunkRows(long runId, Table<?> table, String keyColumns,
                                           String rowExpression, String[] kinds,
                                           DSLContext context) {
        List<String> rows = new ArrayList<>();
        for (String sql : renderedChunkSql(runId, table, keyColumns, rowExpression, kinds, context)) {
            rows.addAll(context.fetch(sql).getValues(0, String.class));
        }
        return rows;
    }

    /**
     * Renders every chunk of a run through {@code dml_utils_lib.render_chunk_sql}
     * from the stored boundaries, in boundary order.
     */
    protected List<String> renderedChunkSql(long runId, Table<?> table, String keyColumns,
                                          String rowExpression, String[] kinds, DSLContext context) {
        String template = "SELECT " + rowExpression + " FROM <driving_table> WHERE <chunking_clause>";
        String[] columns = keyColumns.split(",\\s*");
        return context.fetch(
                "SELECT dml_utils_lib.render_chunk_sql("
                        + " i_sql_text => ?, i_schema_name => ?, i_table_name => ?, i_table_alias => 't',"
                        + " i_key_columns => ?::name[], i_key_kinds => ?::text[],"
                        + " i_start_values => pair.o_start_values, i_end_values => pair.o_end_values,"
                        + " i_is_final => e.boundary_no = last.n) AS sql"
                        + " FROM dml_utils_data.migration_boundary AS s"
                        + " JOIN dml_utils_data.migration_boundary AS e"
                        + "   ON e.run_id = s.run_id AND e.boundary_no = s.boundary_no + 1"
                        + " CROSS JOIN (SELECT max(boundary_no) AS n FROM dml_utils_data.migration_boundary"
                        + "             WHERE run_id = ?) AS last"
                        + " CROSS JOIN LATERAL dml_utils_lib.migration_key_pair_values("
                        + "   i_start_key => s.boundary_id, i_end_key => e.boundary_id,"
                        + "   i_key_kinds => ?::text[]) AS pair"
                        + " WHERE s.run_id = ? ORDER BY s.boundary_no",
                template, schema(table), name(table), columns, kinds, runId, kinds, runId)
                .getValues("sql", String.class);
    }
}
