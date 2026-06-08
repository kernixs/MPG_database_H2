package org.mpgdatabase;

import org.junit.jupiter.api.Test;
import org.mpgdatabase.dao.ClinicalDecisionDao;
import org.mpgdatabase.db.Database;
import org.mpgdatabase.importer.ClinicalDecisionImportService;
import org.mpgdatabase.importer.CnvImportService;
import org.mpgdatabase.importer.CnvParserFactory;
import org.mpgdatabase.model.Models.Note;
import org.mpgdatabase.model.Models.SignedOutCall;
import org.mpgdatabase.model.Models.VariantClassification;
import org.mpgdatabase.report.SearchService;
import org.mpgdatabase.report.VerificationReport;
import org.mpgdatabase.report.VerificationService;

import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Phase2WorkflowTest {
    @Test
    void importsCnvFilesAndPassesVerification() throws Exception {
        try (Connection connection = Database.connect("jdbc:h2:mem:phase2;DB_CLOSE_DELAY=-1")) {
            Database.initialize(connection);
            CnvImportService importer = new CnvImportService(connection, new CnvParserFactory());
            var example = importer.importFile(Path.of("data/example.cnv"), null);
            var test = importer.importFile(Path.of("data/test.cnv"), null);

            Path outputDir = Files.createTempDirectory("phase2-output");
            VerificationService verification = new VerificationService(connection, outputDir);
            VerificationReport report = verification.verify(true, List.of(example, test));

            assertTrue(example.success());
            assertTrue(test.success());
            assertTrue(report.overallPassed(), verification.terminalSummary(report));
            assertEquals(3, report.tableCounts().get("genomic_segments"));
            assertEquals(3, report.tableCounts().get("genomic_events"));
            assertEquals(0, report.tableCounts().get("genomic_links"));
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
            assertTrue(Files.exists(outputDir.resolve("source_files.tsv")));
            assertTrue(Files.exists(outputDir.resolve("genomic_events.tsv")));
            assertTrue(Files.exists(outputDir.resolve("genomic_links.tsv")));
            assertTrue(Files.exists(outputDir.resolve("circos_links.tsv")));
            String resultTrace = Files.readString(outputDir.resolve("result_trace.tsv"));
            assertTrue(resultTrace.contains("EVENT_ID"), resultTrace);
            assertTrue(resultTrace.contains("EVENT_STATUS"), resultTrace);
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
            assertEquals(0, count(connection, """
                    SELECT COUNT(*) FROM genomic_segments
                    WHERE event_id IS NULL
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
            assertEquals(452, count(connection, "SELECT COUNT(*) FROM genomic_events"));
            assertEquals(452, count(connection, "SELECT COUNT(*) FROM genomic_segments WHERE event_id IS NOT NULL"));
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

            String eventSearch = new SearchService(connection).search(Map.of("event-id", "1"));
            assertTrue(eventSearch.contains("EVENT_ID"), eventSearch);
            assertTrue(eventSearch.contains("Rows: 1"), eventSearch);
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
    void storesPhase26ClinicalDecisionLayer() throws Exception {
        try (Connection connection = Database.connect("jdbc:h2:mem:phase26_clinical;DB_CLOSE_DELAY=-1")) {
            Database.initialize(connection);
            CnvImportService importer = new CnvImportService(connection, new CnvParserFactory());
            importer.importFile(Path.of("data/example.cnv"), null);

            SegmentContext context = firstSegmentContext(connection);
            ClinicalDecisionDao dao = new ClinicalDecisionDao(connection);
            long classificationId = dao.createVariantClassification(new VariantClassification(
                    0,
                    context.segmentId(),
                    "Likely Pathogenic",
                    "ACMG/ClinGen CNV",
                    "2020",
                    0.95,
                    "Overlaps dosage-sensitive region and matches the reported phenotype.",
                    "reviewer1",
                    "Ready for sign-out",
                    true,
                    null));
            long signedOutCallId = dao.createSignedOutCall(new SignedOutCall(
                    0,
                    context.segmentId(),
                    classificationId,
                    context.individualId(),
                    context.sampleTestResultId(),
                    "Diagnostic",
                    "Explains the congenital indication for testing.",
                    "The copy-number loss is interpreted as clinically relevant for this individual.",
                    "Signed out",
                    "director1",
                    "Likely pathogenic copy-number loss reported as diagnostic.",
                    "1",
                    null));
            long noteId = dao.createNote(new Note(
                    0,
                    "signed_out_calls",
                    signedOutCallId,
                    "Reviewer note",
                    "Checked ClinGen dosage and source-file annotations before sign-out.",
                    "reviewer1"));

            assertTrue(classificationId > 0);
            assertTrue(signedOutCallId > 0);
            assertTrue(noteId > 0);
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM variant_classifications"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM signed_out_calls"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM notes"));
            assertEquals(1, count(connection, """
                    SELECT COUNT(*) FROM signed_out_calls soc
                    JOIN variant_classifications vc ON vc.classification_id = soc.classification_id
                    WHERE vc.classification_label = 'Likely Pathogenic'
                      AND vc.evidence_summary LIKE '%dosage-sensitive%'
                      AND soc.clinical_significance = 'Diagnostic'
                      AND soc.signed_out_status = 'Signed out'
                    """));
            assertTrue(dao.clinicalTraceBySegment(context.segmentId()).stream()
                    .anyMatch(row -> row.contains("Likely Pathogenic")
                            && row.contains("Diagnostic")
                            && row.contains("Signed out")
                            && row.contains("ClinGen dosage")));

            Path outputDir = Files.createTempDirectory("phase26-output");
            VerificationReport report = new VerificationService(connection, outputDir)
                    .verify(true, List.of());
            assertTrue(report.overallPassed(), new VerificationService(connection, outputDir)
                    .terminalSummary(report));
            assertEquals(1, report.tableCounts().get("variant_classifications"));
            assertEquals(1, report.tableCounts().get("signed_out_calls"));
            assertEquals(1, report.tableCounts().get("notes"));
            assertTrue(Files.readString(outputDir.resolve("signed_out_calls.tsv"))
                    .contains("Diagnostic"));
            assertTrue(Files.readString(outputDir.resolve("signed_out_calls.tsv"))
                    .contains("Signed out"));
            assertTrue(Files.readString(outputDir.resolve("notes.tsv"))
                    .contains("Reviewer note"));

            assertThrows(SQLException.class, () -> dao.createSignedOutCall(new SignedOutCall(
                    0,
                    context.segmentId(),
                    classificationId,
                    context.individualId(),
                    context.sampleTestResultId(),
                    "Maybe important",
                    "Invalid significance should be rejected.",
                    "Invalid significance should be rejected.",
                    "Signed out",
                    "director1",
                    "Invalid significance should be rejected.",
                    "1",
                    null)));
        }
    }

    @Test
    void importsPhase26ClinicalDecisionFile() throws Exception {
        try (Connection connection = Database.connect("jdbc:h2:mem:phase26_clinical_file;DB_CLOSE_DELAY=-1")) {
            Database.initialize(connection);
            CnvImportService cnvImporter = new CnvImportService(connection, new CnvParserFactory());
            cnvImporter.importFile(Path.of("data/example.cnv"), null);

            var result = new ClinicalDecisionImportService(connection)
                    .importFile(Path.of("data/clinical/phase26_clinical_decisions.tsv"));

            assertTrue(result.success());
            assertEquals(100, result.recordsSeen());
            assertEquals(100, result.classificationsInserted());
            assertEquals(100, result.signedOutCallsInserted());
            assertEquals(100, result.notesInserted());
            assertEquals(100, count(connection, "SELECT COUNT(*) FROM variant_classifications"));
            assertEquals(100, count(connection, "SELECT COUNT(*) FROM signed_out_calls"));
            assertEquals(100, count(connection, "SELECT COUNT(*) FROM notes"));
            assertEquals(20, count(connection, """
                    SELECT COUNT(*) FROM signed_out_calls soc
                    JOIN variant_classifications vc ON vc.classification_id = soc.classification_id
                    WHERE vc.classification_label = 'Likely Pathogenic'
                      AND soc.clinical_significance = 'Diagnostic'
                      AND soc.signed_out_status = 'Signed out'
                    """));
            assertEquals(20, count(connection, """
                    SELECT COUNT(*) FROM notes
                    WHERE note_type = 'Follow-up note'
                      AND note_text LIKE '%parental studies%'
                    """));

            Path outputDir = Files.createTempDirectory("phase26-file-output");
            VerificationReport report = new VerificationService(connection, outputDir)
                    .verify(true, List.of(), List.of(result));
            assertTrue(report.overallPassed());
            String importSummary = Files.readString(outputDir.resolve("import_summary.txt"));
            assertTrue(importSummary.contains("Clinical Decision Import Results"), importSummary);
            assertTrue(importSummary.contains("phase26_clinical_decisions.tsv"), importSummary);
            assertTrue(importSummary.contains("classifications=100"), importSummary);
            String runLog = Files.readString(outputDir.resolve("run_log.tsv"));
            assertTrue(runLog.contains("clinical_decision_import"), runLog);
            assertTrue(runLog.contains("phase26_clinical_decisions.tsv"), runLog);
            assertTrue(runLog.contains("classifications_inserted"), runLog);
        }
    }

    @Test
    void importsGenericTranslocationAsLinkedSegmentsForCircos() throws Exception {
        try (Connection connection = Database.connect("jdbc:h2:mem:translocation_links;DB_CLOSE_DELAY=-1")) {
            Database.initialize(connection);
            CnvImportService importer = new CnvImportService(connection, new CnvParserFactory());
            var result = importer.importFile(Path.of("data/translocation_pair.cnv"), null);

            assertTrue(result.success());
            assertEquals(100, result.recordsSeen());
            assertEquals(100, result.segmentsInserted());
            assertEquals(50, count(connection, "SELECT COUNT(*) FROM genomic_events"));
            assertEquals(100, count(connection, "SELECT COUNT(*) FROM genomic_segments"));
            assertEquals(50, count(connection, "SELECT COUNT(*) FROM genomic_links"));
            assertEquals(1, count(connection, """
                    SELECT COUNT(*) FROM genomic_links gl
                    JOIN genomic_segments src ON src.segment_id = gl.source_segment_id
                    JOIN genomic_segments tgt ON tgt.segment_id = gl.target_segment_id
                    WHERE gl.link_type = 'TRANSLOCATION'
                      AND src.chromosome = 'chr9'
                      AND tgt.chromosome = 'chr22'
                    """));

            Path outputDir = Files.createTempDirectory("translocation-output");
            VerificationReport report = new VerificationService(connection, outputDir)
                    .verify(true, List.of(result));
            assertTrue(report.schema().passed(), new VerificationService(connection, outputDir)
                    .terminalSummary(report));
            assertTrue(report.dataIntegrity().passed(), new VerificationService(connection, outputDir)
                    .terminalSummary(report));
            assertTrue(Files.readString(outputDir.resolve("genomic_links.tsv"))
                    .contains("TRANSLOCATION"));
            assertTrue(Files.readString(outputDir.resolve("circos_links.tsv"))
                    .contains("chr22"));
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

    private SegmentContext firstSegmentContext(Connection connection) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT gs.segment_id, gs.sample_test_result_id, sa.individual_id
                FROM genomic_segments gs
                JOIN sample_test_results str ON str.sample_test_result_id = gs.sample_test_result_id
                JOIN sample_tests st ON st.sample_test_id = str.sample_test_id
                JOIN sample_accessions sa ON sa.sample_accession_id = st.sample_accession_id
                ORDER BY gs.segment_id
                LIMIT 1
                """)) {
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return new SegmentContext(
                        rs.getLong("segment_id"),
                        rs.getLong("sample_test_result_id"),
                        rs.getLong("individual_id"));
            }
        }
    }

    private record SegmentContext(long segmentId, long sampleTestResultId, long individualId) {
    }
}
