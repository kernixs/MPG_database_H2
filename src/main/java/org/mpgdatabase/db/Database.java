package org.mpgdatabase.db;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class Database {
    private Database() {
    }

    public static Connection connect(String jdbcUrl) throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    public static void initialize(Connection connection) throws SQLException {
        String schema = readSchema();
        for (String statement : schema.split(";")) {
            String trimmed = statement.trim();
            if (!trimmed.isEmpty()) {
                String normalized = trimmed.toLowerCase();
                if (normalized.contains("idx_segments_event_group_label")
                        || normalized.contains("idx_segments_genome_build")
                        || normalized.contains("idx_classifications_segment")
                        || normalized.contains("idx_signed_out_segment")
                        || normalized.contains("idx_links_event_group_label")) {
                    continue;
                }
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute(trimmed);
                }
            }
        }
        addColumnIfMissing(connection, "SAMPLE_TEST_RESULTS", "SOURCE_FILE_ID", "BIGINT");
        addColumnIfMissing(connection, "SOURCE_FILES", "NOTES", "VARCHAR(1000)");
        addColumnIfMissing(connection, "SOURCE_FILES", "NUM_ROWS_WITH_ERRORS", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(connection, "SOURCE_FILES", "FILE_CHECKSUM", "VARCHAR(128)");
        addColumnIfMissing(connection, "GENOMIC_EVENTS", "EVENT_GROUP_ID", "VARCHAR(128)");
        addColumnIfMissing(connection, "GENOMIC_EVENT_GROUPS", "EVENT_GROUP_TYPE", "VARCHAR(64)");
        addColumnIfMissing(connection, "GENOMIC_SEGMENTS", "EVENT_GROUP_ID", "VARCHAR(128)");
        addColumnIfMissing(connection, "GENOMIC_SEGMENTS", "GENOME_BUILD", "VARCHAR(64)");
        addColumnIfMissing(connection, "GENOMIC_SEGMENTS", "CONFIDENCE", "VARCHAR(64)");
        addColumnIfMissing(connection, "GENOMIC_SEGMENTS", "RAW_SEGMENT_TEXT", "VARCHAR(2000)");
        addColumnIfMissing(connection, "GENOMIC_SEGMENTS", "EVENT_SIZE_BP", "BIGINT");
        addColumnIfMissing(connection, "GENOMIC_SEGMENTS", "CYTOBAND_START", "VARCHAR(64)");
        addColumnIfMissing(connection, "GENOMIC_SEGMENTS", "CYTOBAND_END", "VARCHAR(64)");
        addColumnIfMissing(connection, "GENOMIC_SEGMENTS", "NUMBER_OF_SITES", "INTEGER");
        addColumnIfMissing(connection, "GENOMIC_SEGMENTS", "AMBIGUITY_FLAG", "BOOLEAN NOT NULL DEFAULT FALSE");
        addColumnIfMissing(connection, "GENOMIC_LINKS", "GENOMIC_EVENT_GROUP_ID", "BIGINT");
        addColumnIfMissing(connection, "GENOMIC_LINKS", "EVENT_GROUP_ID", "VARCHAR(128)");
        addColumnIfMissing(connection, "VALIDATION_ISSUES", "SOURCE_FILE_ID", "BIGINT");
        addColumnIfMissing(connection, "VALIDATION_ISSUES", "LINE_NUMBER", "INTEGER");
        addColumnIfMissing(connection, "VALIDATION_ISSUES", "SAMPLE_ACCESSION_ID", "VARCHAR(128)");
        migrateLegacySampleAccessions(connection);
        migrateLegacySampleTestResultColumns(connection);
        migrateLegacyClinicalDecisionTables(connection);
        backfillDirectEventGroupIds(connection);
        backfillIndividualMrns(connection);
        createDirectEventGroupIndexes(connection);
    }

    private static void migrateLegacySampleAccessions(Connection connection) throws SQLException {
        addColumnIfMissing(connection, "SAMPLE_ACCESSIONS", "SAMPLE_ID", "BIGINT");
        addColumnIfMissing(connection, "SAMPLE_ACCESSIONS", "ACCESSION_DNA_SOURCE", "VARCHAR(128)");

        boolean hasLegacyIndividualId = columnExists(connection, "SAMPLE_ACCESSIONS", "INDIVIDUAL_ID");
        boolean hasLegacyDnaSource = columnExists(connection, "SAMPLE_ACCESSIONS", "DNA_SOURCE");
        if (!hasLegacyIndividualId) {
            return;
        }

        String dnaSourceExpression = hasLegacyDnaSource ? "sa.dna_source" : "CAST(NULL AS VARCHAR(128))";
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    INSERT INTO samples (individual_id, sample_identifier, dna_source)
                    SELECT sa.individual_id, sa.accession_identifier, %s
                    FROM sample_accessions sa
                    WHERE sa.sample_id IS NULL
                      AND sa.individual_id IS NOT NULL
                      AND NOT EXISTS (
                          SELECT 1
                          FROM samples s
                          WHERE s.sample_identifier = sa.accession_identifier
                      )
                    """.formatted(dnaSourceExpression));
            stmt.execute("""
                    UPDATE sample_accessions sa
                    SET sample_id = (
                        SELECT s.sample_id
                        FROM samples s
                        WHERE s.sample_identifier = sa.accession_identifier
                    )
                    WHERE sa.sample_id IS NULL
                      AND EXISTS (
                          SELECT 1
                          FROM samples s
                          WHERE s.sample_identifier = sa.accession_identifier
                      )
                    """);
            if (hasLegacyDnaSource) {
                stmt.execute("""
                        UPDATE sample_accessions
                        SET accession_dna_source = dna_source
                        WHERE accession_dna_source IS NULL
                          AND dna_source IS NOT NULL
                        """);
            }
        }
    }

    private static void migrateLegacySampleTestResultColumns(Connection connection) throws SQLException {
        if (columnExists(connection, "SAMPLE_TEST_RESULTS", "GENOME_BUILD")) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("""
                        UPDATE genomic_segments gs
                        SET genome_build = (
                            SELECT str.genome_build
                            FROM sample_test_results str
                            WHERE str.sample_test_result_id = gs.sample_test_result_id
                        )
                        WHERE (gs.genome_build IS NULL OR gs.genome_build = '')
                          AND EXISTS (
                              SELECT 1
                              FROM sample_test_results str
                              WHERE str.sample_test_result_id = gs.sample_test_result_id
                                AND str.genome_build IS NOT NULL
                                AND str.genome_build <> ''
                          )
                        """);
            }
            dropColumnIfExists(connection, "SAMPLE_TEST_RESULTS", "GENOME_BUILD");
        }
        dropColumnIfExists(connection, "SAMPLE_TEST_RESULTS", "ANNOTATION_NAMES");
        dropColumnIfExists(connection, "SAMPLE_TEST_RESULTS", "LINE_NUMBER");
    }

    private static void migrateLegacyClinicalDecisionTables(Connection connection) throws SQLException {
        if (tableExists(connection, "VARIANT_CLASSIFICATIONS")) {
            addColumnIfMissing(connection, "VARIANT_CLASSIFICATIONS", "INTERPRETED_CALL_ID", "BIGINT");
            addColumnIfMissing(connection, "VARIANT_CLASSIFICATIONS", "CLASSIFICATION_SOURCE", "VARCHAR(256)");
        }
        if (tableExists(connection, "SIGNED_OUT_CALLS")) {
            addColumnIfMissing(connection, "SIGNED_OUT_CALLS", "INTERPRETED_CALL_ID", "BIGINT");
            addColumnIfMissing(connection, "SIGNED_OUT_CALLS", "REPORTABILITY_STATUS", "VARCHAR(128)");
        }
        if (tableExists(connection, "NOTES")) {
            addColumnIfMissing(connection, "NOTES", "CREATED_BY", "VARCHAR(128)");
            if (columnExists(connection, "NOTES", "AUTHOR")) {
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("""
                            UPDATE notes
                            SET created_by = author
                            WHERE created_by IS NULL
                              AND author IS NOT NULL
                            """);
                }
            }
        }
        backfillInterpretedCallsFromSegments(connection);
        relaxLegacyClinicalSegmentColumns(connection);
    }

    private static void backfillInterpretedCallsFromSegments(Connection connection) throws SQLException {
        if (!columnExists(connection, "VARIANT_CLASSIFICATIONS", "SEGMENT_ID")
                && !columnExists(connection, "SIGNED_OUT_CALLS", "SEGMENT_ID")) {
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            if (columnExists(connection, "VARIANT_CLASSIFICATIONS", "SEGMENT_ID")) {
                stmt.execute("""
                        INSERT INTO interpreted_calls (finding_type, finding_id, sample_test_result_id, individual_id)
                        SELECT DISTINCT
                            'genomic_segments',
                            vc.segment_id,
                            gs.sample_test_result_id,
                            s.individual_id
                        FROM variant_classifications vc
                        JOIN genomic_segments gs ON gs.segment_id = vc.segment_id
                        JOIN sample_test_results str ON str.sample_test_result_id = gs.sample_test_result_id
                        JOIN sample_tests st ON st.sample_test_id = str.sample_test_id
                        JOIN sample_accessions sa ON sa.sample_accession_id = st.sample_accession_id
                        JOIN samples s ON s.sample_id = sa.sample_id
                        WHERE vc.segment_id IS NOT NULL
                          AND vc.interpreted_call_id IS NULL
                          AND NOT EXISTS (
                              SELECT 1
                              FROM interpreted_calls ic
                              WHERE ic.finding_type = 'genomic_segments'
                                AND ic.finding_id = vc.segment_id
                                AND ic.sample_test_result_id = gs.sample_test_result_id
                                AND ic.individual_id = s.individual_id
                          )
                        """);
                stmt.execute("""
                        UPDATE variant_classifications vc
                        SET interpreted_call_id = (
                            SELECT MIN(ic.interpreted_call_id)
                            FROM interpreted_calls ic
                            JOIN genomic_segments gs ON gs.segment_id = vc.segment_id
                            JOIN sample_test_results str ON str.sample_test_result_id = gs.sample_test_result_id
                            JOIN sample_tests st ON st.sample_test_id = str.sample_test_id
                            JOIN sample_accessions sa ON sa.sample_accession_id = st.sample_accession_id
                            JOIN samples s ON s.sample_id = sa.sample_id
                            WHERE ic.finding_type = 'genomic_segments'
                              AND ic.finding_id = vc.segment_id
                              AND ic.sample_test_result_id = gs.sample_test_result_id
                              AND ic.individual_id = s.individual_id
                        )
                        WHERE vc.interpreted_call_id IS NULL
                          AND vc.segment_id IS NOT NULL
                        """);
            }
            if (columnExists(connection, "SIGNED_OUT_CALLS", "SEGMENT_ID")) {
                stmt.execute("""
                        INSERT INTO interpreted_calls (finding_type, finding_id, sample_test_result_id, individual_id)
                        SELECT DISTINCT
                            'genomic_segments',
                            soc.segment_id,
                            soc.sample_test_result_id,
                            soc.individual_id
                        FROM signed_out_calls soc
                        WHERE soc.segment_id IS NOT NULL
                          AND soc.interpreted_call_id IS NULL
                          AND NOT EXISTS (
                              SELECT 1
                              FROM interpreted_calls ic
                              WHERE ic.finding_type = 'genomic_segments'
                                AND ic.finding_id = soc.segment_id
                                AND ic.sample_test_result_id = soc.sample_test_result_id
                                AND ic.individual_id = soc.individual_id
                          )
                        """);
                stmt.execute("""
                        UPDATE signed_out_calls soc
                        SET interpreted_call_id = (
                            SELECT MIN(ic.interpreted_call_id)
                            FROM interpreted_calls ic
                            WHERE ic.finding_type = 'genomic_segments'
                              AND ic.finding_id = soc.segment_id
                              AND ic.sample_test_result_id = soc.sample_test_result_id
                              AND ic.individual_id = soc.individual_id
                        )
                        WHERE soc.interpreted_call_id IS NULL
                          AND soc.segment_id IS NOT NULL
                        """);
            }
        }
    }

    private static void relaxLegacyClinicalSegmentColumns(Connection connection) throws SQLException {
        dropNotNullIfColumnExists(connection, "VARIANT_CLASSIFICATIONS", "SEGMENT_ID");
        dropNotNullIfColumnExists(connection, "SIGNED_OUT_CALLS", "SEGMENT_ID");
    }

    private static void backfillIndividualMrns(Connection connection) throws SQLException {
        try (PreparedStatement read = connection.prepareStatement("""
                SELECT i.individual_id, sa.accession_identifier, i.external_identifier
                FROM individuals i
                LEFT JOIN samples s ON s.individual_id = i.individual_id
                LEFT JOIN sample_accessions sa ON sa.sample_id = s.sample_id
                WHERE i.mrn IS NULL
                ORDER BY i.individual_id, sa.sample_accession_id
                """);
             ResultSet rs = read.executeQuery()) {
            Map<Long, String> mrns = new LinkedHashMap<>();
            Map<String, Long> usedMrns = existingMrns(connection);
            while (rs.next()) {
                long individualId = rs.getLong("individual_id");
                if (mrns.containsKey(individualId)) {
                    continue;
                }
                String accession = rs.getString("accession_identifier");
                String externalIdentifier = rs.getString("external_identifier");
                String source = accession != null && !accession.isBlank()
                        ? accession
                        : externalIdentifier;
                if (source != null && !source.isBlank()) {
                    String baseMrn = "MRN-" + source.replaceFirst("^IND-", "");
                    String uniqueMrn = uniquePlaceholderMrn(baseMrn, individualId, usedMrns);
                    usedMrns.put(uniqueMrn, individualId);
                    mrns.put(individualId, uniqueMrn);
                }
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE individuals
                    SET mrn = ?
                    WHERE individual_id = ?
                      AND mrn IS NULL
                    """)) {
                for (Map.Entry<Long, String> entry : mrns.entrySet()) {
                    update.setString(1, entry.getValue());
                    update.setLong(2, entry.getKey());
                    update.addBatch();
                }
                update.executeBatch();
            }
        }
    }

    private static Map<String, Long> existingMrns(Connection connection) throws SQLException {
        Map<String, Long> mrns = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT individual_id, mrn
                FROM individuals
                WHERE mrn IS NOT NULL
                """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                mrns.put(rs.getString("mrn"), rs.getLong("individual_id"));
            }
        }
        return mrns;
    }

    private static String uniquePlaceholderMrn(String baseMrn, long individualId, Map<String, Long> usedMrns) {
        Long owner = usedMrns.get(baseMrn);
        if (owner == null || owner == individualId) {
            return baseMrn;
        }
        return baseMrn + "-" + individualId;
    }

    private static void createDirectEventGroupIndexes(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_classifications_segment
                        ON variant_classifications(interpreted_call_id, is_current)
                    """);
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_signed_out_segment
                        ON signed_out_calls(interpreted_call_id, individual_id)
                    """);
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_segments_genome_build
                        ON genomic_segments(genome_build)
                    """);
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_segments_event_group_label
                        ON genomic_segments(event_group_id)
                    """);
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_links_event_group_label
                        ON genomic_links(event_group_id)
                    """);
        }
    }

    private static void backfillDirectEventGroupIds(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            if (columnExists(connection, "GENOMIC_SEGMENTS", "EVENT_ID")) {
                stmt.execute("""
                        UPDATE genomic_segments gs
                        SET event_group_id = (
                            SELECT ge.event_group_id
                            FROM genomic_events ge
                            WHERE ge.event_id = gs.event_id
                        )
                        WHERE (gs.event_group_id IS NULL OR gs.event_group_id = '')
                          AND gs.event_id IS NOT NULL
                          AND EXISTS (
                              SELECT 1
                              FROM genomic_events ge
                              WHERE ge.event_id = gs.event_id
                                AND ge.event_group_id IS NOT NULL
                                AND ge.event_group_id <> ''
                          )
                        """);
            }
            if (columnExists(connection, "GENOMIC_SEGMENTS", "GENOMIC_EVENT_GROUP_ID")) {
                stmt.execute("""
                        UPDATE genomic_segments gs
                        SET event_group_id = (
                            SELECT geg.event_group_label
                            FROM genomic_event_groups geg
                            WHERE geg.genomic_event_group_id = gs.genomic_event_group_id
                        )
                        WHERE (gs.event_group_id IS NULL OR gs.event_group_id = '')
                          AND gs.genomic_event_group_id IS NOT NULL
                          AND EXISTS (
                              SELECT 1
                              FROM genomic_event_groups geg
                              WHERE geg.genomic_event_group_id = gs.genomic_event_group_id
                                AND geg.event_group_label IS NOT NULL
                                AND geg.event_group_label <> ''
                          )
                        """);
            }
            stmt.execute("""
                    UPDATE genomic_links gl
                    SET event_group_id = (
                        SELECT geg.event_group_label
                        FROM genomic_event_groups geg
                        WHERE geg.genomic_event_group_id = gl.genomic_event_group_id
                    )
                    WHERE (gl.event_group_id IS NULL OR gl.event_group_id = '')
                      AND gl.genomic_event_group_id IS NOT NULL
                      AND EXISTS (
                          SELECT 1
                          FROM genomic_event_groups geg
                          WHERE geg.genomic_event_group_id = gl.genomic_event_group_id
                            AND geg.event_group_label IS NOT NULL
                            AND geg.event_group_label <> ''
                      )
                    """);
            stmt.execute("""
                    UPDATE genomic_links gl
                    SET event_group_id = (
                        SELECT gs.event_group_id
                        FROM genomic_segments gs
                        WHERE gs.segment_id = gl.source_segment_id
                    )
                    WHERE (gl.event_group_id IS NULL OR gl.event_group_id = '')
                      AND EXISTS (
                          SELECT 1
                          FROM genomic_segments gs
                          WHERE gs.segment_id = gl.source_segment_id
                            AND gs.event_group_id IS NOT NULL
                            AND gs.event_group_id <> ''
                      )
                    """);
            stmt.execute("""
                    UPDATE genomic_segments
                    SET event_group_id = NULL
                    WHERE event_group_id LIKE 'AUTO-BACKFILL-%'
                    """);
            stmt.execute("""
                    UPDATE genomic_links
                    SET event_group_id = NULL
                    WHERE event_group_id LIKE 'AUTO-BACKFILL-%'
                    """);
        }
    }

    private static void addColumnIfMissing(Connection connection, String table, String column, String type)
            throws SQLException {
        if (columnExists(connection, table, column)) {
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
    }

    private static void dropColumnIfExists(Connection connection, String table, String column) throws SQLException {
        if (!columnExists(connection, table, column)) {
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE " + table + " DROP COLUMN " + column);
        }
    }

    private static void dropNotNullIfColumnExists(Connection connection, String table, String column) throws SQLException {
        if (!columnExists(connection, table, column)) {
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE " + table + " ALTER COLUMN " + column + " DROP NOT NULL");
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (var ps = connection.prepareStatement("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = ?
                """)) {
            ps.setString(1, table.toUpperCase(Locale.ROOT));
            try (var rs = ps.executeQuery()) {
                return rs.next() && rs.getLong(1) > 0;
            }
        }
    }

    private static boolean columnExists(Connection connection, String table, String column) throws SQLException {
        try (var ps = connection.prepareStatement("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = ? AND COLUMN_NAME = ?
                """)) {
            ps.setString(1, table.toUpperCase(Locale.ROOT));
            ps.setString(2, column.toUpperCase(Locale.ROOT));
            try (var rs = ps.executeQuery()) {
                return rs.next() && rs.getLong(1) > 0;
            }
        }
    }

    private static String readSchema() {
        try (var input = Database.class.getResourceAsStream("/schema.sql")) {
            if (input == null) {
                throw new IllegalStateException("schema.sql was not found on the classpath");
            }
            return new String(input.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
