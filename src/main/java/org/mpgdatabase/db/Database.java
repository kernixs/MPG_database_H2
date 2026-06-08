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
        addColumnIfMissing(connection, "GENOMIC_SEGMENTS", "ANNOTATIONS", "VARCHAR(8192)");
        addColumnIfMissing(connection, "VALIDATION_ISSUES", "SOURCE_FILE_ID", "BIGINT");
        addColumnIfMissing(connection, "VALIDATION_ISSUES", "LINE_NUMBER", "INTEGER");
        addColumnIfMissing(connection, "VALIDATION_ISSUES", "SAMPLE_ACCESSION_ID", "VARCHAR(128)");
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
