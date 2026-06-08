package org.mpgdatabase.report;

import org.mpgdatabase.dao.GenomicSegmentDao;
import org.mpgdatabase.importer.ImportResult;
import org.mpgdatabase.model.Models.GenomicSegment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class VerificationService {
    private static final List<String> REQUIRED_TABLES = List.of(
            "individuals",
            "sample_accessions",
            "lab_protocols",
            "sample_tests",
            "pipelines",
            "source_files",
            "sample_test_results",
            "karyotypes",
            "genomic_segments",
            "validation_issues"
    );

    private final Connection connection;
    private final Path outputDir;

    public VerificationService(Connection connection, Path outputDir) {
        this.connection = connection;
        this.outputDir = outputDir;
    }

    public VerificationReport verify(boolean databaseInitializationPassed, List<ImportResult> importResults)
            throws IOException, SQLException {
        Files.createDirectories(outputDir);
        VerificationResult schema = verifySchema();
        VerificationResult relationship = verifyRows("Relationship Verification", """
                SELECT COUNT(*) FROM individuals i
                JOIN sample_accessions sa ON sa.individual_id = i.individual_id
                JOIN sample_tests st ON st.sample_accession_id = sa.sample_accession_id
                JOIN sample_test_results str ON str.sample_test_id = st.sample_test_id
                JOIN genomic_segments gs ON gs.sample_test_result_id = str.sample_test_result_id
                """);
        VerificationResult iscn = verifyRows("ISCN Verification", """
                SELECT COUNT(*) FROM sample_test_results str
                JOIN karyotypes k ON k.sample_test_result_id = str.sample_test_result_id
                JOIN genomic_segments gs ON gs.karyotype_id = k.karyotype_id
                WHERE str.raw_iscn IS NOT NULL AND str.raw_iscn <> ''
                """);
        VerificationResult array = verifyRows("Array Verification", """
                SELECT COUNT(*) FROM sample_test_results str
                JOIN genomic_segments gs ON gs.sample_test_result_id = str.sample_test_result_id
                WHERE gs.karyotype_id IS NULL
                """);
        VerificationResult query = verifyQueries();
        VerificationResult integrity = verifyDataIntegrity();
        Map<String, Long> tableCounts = tableCounts();

        writeReports(tableCounts, importResults, query);
        return new VerificationReport(
                databaseInitializationPassed,
                tableCounts,
                importResults,
                schema,
                relationship,
                iscn,
                array,
                query,
                integrity);
    }

    public String terminalSummary(VerificationReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("Database Initialization: ").append(passFail(report.databaseInitializationPassed())).append("\n\n");
        sb.append("Table Counts\n------------\n");
        appendCount(sb, report.tableCounts(), "Individuals", "individuals");
        appendCount(sb, report.tableCounts(), "Sample Accessions", "sample_accessions");
        appendCount(sb, report.tableCounts(), "Sample Tests", "sample_tests");
        appendCount(sb, report.tableCounts(), "Source Files", "source_files");
        appendCount(sb, report.tableCounts(), "Sample Test Results", "sample_test_results");
        appendCount(sb, report.tableCounts(), "Karyotypes", "karyotypes");
        appendCount(sb, report.tableCounts(), "Genomic Segments", "genomic_segments");
        appendCount(sb, report.tableCounts(), "Validation Issues", "validation_issues");
        sb.append("\nImport Status\n-------------\n");
        for (ImportResult result : report.importResults()) {
            sb.append(result.fileName()).append(" imported: ").append(passFail(result.success())).append("\n");
        }
        sb.append("\nVerification Status\n-------------------\n");
        sb.append("Schema Verification: ").append(passFail(report.schema().passed())).append("\n");
        sb.append("Relationship Verification: ").append(passFail(report.relationship().passed())).append("\n");
        sb.append("ISCN Verification: ").append(passFail(report.iscn().passed())).append("\n");
        sb.append("Array Verification: ").append(passFail(report.array().passed())).append("\n");
        sb.append("Query Verification: ").append(passFail(report.query().passed())).append("\n");
        sb.append("Data Integrity Verification: ").append(passFail(report.dataIntegrity().passed())).append("\n");
        appendFailureMessages(sb, report);
        sb.append("\nOverall Result: ").append(passFail(report.overallPassed())).append("\n");
        return sb.toString();
    }

    private VerificationResult verifySchema() throws SQLException {
        VerificationResult result = new VerificationResult("Schema Verification");
        for (String table : REQUIRED_TABLES) {
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
                    WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = ?
                    """)) {
                ps.setString(1, table.toUpperCase());
                if (scalarLong(ps) == 0) {
                    result.fail("Missing required table: " + table);
                }
            }
        }
        if (result.passed()) {
            result.pass("All required tables exist");
        }
        return result;
    }

    private VerificationResult verifyRows(String name, String sql) throws SQLException {
        VerificationResult result = new VerificationResult(name);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            long count = scalarLong(ps);
            if (count == 0) {
                result.fail(name + " found no matching records");
            } else {
                result.pass(name + " found " + count + " matching records");
            }
        } catch (SQLException e) {
            result.fail(name + " failed: " + e.getMessage());
        }
        return result;
    }

    private VerificationResult verifyQueries() throws SQLException, IOException {
        VerificationResult result = new VerificationResult("Query Verification");
        GenomicSegmentDao dao = new GenomicSegmentDao(connection);
        StringBuilder sb = new StringBuilder();
        appendSegments(sb, "Segments for sample SIM001", dao.findBySampleAccession("SIM001"));
        appendSegments(sb, "Segments overlapping chr5:70000000-150000000", dao.findOverlapping("chr5", 70_000_000, 150_000_000));
        sb.append("\nValidation Issues\n");
        sb.append(queryText("SELECT validation_issue_id, issue_type, issue_message, severity FROM validation_issues ORDER BY validation_issue_id"));
        appendSegments(sb, "ISCN-derived segments", dao.findIscnDerived());
        Files.writeString(outputDir.resolve("query_results.txt"), sb.toString());

        if (dao.findBySampleAccession("SIM001").isEmpty()) {
            result.fail("No segments found for sample SIM001");
        }
        if (dao.findOverlapping("chr5", 70_000_000, 150_000_000).isEmpty()) {
            result.fail("No overlapping chr5 segments found");
        }
        if (dao.findIscnDerived().isEmpty()) {
            result.fail("No ISCN-derived segments found");
        }
        if (result.passed()) {
            result.pass("All required queries returned expected result sets");
        }
        return result;
    }

    private VerificationResult verifyDataIntegrity() throws SQLException {
        VerificationResult result = new VerificationResult("Data Integrity Verification");
        checkZero(result, "Orphan genomic_segments", """
                SELECT COUNT(*) FROM genomic_segments gs
                LEFT JOIN sample_test_results str ON str.sample_test_result_id = gs.sample_test_result_id
                WHERE str.sample_test_result_id IS NULL
                """);
        checkZero(result, "Orphan karyotypes", """
                SELECT COUNT(*) FROM karyotypes k
                LEFT JOIN sample_test_results str ON str.sample_test_result_id = k.sample_test_result_id
                WHERE str.sample_test_result_id IS NULL
                """);
        checkZero(result, "Orphan sample_test_results", """
                SELECT COUNT(*) FROM sample_test_results str
                LEFT JOIN sample_tests st ON st.sample_test_id = str.sample_test_id
                WHERE st.sample_test_id IS NULL
                """);
        checkZero(result, "Broken source_file links", """
                SELECT COUNT(*) FROM sample_test_results str
                LEFT JOIN source_files sf ON sf.source_file_id = str.source_file_id
                WHERE str.source_file_id IS NOT NULL AND sf.source_file_id IS NULL
                """);
        checkZero(result, "Duplicate accession identifiers", """
                SELECT COUNT(*) FROM (
                    SELECT accession_identifier FROM sample_accessions
                    GROUP BY accession_identifier HAVING COUNT(*) > 1
                )
                """);
        if (result.passed()) {
            result.pass("No orphan rows or duplicate accessions detected");
        }
        return result;
    }

    private void checkZero(VerificationResult result, String label, String sql) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            long count = scalarLong(ps);
            if (count != 0) {
                result.fail(label + ": " + count);
            }
        }
    }

    private Map<String, Long> tableCounts() throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : REQUIRED_TABLES) {
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM " + table)) {
                counts.put(table, scalarLong(ps));
            }
        }
        return counts;
    }

    private void writeReports(Map<String, Long> tableCounts, List<ImportResult> importResults, VerificationResult query)
            throws IOException, SQLException {
        StringBuilder importSummary = new StringBuilder();
        importSummary.append("Validation Meaning\n");
        importSummary.append("ERROR\tRejected row; no genomic_segments row inserted\n");
        importSummary.append("WARNING\tImported row; flagged for review\n\n");
        importSummary.append("Table Counts\n");
        tableCounts.forEach((table, count) -> importSummary.append(table).append('\t').append(count).append('\n'));
        importSummary.append("\nImport Results\n");
        for (ImportResult result : importResults) {
            importSummary.append(result.fileName()).append('\t')
                    .append(result.success() ? "PASS" : "FAIL").append('\t')
                    .append("records=").append(result.recordsSeen()).append('\t')
                    .append("segments=").append(result.segmentsInserted()).append('\t')
                    .append("issues=").append(result.issuesInserted()).append('\n');
        }
        Files.writeString(outputDir.resolve("import_summary.txt"), importSummary.toString());
        Files.writeString(outputDir.resolve("segments.tsv"), queryText("""
                SELECT segment_id, sample_test_result_id, karyotype_id, chromosome, start_pos, stop_pos,
                       event_type, copy_number, array_score, number_of_sites, annotations
                FROM genomic_segments ORDER BY segment_id
                """));
        Files.writeString(outputDir.resolve("source_files.tsv"), queryText("""
                SELECT
                    sf.source_file_id,
                    sf.file_name,
                    sf.file_path,
                    p.software_name AS pipeline_name,
                    sf.imported_at,
                    sf.import_status,
                    sf.row_count,
                    sf.notes
                FROM source_files sf
                LEFT JOIN pipelines p ON p.pipeline_id = sf.pipeline_id
                ORDER BY sf.source_file_id
                """));
        Files.writeString(outputDir.resolve("sample_test_results.tsv"), queryText("""
                SELECT
                    str.sample_test_result_id,
                    str.sample_test_id,
                    str.pipeline_id,
                    str.source_file_id,
                    sf.file_name AS source_file,
                    str.genome_build,
                    str.calling_method,
                    str.raw_iscn,
                    str.annotation_names,
                    str.line_number
                FROM sample_test_results str
                LEFT JOIN source_files sf ON sf.source_file_id = str.source_file_id
                ORDER BY str.sample_test_result_id
                """));
        Files.writeString(outputDir.resolve("segments_by_sample.tsv"), queryText("""
                SELECT sa.accession_identifier, gs.segment_id, gs.chromosome, gs.start_pos, gs.stop_pos,
                       gs.event_type, gs.copy_number
                FROM genomic_segments gs
                JOIN sample_test_results str ON str.sample_test_result_id = gs.sample_test_result_id
                JOIN sample_tests st ON st.sample_test_id = str.sample_test_id
                JOIN sample_accessions sa ON sa.sample_accession_id = st.sample_accession_id
                ORDER BY sa.accession_identifier, gs.segment_id
                """));
        Files.writeString(outputDir.resolve("karyotypes.tsv"), queryText("""
                SELECT karyotype_id, sample_test_result_id, karyotype_text, clone_number, cell_count, abnormalities
                FROM karyotypes ORDER BY karyotype_id
                """));
        Files.writeString(outputDir.resolve("validation_issues.tsv"), queryText("""
                SELECT
                       vi.validation_issue_id,
                       vi.segment_id,
                       vi.source_file_id,
                       sf.file_name AS source_file,
                       sf.file_path AS source_file_path,
                       vi.line_number AS row_number,
                       vi.sample_accession_id,
                       vi.issue_type,
                       vi.severity,
                       vi.issue_message,
                       CASE
                           WHEN vi.severity = 'ERROR' THEN 'REJECTED ROW'
                           WHEN vi.severity = 'WARNING' THEN 'IMPORTED + FLAGGED'
                           ELSE 'REVIEW'
                       END AS validation_behavior
                FROM validation_issues vi
                LEFT JOIN source_files sf ON sf.source_file_id = vi.source_file_id
                ORDER BY vi.validation_issue_id
                """));
        Files.writeString(outputDir.resolve("result_trace.tsv"), queryText("""
                SELECT
                    sa.accession_identifier,
                    i.individual_id,
                    st.sample_test_id,
                    str.sample_test_result_id,
                    lp.technology AS lab_protocol,
                    st.test_type,
                    p.software_name AS pipeline,
                    str.source_file_id,
                    sf.file_name AS source_file,
                    sf.import_status,
                    str.line_number AS source_row_number,
                    str.genome_build,
                    str.calling_method,
                    str.raw_iscn,
                    str.annotation_names,
                    gs.segment_id,
                    gs.chromosome,
                    gs.start_pos,
                    gs.stop_pos,
                    gs.event_type,
                    gs.copy_number,
                    gs.annotations,
                    COALESCE(vi.validation_issue_count, 0) AS validation_issue_count,
                    COALESCE(vi.validation_summary, '') AS validation_summary
                FROM genomic_segments gs
                JOIN sample_test_results str ON str.sample_test_result_id = gs.sample_test_result_id
                JOIN sample_tests st ON st.sample_test_id = str.sample_test_id
                JOIN sample_accessions sa ON sa.sample_accession_id = st.sample_accession_id
                JOIN individuals i ON i.individual_id = sa.individual_id
                JOIN lab_protocols lp ON lp.lab_protocol_id = st.lab_protocol_id
                JOIN pipelines p ON p.pipeline_id = str.pipeline_id
                LEFT JOIN source_files sf ON sf.source_file_id = str.source_file_id
                LEFT JOIN (
                    SELECT
                        segment_id,
                        COUNT(*) AS validation_issue_count,
                        LISTAGG(severity || ':' || issue_type, '; ') WITHIN GROUP (ORDER BY validation_issue_id) AS validation_summary
                    FROM validation_issues
                    WHERE segment_id IS NOT NULL
                    GROUP BY segment_id
                ) vi ON vi.segment_id = gs.segment_id
                ORDER BY gs.segment_id
                """));
    }

    private String queryText(String sql) throws SQLException {
        StringBuilder sb = new StringBuilder();
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            int columns = rs.getMetaData().getColumnCount();
            for (int i = 1; i <= columns; i++) {
                if (i > 1) {
                    sb.append('\t');
                }
                sb.append(rs.getMetaData().getColumnLabel(i));
            }
            sb.append('\n');
            while (rs.next()) {
                for (int i = 1; i <= columns; i++) {
                    if (i > 1) {
                        sb.append('\t');
                    }
                    sb.append(rs.getString(i) == null ? "" : rs.getString(i));
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private void appendSegments(StringBuilder sb, String title, List<GenomicSegment> segments) {
        sb.append('\n').append(title).append('\n');
        sb.append("segment_id\tsample_test_result_id\tkaryotype_id\tchromosome\tstart_pos\tstop_pos\tevent_type\tcopy_number\n");
        for (GenomicSegment segment : segments) {
            sb.append(segment.id()).append('\t')
                    .append(segment.sampleTestResultId()).append('\t')
                    .append(segment.karyotypeId() == null ? "" : segment.karyotypeId()).append('\t')
                    .append(segment.chromosome()).append('\t')
                    .append(segment.startPos()).append('\t')
                    .append(segment.stopPos()).append('\t')
                    .append(segment.eventType()).append('\t')
                    .append(segment.copyNumber()).append('\n');
        }
    }

    private long scalarLong(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private void appendCount(StringBuilder sb, Map<String, Long> counts, String label, String key) {
        sb.append(label).append(": ").append(counts.getOrDefault(key, 0L)).append('\n');
    }

    private String passFail(boolean value) {
        return value ? "PASS" : "FAIL";
    }

    private void appendFailureMessages(StringBuilder sb, VerificationReport report) {
        List<VerificationResult> results = List.of(
                report.schema(), report.relationship(), report.iscn(), report.array(),
                report.query(), report.dataIntegrity());
        boolean anyFailures = results.stream().anyMatch(r -> !r.passed());
        if (!anyFailures) {
            return;
        }
        sb.append("\nFailure Details\n---------------\n");
        for (VerificationResult result : results) {
            if (!result.passed()) {
                for (String message : result.messages()) {
                    sb.append(result.name()).append(": ").append(message).append('\n');
                }
            }
        }
    }
}
