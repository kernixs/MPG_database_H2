package org.mpgdatabase;

import org.junit.jupiter.api.Test;
import org.mpgdatabase.db.Database;
import org.mpgdatabase.importer.CnvImportService;
import org.mpgdatabase.importer.CnvParserFactory;
import org.mpgdatabase.report.SearchService;
import org.mpgdatabase.report.VerificationReport;
import org.mpgdatabase.report.VerificationService;

import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Phase2WorkflowTest {
    @Test
    void importsCnvFilesAndPassesVerification() throws Exception {
        try (Connection connection = Database.connect("jdbc:h2:mem:phase2;DB_CLOSE_DELAY=-1")) {
            Database.initialize(connection);
            CnvImportService importer = new CnvImportService(connection, new CnvParserFactory());
            var example = importer.importFile(Path.of("data/example.cnv"), null);
            var test = importer.importFile(Path.of("data/test.cnv"), null);

            VerificationService verification = new VerificationService(connection, Path.of("output/test"));
            VerificationReport report = verification.verify(true, List.of(example, test));

            assertTrue(example.success());
            assertTrue(test.success());
            assertTrue(report.overallPassed(), verification.terminalSummary(report));
            assertEquals(3, report.tableCounts().get("genomic_segments"));
            assertEquals(3, report.tableCounts().get("validation_issues"));
            assertEquals(2, report.tableCounts().get("source_files"));
            assertEquals(2, count(connection, """
                    SELECT COUNT(*) FROM validation_issues
                    WHERE issue_type = 'Missing Genome Build' AND severity = 'ERROR'
                    """));
            assertEquals(0, count(connection, """
                    SELECT COUNT(*) FROM genomic_segments gs
                    JOIN sample_test_results str ON str.sample_test_result_id = gs.sample_test_result_id
                    WHERE str.genome_build = 'unknown'
                    """));
            assertEquals(0, count(connection, """
                    SELECT COUNT(*) FROM sample_test_results str
                    LEFT JOIN source_files sf ON sf.source_file_id = str.source_file_id
                    WHERE sf.source_file_id IS NULL
                    """));
            assertEquals(3, count(connection, """
                    SELECT COUNT(*) FROM validation_issues vi
                    JOIN source_files sf ON sf.source_file_id = vi.source_file_id
                    WHERE sf.file_name = 'test.cnv' AND vi.line_number IS NOT NULL
                    """));
            assertEquals(1, count(connection, """
                    SELECT COUNT(*) FROM source_files
                    WHERE file_name = 'example.cnv' AND import_status = 'SUCCESS'
                    """));
            assertEquals(1, count(connection, """
                    SELECT COUNT(*) FROM source_files
                    WHERE file_name = 'test.cnv' AND import_status = 'FAILED'
                    """));
            assertTrue(Files.exists(Path.of("output/test/source_files.tsv")));
            String resultTrace = Files.readString(Path.of("output/test/result_trace.tsv"));
            assertTrue(resultTrace.contains("SOURCE_FILE_ID"), resultTrace);
            assertTrue(resultTrace.contains("SOURCE_FILE"), resultTrace);
            assertTrue(resultTrace.contains("INDIVIDUAL_ID"), resultTrace);
            assertTrue(resultTrace.contains("IMPORT_STATUS"), resultTrace);
            assertTrue(resultTrace.contains("SOURCE_ROW_NUMBER"), resultTrace);
            assertTrue(resultTrace.contains("VALIDATION_ISSUE_COUNT"), resultTrace);
            assertTrue(resultTrace.contains("VALIDATION_SUMMARY"), resultTrace);
            assertEquals(0, count(connection, """
                    SELECT COUNT(*) FROM sample_test_results str
                    JOIN genomic_segments gs ON gs.sample_test_result_id = str.sample_test_result_id
                    WHERE str.line_number IS NULL
                    """));
        }
    }

    @Test
    void importsNgsAnnotationsAndSearchesAnnotationsAndMultipleEventTypes() throws Exception {
        try (Connection connection = Database.connect("jdbc:h2:mem:phase25;DB_CLOSE_DELAY=-1")) {
            Database.initialize(connection);
            CnvImportService importer = new CnvImportService(connection, new CnvParserFactory());
            var wgs = importer.importFile(
                    Path.of("data/25-54321WGS_05Nov2025_SWGS111_SV_DEL_DUP_Classify_Result.txt"),
                    null);

            assertTrue(wgs.success());
            assertEquals(452, wgs.segmentsInserted());
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM source_files"));
            assertEquals(1, count(connection, """
                    SELECT COUNT(*) FROM source_files
                    WHERE import_status = 'SUCCESS'
                    """));
            assertEquals(452, count(connection, """
                    SELECT COUNT(*) FROM sample_test_results
                    WHERE calling_method = 'NGS-derived' AND genome_build = 'hg19'
                    """));
            assertEquals(0, count(connection, """
                    SELECT COUNT(*) FROM genomic_segments gs
                    JOIN sample_test_results str ON str.sample_test_result_id = gs.sample_test_result_id
                    WHERE COALESCE(str.annotation_names, '') = '' OR COALESCE(gs.annotations, '') = ''
                    """));

            String geneSearch = new SearchService(connection).search(Map.of("annotation", "Gene=FCGR3A"));
            assertTrue(geneSearch.contains("FCGR3A"), geneSearch);
            assertFalse(geneSearch.contains("Rows: 0"), geneSearch);

            String classSearch = new SearchService(connection).search(Map.of("annotation", "Class=CDP2"));
            assertTrue(classSearch.contains("CDP2"), classSearch);

            String multipleAnnotationSearch = new SearchService(connection).search(Map.of(
                    "annotation", "Gene=FCGR3A\nClinical=1"));
            assertTrue(multipleAnnotationSearch.contains("Gene=FCGR3A"), multipleAnnotationSearch);
            assertTrue(multipleAnnotationSearch.contains("Clinical=1"), multipleAnnotationSearch);

            String annotationValueOrSearch = new SearchService(connection).search(Map.of(
                    "annotation", "Gene=FCGR3A,CYP2A6"));
            assertTrue(annotationValueOrSearch.contains("FCGR3A"), annotationValueOrSearch);
            assertTrue(annotationValueOrSearch.contains("CYP2A6"), annotationValueOrSearch);

            String annotationAndNormalFilterSearch = new SearchService(connection).search(Map.of(
                    "annotation", "Gene=FCGR3A",
                    "event-type", "DUP"));
            assertTrue(annotationAndNormalFilterSearch.contains("FCGR3A"), annotationAndNormalFilterSearch);
            assertTrue(annotationAndNormalFilterSearch.contains("DUP"), annotationAndNormalFilterSearch);

            String exactSearch = new SearchService(connection).search(Map.of("annotation", "Gene=BRCA"));
            assertTrue(exactSearch.contains("Rows: 0"), exactSearch);

            String unknownAnnotationKeySearch = new SearchService(connection).search(Map.of("annotation", "NotAKey=FCGR3A"));
            assertTrue(unknownAnnotationKeySearch.contains("Rows: 0"), unknownAnnotationKeySearch);

            String emptyAnnotationValueSearch = new SearchService(connection).search(Map.of("annotation", "Gene="));
            assertFalse(emptyAnnotationValueSearch.isBlank());

            String multiEventSearch = new SearchService(connection).search(Map.of("event-type", "DEL,DUP"));
            assertTrue(multiEventSearch.contains("DEL"), multiEventSearch);
            assertTrue(multiEventSearch.contains("DUP"), multiEventSearch);

            String whitespaceSearch = new SearchService(connection).search(Map.of("event-type", "DEL, DUP"));
            assertTrue(whitespaceSearch.contains("DEL"), whitespaceSearch);
            assertTrue(whitespaceSearch.contains("DUP"), whitespaceSearch);

            String emptyValueSearch = new SearchService(connection).search(Map.of("event-type", "DEL,,DUP"));
            assertTrue(emptyValueSearch.contains("DEL"), emptyValueSearch);
            assertTrue(emptyValueSearch.contains("DUP"), emptyValueSearch);

            String chromosomeAndEventSearch = new SearchService(connection).search(Map.of(
                    "chromosome", "chr5,chr7",
                    "event-type", "DEL,DUP"));
            assertTrue(chromosomeAndEventSearch.contains("chr5") || chromosomeAndEventSearch.contains("chr7"),
                    chromosomeAndEventSearch);
            assertFalse(chromosomeAndEventSearch.contains("chr1\t"), chromosomeAndEventSearch);

            String callingMethodSearch = new SearchService(connection).search(Map.of(
                    "calling-method", "ISCN-derived,Array-derived,NGS-derived"));
            assertTrue(callingMethodSearch.contains("NGS-derived"), callingMethodSearch);

            String genomeBuildSearch = new SearchService(connection).search(Map.of("genome-build", "hg19,hg38"));
            assertTrue(genomeBuildSearch.contains("hg19"), genomeBuildSearch);

            String unknownSearch = new SearchService(connection).search(Map.of("event-type", "NOT_A_REAL_EVENT"));
            assertTrue(unknownSearch.contains("Rows: 0"), unknownSearch);

            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE genomic_segments
                    SET annotations = annotations || ';extra'
                    WHERE segment_id = 1
                    """)) {
                ps.executeUpdate();
            }
            String mismatchSearch = new SearchService(connection).search(Map.of("annotation", "Gene=FCGR3A"));
            assertFalse(mismatchSearch.isBlank());
            assertEquals(1, issueCount(connection, "Annotation Count Mismatch", "WARNING"));
        }
    }

    @Test
    void searchesMultipleSamplesAndPreservesSingleValueBehavior() throws Exception {
        try (Connection connection = Database.connect("jdbc:h2:mem:phase25_search_samples;DB_CLOSE_DELAY=-1")) {
            Database.initialize(connection);
            CnvImportService importer = new CnvImportService(connection, new CnvParserFactory());
            importer.importFile(Path.of("data/example.cnv"), null);

            String multiSampleSearch = new SearchService(connection).search(Map.of("sample", "SIM001,SIM002"));
            assertTrue(multiSampleSearch.contains("SIM001"), multiSampleSearch);
            assertTrue(multiSampleSearch.contains("SIM002"), multiSampleSearch);

            String singleSearch = new SearchService(connection).search(Map.of(
                    "sample", "SIM001",
                    "event-type", "DEL"));
            assertTrue(singleSearch.contains("SIM001"), singleSearch);
            assertTrue(singleSearch.contains("DEL"), singleSearch);
            assertFalse(singleSearch.contains("SIM002"), singleSearch);
        }
    }

    @Test
    void annotationMismatchImportsSegmentWithWarningWhenCoreFieldsAreValid() throws Exception {
        try (Connection connection = Database.connect("jdbc:h2:mem:phase25_annotation_mismatch;DB_CLOSE_DELAY=-1")) {
            Database.initialize(connection);
            CnvImportService importer = new CnvImportService(connection, new CnvParserFactory());
            var array = importer.importFile(Path.of("data/array_derived_100.cnv"), null);
            var iscn = importer.importFile(Path.of("data/iscn_derived_100.cnv"), null);

            assertTrue(array.success());
            assertTrue(iscn.success());
            assertEquals(100, array.segmentsInserted());
            assertEquals(100, iscn.segmentsInserted());
            assertEquals(0, issueCount(connection, "Annotation Count Mismatch", "WARNING"));
            assertEquals(0, count(connection, """
                    SELECT COUNT(*) FROM validation_issues
                    WHERE issue_type = 'Annotation Count Mismatch'
                      AND severity = 'ERROR'
                    """));
        }
    }

    @Test
    void appliesRejectAndImportWithWarningValidationRules() throws Exception {
        Path tempDir = Files.createTempDirectory("phase25-validation");
        Path input = tempDir.resolve("validation_cases.cnv");
        Files.writeString(input, String.join("\n",
                "sample_accession_id\tchromosome\tstart_pos\tstop_pos\tevent_type\tcopy_number\tgenome_build\traw_iscn",
                "MISSBUILD\tchr1\t10\t20\tDEL\t1\t\t",
                "MISSCHR\t\t10\t20\tDEL\t1\thg38\t",
                "MISSSTART\tchr1\t\t20\tDEL\t1\thg38\t",
                "MISSEND\tchr1\t10\t\tDEL\t1\thg38\t",
                "BADINTERVAL\tchr1\t30\t20\tDEL\t1\thg38\t",
                "UNKNOWNEVENT\tchr1\t10\t20\tWEIRD\t2\thg38\t",
                "UNPARSEABLE\tchr1\t10",
                "ADDCASE\tchr1\t10\t20\tADD\t3\thg38\t46,XX,add(1)(p36)",
                "DERCASE\tchr1\t10\t20\tDER\t2\thg38\t46,XX,der(1)",
                "MARCASE\tchr1\t10\t20\tMAR\t3\thg38\t47,XX,+mar",
                "QUESTIONCASE\tchr1\t10\t20\tDEL\t1\thg38\t46,XX,del(1)(p36?)",
                "MOSAICCASE\tchr1\t10\t20\tDEL\t1\thg38\t46,XX,del(1)(p36)/46,XX",
                "ERRORWINS\tchr1\t10\t20\tWEIRD\t3\t\t46,XX,add(1)(p36)",
                ""));

        try (Connection connection = Database.connect("jdbc:h2:mem:phase25_validation;DB_CLOSE_DELAY=-1")) {
            Database.initialize(connection);
            CnvImportService importer = new CnvImportService(connection, new CnvParserFactory());
            var result = importer.importFile(input, null);

            assertTrue(result.success());
            assertEquals(13, result.recordsSeen());
            assertEquals(6, result.segmentsInserted());
            assertEquals(11, result.issuesInserted());
            assertEquals(6, count(connection, "SELECT COUNT(*) FROM genomic_segments"));
            assertEquals(7, count(connection, "SELECT COUNT(*) FROM validation_issues WHERE severity = 'ERROR'"));
            assertEquals(4, count(connection, "SELECT COUNT(*) FROM validation_issues WHERE severity = 'WARNING'"));

            assertEquals(2, issueCount(connection, "Missing Genome Build", "ERROR"));
            assertEquals(1, issueCount(connection, "Missing Chromosome", "ERROR"));
            assertEquals(1, issueCount(connection, "Missing Start Position", "ERROR"));
            assertEquals(1, issueCount(connection, "Missing End Position", "ERROR"));
            assertEquals(1, issueCount(connection, "Invalid Interval", "ERROR"));
            assertEquals(1, issueCount(connection, "Unparseable Row", "ERROR"));

            assertEquals(1, issueCount(connection, "Unknown Event Type", "WARNING"));
            assertEquals(1, issueCount(connection, "Marker Chromosome Event", "WARNING"));
            assertEquals(1, issueCount(connection, "Uncertain Breakpoint", "WARNING"));
            assertEquals(1, issueCount(connection, "Mosaic Karyotype", "WARNING"));
            assertEquals(0, count(connection, """
                    SELECT COUNT(*) FROM validation_issues
                    WHERE issue_type = 'Marker Chromosome Event'
                      AND severity = 'WARNING'
                      AND segment_id IS NULL
                    """));
            assertEquals(1, count(connection, """
                    SELECT COUNT(*) FROM genomic_segments
                    WHERE event_type = 'ADD'
                    """));
            assertEquals(1, count(connection, """
                    SELECT COUNT(*) FROM genomic_segments
                    WHERE event_type = 'DER'
                    """));
            assertEquals(1, count(connection, """
                    SELECT COUNT(*) FROM genomic_segments gs
                    JOIN sample_test_results str ON str.sample_test_result_id = gs.sample_test_result_id
                    WHERE str.raw_iscn = '46,XX,add(1)(p36)'
                    """));
            assertEquals(2, count(connection, """
                    SELECT COUNT(*) FROM genomic_segments
                    WHERE event_type = 'UNKNOWN'
                    """));
            assertEquals(1, count(connection, """
                    SELECT COUNT(*) FROM genomic_segments gs
                    JOIN sample_test_results str ON str.sample_test_result_id = gs.sample_test_result_id
                    WHERE str.raw_iscn = '46,XX,del(1)(p36)/46,XX'
                      AND gs.event_type = 'DEL'
                    """));
            assertEquals(0, count(connection, """
                    SELECT COUNT(*) FROM validation_issues
                    WHERE sample_accession_id IS NULL
                    """));
            assertEquals(1, count(connection, """
                    SELECT COUNT(*) FROM source_files
                    WHERE import_status = 'PARTIAL_SUCCESS'
                    """));
        }
    }

    private long count(Connection connection, String sql) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private long issueCount(Connection connection, String issueType, String severity) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT COUNT(*) FROM validation_issues
                WHERE issue_type = ? AND severity = ?
                """)) {
            ps.setString(1, issueType);
            ps.setString(2, severity);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }
}
