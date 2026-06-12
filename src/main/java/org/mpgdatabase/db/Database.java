package org.mpgdatabase.db;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
                        || normalized.contains("idx_links_event_group_label")) {
                    continue;
                }
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute(trimmed);
                }
            }
        }
        addColumnIfMissing(connection, "SAMPLE_TEST_RESULTS", "ANNOTATION_NAMES", "VARCHAR(8192)");
        addColumnIfMissing(connection, "SAMPLE_TEST_RESULTS", "SOURCE_FILE_ID", "BIGINT");
        addColumnIfMissing(connection, "SAMPLE_TEST_RESULTS", "LINE_NUMBER", "INTEGER");
        addColumnIfMissing(connection, "SOURCE_FILES", "NOTES", "VARCHAR(1000)");
        addColumnIfMissing(connection, "GENOMIC_EVENTS", "EVENT_GROUP_ID", "VARCHAR(128)");
        addColumnIfMissing(connection, "GENOMIC_EVENT_GROUPS", "EVENT_GROUP_TYPE", "VARCHAR(64)");
        addColumnIfMissing(connection, "GENOMIC_SEGMENTS", "EVENT_GROUP_ID", "VARCHAR(128)");
        addColumnIfMissing(connection, "GENOMIC_SEGMENTS", "GENOME_BUILD", "VARCHAR(64)");
        addColumnIfMissing(connection, "GENOMIC_SEGMENTS", "CONFIDENCE", "VARCHAR(64)");
        addColumnIfMissing(connection, "GENOMIC_SEGMENTS", "ARRAY_SCORE", "DOUBLE PRECISION");
        addColumnIfMissing(connection, "GENOMIC_SEGMENTS", "NUMBER_OF_SITES", "INTEGER");
        addColumnIfMissing(connection, "GENOMIC_SEGMENTS", "RAW_ISCN", "VARCHAR(4096)");
        addColumnIfMissing(connection, "GENOMIC_SEGMENTS", "RAW_SEGMENT_TEXT", "VARCHAR(2000)");
        addColumnIfMissing(connection, "GENOMIC_SEGMENTS", "ANNOTATIONS", "VARCHAR(8192)");
        addColumnIfMissing(connection, "GENOMIC_LINKS", "GENOMIC_EVENT_GROUP_ID", "BIGINT");
        addColumnIfMissing(connection, "GENOMIC_LINKS", "EVENT_GROUP_ID", "VARCHAR(128)");
        addColumnIfMissing(connection, "VALIDATION_ISSUES", "SOURCE_FILE_ID", "BIGINT");
        addColumnIfMissing(connection, "VALIDATION_ISSUES", "LINE_NUMBER", "INTEGER");
        addColumnIfMissing(connection, "VALIDATION_ISSUES", "SAMPLE_ACCESSION_ID", "VARCHAR(128)");
        backfillDirectEventGroupIds(connection);
        backfillSegmentResultFields(connection);
        migrateGenomicSegmentLegacyColumns(connection);
        createDirectEventGroupIndexes(connection);
    }

    private static void migrateGenomicSegmentLegacyColumns(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE GENOMIC_SEGMENTS DROP COLUMN IF EXISTS EVENT_ID");
            stmt.execute("ALTER TABLE GENOMIC_SEGMENTS DROP COLUMN IF EXISTS GENOMIC_EVENT_GROUP_ID");
            stmt.execute("ALTER TABLE GENOMIC_SEGMENTS DROP COLUMN IF EXISTS CYTOBAND_START");
            stmt.execute("ALTER TABLE GENOMIC_SEGMENTS DROP COLUMN IF EXISTS CYTOBAND_END");
        }
    }

    private static void backfillSegmentResultFields(Connection connection) throws SQLException {
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
                      )
                    """);
            stmt.execute("""
                    UPDATE genomic_segments gs
                    SET raw_iscn = (
                        SELECT str.raw_iscn
                        FROM sample_test_results str
                        WHERE str.sample_test_result_id = gs.sample_test_result_id
                    )
                    WHERE (gs.raw_iscn IS NULL OR gs.raw_iscn = '')
                      AND EXISTS (
                          SELECT 1
                          FROM sample_test_results str
                          WHERE str.sample_test_result_id = gs.sample_test_result_id
                            AND str.raw_iscn IS NOT NULL
                            AND str.raw_iscn <> ''
                      )
                    """);
        }
    }

    private static void createDirectEventGroupIndexes(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
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
