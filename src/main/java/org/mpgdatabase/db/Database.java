package org.mpgdatabase.db;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

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
        addColumnIfMissing(connection, "GENOMIC_SEGMENTS", "EVENT_ID", "BIGINT");
        addColumnIfMissing(connection, "GENOMIC_SEGMENTS", "ANNOTATIONS", "VARCHAR(8192)");
        addColumnIfMissing(connection, "VALIDATION_ISSUES", "SOURCE_FILE_ID", "BIGINT");
        addColumnIfMissing(connection, "VALIDATION_ISSUES", "LINE_NUMBER", "INTEGER");
        addColumnIfMissing(connection, "VALIDATION_ISSUES", "SAMPLE_ACCESSION_ID", "VARCHAR(128)");
        backfillGenomicEvents(connection);
    }

    private static void addColumnIfMissing(Connection connection, String table, String column, String type)
            throws SQLException {
        try (var ps = connection.prepareStatement("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = ? AND COLUMN_NAME = ?
                """)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (var rs = ps.executeQuery()) {
                if (rs.next() && rs.getLong(1) > 0) {
                    return;
                }
            }
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
    }

    private static void backfillGenomicEvents(Connection connection) throws SQLException {
        try (var ps = connection.prepareStatement("""
                SELECT
                    gs.segment_id,
                    gs.sample_test_result_id,
                    str.source_file_id,
                    gs.event_type,
                    str.genome_build,
                    str.calling_method,
                    str.raw_iscn,
                    str.line_number,
                    gs.confidence,
                    gs.annotations
                FROM genomic_segments gs
                JOIN sample_test_results str ON str.sample_test_result_id = gs.sample_test_result_id
                WHERE gs.event_id IS NULL
                ORDER BY gs.segment_id
                """)) {
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    long eventId;
                    try (var insert = connection.prepareStatement("""
                            INSERT INTO genomic_events
                                (sample_test_result_id, source_file_id, event_group_id, event_type, genome_build, calling_method,
                                 raw_event_text, line_number, event_status, confidence, annotations)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """, Statement.RETURN_GENERATED_KEYS)) {
                        insert.setLong(1, rs.getLong("sample_test_result_id"));
                        long sourceFileId = rs.getLong("source_file_id");
                        if (rs.wasNull()) {
                            insert.setNull(2, java.sql.Types.BIGINT);
                        } else {
                            insert.setLong(2, sourceFileId);
                        }
                        insert.setString(3, null);
                        insert.setString(4, rs.getString("event_type"));
                        insert.setString(5, rs.getString("genome_build"));
                        insert.setString(6, rs.getString("calling_method"));
                        insert.setString(7, rs.getString("raw_iscn"));
                        int lineNumber = rs.getInt("line_number");
                        if (rs.wasNull()) {
                            insert.setNull(8, java.sql.Types.INTEGER);
                        } else {
                            insert.setInt(8, lineNumber);
                        }
                        insert.setString(9, "BACKFILLED");
                        insert.setString(10, rs.getString("confidence"));
                        insert.setString(11, rs.getString("annotations"));
                        insert.executeUpdate();
                        try (var keys = insert.getGeneratedKeys()) {
                            if (!keys.next()) {
                                throw new SQLException("No event_id returned while backfilling genomic_events");
                            }
                            eventId = keys.getLong(1);
                        }
                    }
                    try (var update = connection.prepareStatement("""
                            UPDATE genomic_segments
                            SET event_id = ?
                            WHERE segment_id = ?
                            """)) {
                        update.setLong(1, eventId);
                        update.setLong(2, rs.getLong("segment_id"));
                        update.executeUpdate();
                    }
                }
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
