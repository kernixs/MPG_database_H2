package org.mpgdatabase;

import org.junit.jupiter.api.Test;
import org.mpgdatabase.dao.ClinicalDecisionDao;
import org.mpgdatabase.db.Database;
import org.mpgdatabase.importer.ClinicalDecisionImportService;
import org.mpgdatabase.importer.CnvImportService;
import org.mpgdatabase.importer.CnvParserFactory;
import org.mpgdatabase.importer.VcfImportService;
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
    void importsVcfSmallVariantsWithMultiSampleMultiAltAndAnnotations() throws Exception {
        Path input = Files.createTempFile("small-variants", ".vcf");
        Files.writeString(input, """
                ##fileformat=VCFv4.2
                ##GATKCommandLine=<ID=CNNScoreVariants,CommandLine="--reference Homo_sapiens_assembly38.fasta --intervals exome.targets.hg38.sorted.bed">
                ##INFO=<ID=ANN,Number=.,Type=String,Description="Functional annotations: 'Allele | Annotation | Annotation_Impact | Gene_Name | Gene_ID | Feature_Type | Feature_ID | Transcript_BioType | Rank | HGVS.c | HGVS.p'">
                ##SnpEffVersion="SnpEff 5.1"
                #CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tSAMPLE_A\tSAMPLE_B
                1\t69270\trs1\tA\tG\t99\tPASS\tANN=G|missense_variant|MODERATE|TP53|GENE1|transcript|NM_000546.6|protein_coding|1/1|c.215C>G|p.Arg72Pro\tGT:AD:DP:GQ\t0/1:10,12:22:60\t1|1:0,30:30:99
                2\t100\t.\tA\tC,T\t50\tPASS\tANN=C|synonymous_variant|LOW|BRCA1|GENE2|transcript|NM_007294.4|protein_coding|1/1|c.1A>C|p.=,T|stop_gained|HIGH|RUNX1|GENE3|transcript|NM_001754.5|protein_coding|1/1|c.2A>T|p.Lys1*\tGT:AD:DP:GQ\t0/1:5,5,0:10:20\t0/2:3,0,7:10:21
                """);
        try (Connection connection = Database.connect("jdbc:h2:mem:vcf_small_variants;DB_CLOSE_DELAY=-1")) {
            Database.initialize(connection);
            var result = new VcfImportService(connection).importFile(input, null);

            assertTrue(result.success());
            assertEquals(2, result.recordsSeen());
            assertEquals(3, count(connection, "SELECT COUNT(*) FROM small_variants"));
            assertEquals(6, count(connection, "SELECT COUNT(*) FROM small_variant_sample_calls"));
            assertEquals(2, count(connection, "SELECT COUNT(*) FROM sample_test_results WHERE calling_method = 'VCF-small-variant'"));
            assertEquals(3, count(connection, "SELECT COUNT(*) FROM small_variants WHERE genome_build = 'GRCh38'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM small_variants WHERE variant_type = 'SNV' AND ref_allele = 'A' AND alt_allele = 'G'"));
            assertEquals(2, count(connection, "SELECT COUNT(*) FROM small_variants WHERE position = 100 AND alt_allele IN ('C', 'T')"));
            assertEquals(1, count(connection, """
                    SELECT COUNT(*)
                    FROM small_variant_sample_calls svc
                    JOIN sample_test_results str ON str.sample_test_result_id = svc.sample_test_result_id
                    JOIN sample_tests st ON st.sample_test_id = str.sample_test_id
                    JOIN sample_accessions sa ON sa.sample_accession_id = st.sample_accession_id
                    WHERE sa.accession_identifier = 'SAMPLE_B'
                      AND svc.genotype = '1|1'
                      AND svc.phased = TRUE
                      AND svc.ref_depth = 0
                      AND svc.alt_depth = 30
                      AND svc.total_depth = 30
                      AND svc.allele_balance = 1.0
                    """));
            assertEquals(1, count(connection, """
                    SELECT COUNT(*)
                    FROM small_variant_sample_calls svc
                    JOIN small_variants sv ON sv.small_variant_id = svc.small_variant_id
                    JOIN sample_test_results str ON str.sample_test_result_id = svc.sample_test_result_id
                    JOIN sample_tests st ON st.sample_test_id = str.sample_test_id
                    JOIN sample_accessions sa ON sa.sample_accession_id = st.sample_accession_id
                    WHERE sa.accession_identifier = 'SAMPLE_B'
                      AND sv.position = 100
                      AND sv.alt_allele = 'T'
                      AND svc.genotype = '0/2'
                      AND svc.alt_depth = 7
                      AND svc.allele_balance = 0.7
                    """));
            assertEquals(1, count(connection, """
                    SELECT COUNT(*)
                    FROM small_variant_annotations
                    WHERE gene = 'TP53'
                      AND transcript = 'NM_000546.6'
                      AND consequence = 'missense_variant'
                      AND impact = 'MODERATE'
                      AND annotation_source = 'SnpEff'
                    """));
        }
    }

    @Test
    void classifiesVcfSnvInsertionDeletionAndIndelTypes() throws Exception {
        Path input = Files.createTempFile("small-variant-types", ".vcf");
        Files.writeString(input, """
                ##fileformat=VCFv4.2
                ##reference=GRCh38
                #CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tSAMPLE_ONE
                1\t10\t.\tA\tG\t.\tPASS\t.\tGT:AD:DP:GQ\t0/1:4,6:10:20
                1\t20\t.\tA\tATG\t.\tPASS\t.\tGT:AD:DP:GQ\t0/1:4,6:10:20
                1\t30\t.\tATG\tA\t.\tPASS\t.\tGT:AD:DP:GQ\t0/1:4,6:10:20
                1\t40\t.\tATG\tGCA\t.\tPASS\t.\tGT:AD:DP:GQ\t0/1:4,6:10:20
                """);
        try (Connection connection = Database.connect("jdbc:h2:mem:vcf_variant_types;DB_CLOSE_DELAY=-1")) {
            Database.initialize(connection);
            var result = new VcfImportService(connection).importFile(input, null);

            assertTrue(result.success());
            assertEquals(4, count(connection, "SELECT COUNT(*) FROM small_variants"));
            assertEquals(4, count(connection, "SELECT COUNT(*) FROM small_variant_sample_calls"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM small_variants WHERE variant_type = 'SNV' AND position = 10"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM small_variants WHERE variant_type = 'INSERTION' AND position = 20"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM small_variants WHERE variant_type = 'DELETION' AND position = 30"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM small_variants WHERE variant_type = 'INDEL' AND position = 40"));
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM genomic_segments"));
        }
    }

    @Test
    void rejectsVcfRowsWhenGenomeBuildIsUnknown() throws Exception {
        Path input = Files.createTempFile("small-variant-no-build", ".vcf");
        Files.writeString(input, """
                ##fileformat=VCFv4.2
                #CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tSAMPLE_ONE
                1\t10\t.\tA\tG\t.\tPASS\t.\tGT\t0/1
                """);
        try (Connection connection = Database.connect("jdbc:h2:mem:vcf_missing_build;DB_CLOSE_DELAY=-1")) {
            Database.initialize(connection);
            var result = new VcfImportService(connection).importFile(input, null);

            assertTrue(result.success());
            assertEquals(1, result.recordsSeen());
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM small_variants"));
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM small_variant_sample_calls"));
            assertEquals(1, issueCount(connection, "Missing Genome Build", "ERROR"));
        }
    }

    @Test
    void skipsStructuralVariantVcfRowsForSmallVariantImport() throws Exception {
        Path input = Files.createTempFile("mixed-small-and-sv", ".vcf");
        Files.writeString(input, """
                ##fileformat=VCFv4.2
                ##reference=GRCh38
                ##INFO=<ID=SVTYPE,Number=1,Type=String,Description="Type of structural variant">
                #CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tSAMPLE_ONE
                1\t10\trs-small\tA\tG\t99\tPASS\t.\tGT:AD:DP:GQ\t0/1:4,6:10:20
                1\t100\tsv-del\tN\t<DEL>\t.\tPASS\tSVTYPE=DEL;END=200\tGT:DP\t0/1:18
                2\t300\tsv-bnd\tN\tN]chr3:400]\t.\tPASS\tSVTYPE=BND\tGT:DP\t0/1:22
                """);
        try (Connection connection = Database.connect("jdbc:h2:mem:vcf_skip_sv;DB_CLOSE_DELAY=-1")) {
            Database.initialize(connection);
            var result = new VcfImportService(connection).importFile(input, null);

            assertTrue(result.success());
            assertEquals(3, result.recordsSeen());
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM small_variants"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM small_variant_sample_calls"));
            assertEquals(2, issueCount(connection, "Unsupported VCF Structural Variant", "WARNING"));
            assertEquals(1, count(connection, """
                    SELECT COUNT(*) FROM small_variants
                    WHERE variant_id = 'rs-small'
                      AND chromosome = 'chr1'
                      AND position = 10
                      AND ref_allele = 'A'
                      AND alt_allele = 'G'
                    """));
            assertEquals(1, count(connection, """
                    SELECT COUNT(*) FROM source_files
                    WHERE import_status = 'PARTIAL_SUCCESS'
                    """));
        }
    }

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
            assertEquals(0, report.tableCounts().get("genomic_events"));
            assertEquals(0, report.tableCounts().get("genomic_event_groups"));
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
            assertTrue(Files.exists(outputDir.resolve("event_groups.tsv")));
            assertTrue(Files.exists(outputDir.resolve("genomic_links.tsv")));
            assertTrue(Files.exists(outputDir.resolve("circos_links.tsv")));
            String resultTrace = Files.readString(outputDir.resolve("result_trace.tsv"));
            assertTrue(resultTrace.contains("EVENT_GROUP_ID"), resultTrace);
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
            assertFalse(columnExists(connection, "GENOMIC_SEGMENTS", "EVENT_ID"));
            assertFalse(columnExists(connection, "GENOMIC_SEGMENTS", "GENOMIC_EVENT_GROUP_ID"));
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
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM genomic_events"));
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM genomic_event_groups"));
            assertFalse(columnExists(connection, "GENOMIC_SEGMENTS", "EVENT_ID"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM source_files"));
            assertEquals(1, count(connection, """
                    SELECT COUNT(*) FROM source_files
                    WHERE import_status = 'SUCCESS'
                    """));
            assertEquals(1, count(connection, """
                    SELECT COUNT(*) FROM sample_test_results
                    WHERE calling_method = 'NGS-derived' AND genome_build = 'GRCh37'
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
            assertTrue(multiEventSearch.contains("EVENT_GROUP_ID"), multiEventSearch);
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

            String genomeBuildSearch = new SearchService(connection).search(Map.of("genome-build", "GRCh37,GRCh38"));
            assertTrue(genomeBuildSearch.contains("GRCh37"), genomeBuildSearch);

            String unknownSearch = new SearchService(connection).search(Map.of("event-type", "NOT_A_REAL_EVENT"));
            assertTrue(unknownSearch.contains("Rows: 0"), unknownSearch);

            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE genomic_segments
                    SET annotations = annotations || '|extra'
                    WHERE segment_id = 1
                    """)) {
                ps.executeUpdate();
            }
            String mismatchSearch = new SearchService(connection).search(Map.of("annotation", "Gene=FCGR3A"));
            assertFalse(mismatchSearch.isBlank());
            assertTrue(mismatchSearch.contains("FCGR3A"), mismatchSearch);
            assertEquals(0, issueCount(connection, "Annotation Count Mismatch", "WARNING"));
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

            assertFalse(new SearchService(connection).search(Map.of("event-group", "NO_SUCH_GROUP")).isBlank());
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
    void importsAcghArrayCnvCallsWithArrayMeasurementsAsAnnotations() throws Exception {
        Path input = Files.createTempFile("acgh-array-cnv", ".cnv");
        Files.writeString(input, """
                Sample\tChr\tStart\tEnd\tAberration\tCopyNumber\tBuild\tProbeCount\tMeanLogRatio\tGene\tClassification
                ACGH001\t5\t70000000\t71000000\tLOSS\t1\thg38\t42\t-0.58\tMEF2C\tPathogenic
                """);
        try (Connection connection = Database.connect("jdbc:h2:mem:acgh_array;DB_CLOSE_DELAY=-1")) {
            Database.initialize(connection);
            CnvImportService importer = new CnvImportService(connection, new CnvParserFactory());
            var result = importer.importFile(input, null);

            assertTrue(result.success());
            assertEquals(1, result.segmentsInserted());
            assertEquals(1, count(connection, """
                    SELECT COUNT(*)
                    FROM genomic_segments gs
                    JOIN sample_test_results str ON str.sample_test_result_id = gs.sample_test_result_id
                    WHERE gs.chromosome = 'chr5'
                      AND gs.start_pos = 70000000
                      AND gs.stop_pos = 71000000
                      AND gs.event_type = 'LOSS'
                      AND gs.copy_number = 1
                      AND str.genome_build = 'GRCh38'
                      AND gs.genome_build = 'GRCh38'
                      AND str.calling_method = 'Array-derived'
                      AND str.annotation_names = 'ProbeCount|MeanLogRatio|Gene|Classification'
                      AND gs.annotations = '42|-0.58|MEF2C|Pathogenic'
                    """));
        }
    }

    @Test
    void importsSnpArrayCnvCallsWithArrayMeasurementsAsAnnotations() throws Exception {
        Path input = Files.createTempFile("snp-array-cnv", ".cnv");
        Files.writeString(input, """
                SampleID\tChromosome\tBP1\tBP2\tCNV_Type\tCN\tGenomeBuild\tNumProbes\tConfidence\tMeanBAF\tMeanLRR\tLOHScore\tGene
                SNP001\t7\t55000000\t56000000\tDUP\t3\tGRCh37\t88\tHIGH\t0.61\t0.35\t0.02\tSHH
                """);
        try (Connection connection = Database.connect("jdbc:h2:mem:snp_array;DB_CLOSE_DELAY=-1")) {
            Database.initialize(connection);
            CnvImportService importer = new CnvImportService(connection, new CnvParserFactory());
            var result = importer.importFile(input, null);

            assertTrue(result.success());
            assertEquals(1, result.segmentsInserted());
            assertEquals(1, count(connection, """
                    SELECT COUNT(*)
                    FROM genomic_segments gs
                    JOIN sample_test_results str ON str.sample_test_result_id = gs.sample_test_result_id
                    WHERE gs.chromosome = 'chr7'
                      AND gs.start_pos = 55000000
                      AND gs.stop_pos = 56000000
                      AND gs.event_type = 'DUP'
                      AND gs.copy_number = 3
                      AND gs.confidence = 'HIGH'
                      AND str.genome_build = 'GRCh37'
                      AND gs.genome_build = 'GRCh37'
                      AND str.calling_method = 'SNP-array-derived'
                      AND str.annotation_names = 'NumProbes|MeanBAF|MeanLRR|LOHScore|Gene'
                      AND gs.annotations = '88|0.61|0.35|0.02|SHH'
                    """));
        }
    }

    @Test
    void keepsBlankAnnotationPlaceholdersForSharedResultHeader() throws Exception {
        Path input = Files.createTempFile("blank-annotation-placeholders", ".cnv");
        Files.writeString(input, """
                Sample\tChr\tStart\tEnd\tAberration\tCopyNumber\tBuild\tGene\tClinical\tLumpy\tCNVNATOR
                ANN001\t7\t51109096\t63573985\tDEL\t1\thg38\tFCGR3A\tCDP2\t1\t1
                ANN001\t7\t70000000\t71000000\tDUP\t3\thg38\tSHH\t\t1\t
                """);
        try (Connection connection = Database.connect("jdbc:h2:mem:annotation_placeholders;DB_CLOSE_DELAY=-1")) {
            Database.initialize(connection);
            CnvImportService importer = new CnvImportService(connection, new CnvParserFactory());
            var result = importer.importFile(input, null);

            assertTrue(result.success());
            assertEquals(2, result.segmentsInserted());
            assertEquals(2, count(connection, """
                    SELECT COUNT(*)
                    FROM genomic_segments gs
                    JOIN sample_test_results str ON str.sample_test_result_id = gs.sample_test_result_id
                    WHERE str.annotation_names = 'Gene|Clinical|Lumpy|CNVNATOR'
                    """));
            assertEquals(1, count(connection, """
                    SELECT COUNT(*)
                    FROM genomic_segments
                    WHERE annotations = 'SHH||1|'
                    """));
            assertAnnotationAlignment(connection);
        }
    }

    @Test
    void bundledNgsFileMapsCoreFieldsAndPreservesNgsAnnotations() throws Exception {
        try (Connection connection = Database.connect("jdbc:h2:mem:ngs_annotations_acceptance;DB_CLOSE_DELAY=-1")) {
            Database.initialize(connection);
            CnvImportService importer = new CnvImportService(connection, new CnvParserFactory());
            var result = importer.importFile(
                    Path.of("data/25-54321WGS_05Nov2025_SWGS111_SV_DEL_DUP_Classify_Result.txt"),
                    null);

            assertTrue(result.success());
            assertEquals(452, result.segmentsInserted());
            assertEquals(452, count(connection, """
                    SELECT COUNT(*)
                    FROM genomic_segments gs
                    JOIN sample_test_results str ON str.sample_test_result_id = gs.sample_test_result_id
                    WHERE gs.chromosome IS NOT NULL
                      AND gs.start_pos IS NOT NULL
                      AND gs.stop_pos IS NOT NULL
                      AND gs.event_type IN ('DEL', 'DUP')
                      AND gs.copy_number IN (1, 3)
                      AND gs.genome_build = 'GRCh37'
                      AND str.calling_method = 'NGS-derived'
                    """));
            assertEquals(452, count(connection, """
                    SELECT COUNT(*)
                    FROM genomic_segments gs
                    JOIN sample_test_results str ON str.sample_test_result_id = gs.sample_test_result_id
                    WHERE str.annotation_names LIKE '%Gene%'
                      AND str.annotation_names LIKE '%Clinical%'
                      AND str.annotation_names LIKE '%Lumpy%'
                      AND str.annotation_names LIKE '%CNVNATOR%'
                      AND str.annotation_names LIKE '%gnomAD_version%'
                    """));
            assertNoDedicatedAnnotationNames(connection);
            assertAnnotationAlignment(connection);
        }
    }

    @Test
    void bundledArrayFileMapsCoreFieldsAndPreservesArrayAnnotations() throws Exception {
        try (Connection connection = Database.connect("jdbc:h2:mem:array_annotations_acceptance;DB_CLOSE_DELAY=-1")) {
            Database.initialize(connection);
            CnvImportService importer = new CnvImportService(connection, new CnvParserFactory());
            var result = importer.importFile(Path.of("data/array_derived_100.cnv"), null);

            assertTrue(result.success());
            assertEquals(100, result.segmentsInserted());
            assertEquals(100, count(connection, """
                    SELECT COUNT(*)
                    FROM genomic_segments gs
                    JOIN sample_test_results str ON str.sample_test_result_id = gs.sample_test_result_id
                    WHERE gs.genome_build IN ('GRCh37', 'GRCh38')
                      AND gs.raw_iscn IS NULL
                      AND str.calling_method = 'Array-derived'
                    """));
            assertFalse(columnExists(connection, "GENOMIC_SEGMENTS", "ARRAY_SCORE"));
            assertFalse(columnExists(connection, "GENOMIC_SEGMENTS", "NUMBER_OF_SITES"));
            assertTrue(columnExists(connection, "GENOMIC_SEGMENTS", "CONFIDENCE"));
            assertNoDedicatedAnnotationNames(connection);
            assertAnnotationAlignment(connection);
        }
    }

    @Test
    void storesNgsExtraFieldsInSegmentAnnotations() throws Exception {
        Path input = Files.createTempFile("ngs-segment-annotations", ".cnv");
        Files.writeString(input, """
                Sample\tChr\tStart\tEnd\tSV_Type\thg_version\tGene\tLumpy\tCNVNATOR\tgnomAD_count\tDGV_pop_percent
                SIM001\tchr7\t51109096\t63573985\tDEL\thg38\tFCGR3A\t1\t1\t12\t0.37
                """);
        try (Connection connection = Database.connect("jdbc:h2:mem:ngs_segment_annotations;DB_CLOSE_DELAY=-1")) {
            Database.initialize(connection);
            CnvImportService importer = new CnvImportService(connection, new CnvParserFactory());
            var result = importer.importFile(input, null);

            assertTrue(result.success());
            assertEquals(1, result.segmentsInserted());
            assertEquals(1, count(connection, """
                    SELECT COUNT(*)
                    FROM genomic_segments
                    WHERE chromosome = 'chr7'
                      AND start_pos = 51109096
                      AND stop_pos = 63573985
                      AND event_type = 'DEL'
                      AND copy_number = 1
                      AND genome_build = 'GRCh38'
                    """));
            assertEquals(5, count(connection, "SELECT COUNT(*) FROM segment_annotations"));
            assertEquals(1, count(connection, """
                    SELECT COUNT(*)
                    FROM genomic_segments gs
                    JOIN segment_annotations sa ON sa.segment_id = gs.segment_id
                    WHERE sa.annotation_name = 'Gene'
                      AND sa.text_value = 'FCGR3A'
                    """));
            assertEquals(1, count(connection, """
                    SELECT COUNT(*)
                    FROM genomic_segments gs
                    JOIN segment_annotations sa ON sa.segment_id = gs.segment_id
                    WHERE sa.annotation_name = 'Lumpy'
                      AND sa.numeric_value = 1
                    """));
            assertEquals(1, count(connection, """
                    SELECT COUNT(*)
                    FROM segment_annotations
                    WHERE annotation_name = 'DGV_pop_percent'
                      AND numeric_value = 0.37
                      AND value_type = 'NUMBER'
                    """));
        }
    }

    @Test
    void storesArrayExtraFieldsInSegmentAnnotationsAndSkipsBlanks() throws Exception {
        Path input = Files.createTempFile("array-segment-annotations", ".cnv");
        Files.writeString(input, """
                SampleID\tChromosome\tBP1\tBP2\tCNV_Type\tCN\tGenomeBuild\tprobe_count\tLRR\tBAF\tarray_platform\tCNVNATOR
                ARR001\tchr2\t1000\t2000\tDUP\t3\thg19\t501\t0.22\t\tCytoScan\t
                """);
        try (Connection connection = Database.connect("jdbc:h2:mem:array_segment_annotations;DB_CLOSE_DELAY=-1")) {
            Database.initialize(connection);
            CnvImportService importer = new CnvImportService(connection, new CnvParserFactory());
            var result = importer.importFile(input, null);

            assertTrue(result.success());
            assertEquals(1, result.segmentsInserted());
            assertEquals(3, count(connection, "SELECT COUNT(*) FROM segment_annotations"));
            assertEquals(0, count(connection, """
                    SELECT COUNT(*)
                    FROM segment_annotations
                    WHERE annotation_name IN ('BAF', 'CNVNATOR')
                    """));
            assertEquals(1, count(connection, """
                    SELECT COUNT(*)
                    FROM genomic_segments gs
                    JOIN segment_annotations sa ON sa.segment_id = gs.segment_id
                    WHERE sa.annotation_name = 'probe_count'
                      AND sa.numeric_value > 400
                    """));
            String sizeSearch = new SearchService(connection).search(Map.of("cnv-size-min", "1001"));
            assertTrue(sizeSearch.contains("ARR001"), sizeSearch);
            String annotationSearch = new SearchService(connection).search(Map.of("annotation", "array_platform=CytoScan"));
            assertTrue(annotationSearch.contains("CytoScan"), annotationSearch);
        }
    }

    @Test
    void duplicateFileImportsAreWarnedButNotBlocked() throws Exception {
        Path input = Files.createTempFile("duplicate-cnv-import", ".cnv");
        Files.writeString(input, """
                Sample\tChr\tStart\tEnd\tAberration\tCopyNumber\tBuild\tGene
                DUP001\tchr1\t1000\t2000\tDEL\t1\thg38\tSHH
                """);
        try (Connection connection = Database.connect("jdbc:h2:mem:duplicate_import_warning;DB_CLOSE_DELAY=-1")) {
            Database.initialize(connection);
            CnvImportService importer = new CnvImportService(connection, new CnvParserFactory());

            var first = importer.importFile(input, null);
            var second = importer.importFile(input, null);

            assertTrue(first.success());
            assertTrue(second.success());
            assertEquals(2, count(connection, "SELECT COUNT(*) FROM source_files"));
            assertEquals(1, issueCount(connection, "Duplicate Source File", "WARNING"));
        }
    }

    @Test
    void rejectsArrayCnvRowsMissingRequiredIntervalFields() throws Exception {
        Path input = Files.createTempFile("missing-array-fields", ".cnv");
        Files.writeString(input, """
                Sample\tChr\tEnd\tAberration\tGenomeBuild\tProbeCount
                MISS001\t5\t71000000\tDEL\thg38\t42
                """);
        try (Connection connection = Database.connect("jdbc:h2:mem:array_missing_fields;DB_CLOSE_DELAY=-1")) {
            Database.initialize(connection);
            CnvImportService importer = new CnvImportService(connection, new CnvParserFactory());
            var result = importer.importFile(input, null);

            assertTrue(result.success());
            assertEquals(0, result.segmentsInserted());
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM genomic_segments"));
            assertTrue(issueCount(connection, "Missing Required Column", "ERROR") > 0
                    || issueCount(connection, "Missing Start Position", "ERROR") > 0);
        }
    }

    @Test
    void rejectsRawArrayProbeEvidenceFilesAsUnsupportedForCnvImport() throws Exception {
        Path input = Files.createTempFile("raw-array-probes", ".tsv");
        Files.writeString(input, """
                ProbeName\tSystematicName\tLogRatio\tPValueLogRatio\tgProcessedSignal\trProcessedSignal
                probe_1\tA_01\t0.12\t0.05\t1234\t1299
                """);
        try (Connection connection = Database.connect("jdbc:h2:mem:raw_array_evidence;DB_CLOSE_DELAY=-1")) {
            Database.initialize(connection);
            CnvImportService importer = new CnvImportService(connection, new CnvParserFactory());
            var result = importer.importFile(input, null);

            assertTrue(result.success());
            assertEquals(0, result.segmentsInserted());
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM genomic_segments"));
            assertEquals(1, issueCount(connection, "Unsupported Array Evidence File", "ERROR"));
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
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM genomic_events"));
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM genomic_event_groups"));
            assertEquals(100, count(connection, "SELECT COUNT(*) FROM genomic_segments"));
            assertEquals(50, count(connection, "SELECT COUNT(*) FROM genomic_links"));
            assertEquals(100, count(connection, """
                    SELECT COUNT(*) FROM genomic_segments
                    WHERE event_group_id IS NOT NULL
                    """));
            assertEquals(50, count(connection, """
                    SELECT COUNT(*) FROM genomic_links
                    WHERE event_group_id IS NOT NULL
                    """));
            assertFalse(columnExists(connection, "GENOMIC_SEGMENTS", "EVENT_ID"));
            assertEquals(0, count(connection, """
                    SELECT COUNT(*) FROM genomic_links
                    WHERE event_id IS NOT NULL
                    """));
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
            assertTrue(Files.readString(outputDir.resolve("genomic_links.tsv"))
                    .contains("TXG001"));
            String circosLinks = Files.readString(outputDir.resolve("circos_links.tsv"));
            assertTrue(circosLinks.contains("chr22"));
            assertTrue(circosLinks.contains("SOURCE_CHROMOSOME"), circosLinks);
            assertTrue(circosLinks.contains("TARGET_CHROMOSOME"), circosLinks);
            assertFalse(circosLinks.contains("EVENT_ID"), circosLinks);
        }
    }

    @Test
    void groupsMixedSimpleAndComplexEventsForPhase27() throws Exception {
        try (Connection connection = Database.connect("jdbc:h2:mem:phase27_event_groups;DB_CLOSE_DELAY=-1")) {
            Database.initialize(connection);
            CnvImportService importer = new CnvImportService(connection, new CnvParserFactory());
            var result = importer.importFile(Path.of("data/realistic_cnv_mixed_events_import.cnv"), null);

            assertTrue(result.success());
            assertEquals(12, result.recordsSeen());
            assertEquals(12, result.segmentsInserted());
            assertEquals(12, count(connection, "SELECT COUNT(*) FROM genomic_segments"));
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM genomic_event_groups"));
            assertEquals(4, count(connection, """
                    SELECT COUNT(*) FROM genomic_segments
                    WHERE event_group_id IS NULL
                    """));
            assertEquals(1, count(connection, """
                    SELECT COUNT(*)
                    FROM (
                        SELECT event_group_id
                        FROM genomic_segments
                        WHERE event_group_id = 'TXG001'
                        GROUP BY event_group_id
                        HAVING COUNT(*) = 2
                    )
                    """));
            assertEquals(1, count(connection, """
                    SELECT COUNT(*)
                    FROM (
                        SELECT event_group_id
                        FROM genomic_segments
                        WHERE event_group_id = 'RING001'
                          AND event_type = 'RING'
                        GROUP BY event_group_id
                    )
                    """));
            assertEquals(1, count(connection, """
                    SELECT COUNT(*)
                    FROM genomic_links
                    WHERE link_type = 'RING'
                    """));
            assertEquals(1, count(connection, """
                    SELECT COUNT(*)
                    FROM (
                        SELECT event_group_id
                        FROM genomic_segments
                        WHERE event_group_id = 'TXG001'
                          AND chromosome IN ('chr9', 'chr22')
                        GROUP BY event_group_id
                        HAVING COUNT(DISTINCT chromosome) = 2
                    )
                    """));

            String groupSearch = new SearchService(connection).search(Map.of("event-group", "TXG001"));
            assertTrue(groupSearch.contains("TXG001"), groupSearch);
            assertTrue(groupSearch.contains("chr9"), groupSearch);
            assertTrue(groupSearch.contains("chr22"), groupSearch);
            assertTrue(groupSearch.contains("Rows: 2"), groupSearch);

            Path outputDir = Files.createTempDirectory("phase27-output");
            VerificationReport report = new VerificationService(connection, outputDir)
                    .verify(true, List.of(result));
            assertTrue(report.schema().passed(), new VerificationService(connection, outputDir)
                    .terminalSummary(report));
            assertTrue(report.dataIntegrity().passed(), new VerificationService(connection, outputDir)
                    .terminalSummary(report));
            String genomicLinks = Files.readString(outputDir.resolve("genomic_links.tsv"));
            assertTrue(genomicLinks.contains("TXG001"), genomicLinks);
            assertTrue(genomicLinks.contains("INVG001"), genomicLinks);
            assertTrue(genomicLinks.contains("RING001"), genomicLinks);
        }
    }

    @Test
    void representsCnvsStructuralEventsAndLinksForPhase27() throws Exception {
        Path input = Path.of("data/phase27_finding_model.cnv");

        try (Connection connection = Database.connect("jdbc:h2:mem:phase27_finding_model;DB_CLOSE_DELAY=-1")) {
            Database.initialize(connection);
            CnvImportService importer = new CnvImportService(connection, new CnvParserFactory());
            var result = importer.importFile(input, null);

            assertTrue(result.success());
            assertEquals(22, result.recordsSeen());
            assertEquals(22, result.segmentsInserted());
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM genomic_events"));
            assertEquals(22, count(connection, "SELECT COUNT(*) FROM genomic_segments"));
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM genomic_event_groups"));
            assertEquals(7, count(connection, "SELECT COUNT(*) FROM genomic_links"));
            assertEquals(8, count(connection, """
                    SELECT COUNT(*)
                    FROM genomic_segments
                    WHERE event_type IN ('DEL', 'DUP', 'GAIN', 'LOSS', 'AMP', 'ROH', 'TRISOMY', 'MONOSOMY')
                      AND event_group_id IS NULL
                    """));
            assertEquals(0, count(connection, """
                    SELECT COUNT(*)
                    FROM genomic_segments
                    WHERE event_type IN ('DEL', 'DUP', 'GAIN', 'LOSS', 'AMP', 'ROH', 'TRISOMY', 'MONOSOMY')
                      AND event_group_id IS NOT NULL
                    """));
            assertEquals(0, count(connection, """
                    SELECT COUNT(*)
                    FROM genomic_segments
                    WHERE event_type IN ('TRANS', 'INV', 'INS', 'DER', 'DIC', 'RING', 'COMPLEX')
                      AND event_group_id IS NULL
                    """));
            assertEquals(7, count(connection, """
                    SELECT COUNT(*)
                    FROM (
                        SELECT event_group_id
                        FROM genomic_segments
                        WHERE event_group_id IS NOT NULL
                        GROUP BY event_group_id
                        HAVING COUNT(*) = 2
                    )
                    """));
            assertEquals(0, count(connection, """
                    SELECT COUNT(*)
                    FROM genomic_links gl
                    JOIN genomic_segments src ON src.segment_id = gl.source_segment_id
                    JOIN genomic_segments tgt ON tgt.segment_id = gl.target_segment_id
                    WHERE src.event_type IN ('DEL', 'DUP', 'GAIN', 'LOSS', 'AMP', 'ROH', 'TRISOMY', 'MONOSOMY')
                       OR tgt.event_type IN ('DEL', 'DUP', 'GAIN', 'LOSS', 'AMP', 'ROH', 'TRISOMY', 'MONOSOMY')
                    """));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM genomic_links WHERE link_type = 'TRANSLOCATION'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM genomic_links WHERE link_type = 'INVERSION'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM genomic_links WHERE link_type = 'INSERTION'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM genomic_links WHERE link_type = 'DERIVATIVE'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM genomic_links WHERE link_type = 'DICENTRIC'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM genomic_links WHERE link_type = 'RING'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM genomic_links WHERE link_type = 'COMPLEX'"));
        }
    }

    @Test
    void normalizesAndRejectsGenomeBuildsForPhase28() throws Exception {
        Path tempDir = Files.createTempDirectory("phase28-genome-builds");
        Path input = tempDir.resolve("genome_build_aliases.cnv");
        Files.writeString(input, String.join("\n",
                "sample_accession_id\tchromosome\tstart_pos\tstop_pos\tevent_type\tcopy_number\tgenome_build\traw_iscn",
                "B37A\tchr1\t10\t20\tDEL\t1\tGRCh37\t",
                "B37B\tchr1\t30\t40\tDEL\t1\thg19\t",
                "B37C\tchr1\t50\t60\tDEL\t1\tBuild 37\t",
                "B37D\tchr1\t70\t80\tDEL\t1\tb37\t",
                "B38A\tchr2\t10\t20\tDUP\t3\tGRCh38\t",
                "B38B\tchr2\t30\t40\tDUP\t3\thg38\t",
                "B38C\tchr2\t50\t60\tDUP\t3\tBuild 38\t",
                "B38D\tchr2\t70\t80\tDUP\t3\tb38\t",
                "T2TA\tchr3\t10\t20\tGAIN\t3\tT2T\t",
                "T2TB\tchr3\t30\t40\tGAIN\t3\tCHM13\t",
                "T2TC\tchr3\t50\t60\tGAIN\t3\tT2T-CHM13\t",
                "T2TD\tchr3\t70\t80\tGAIN\t3\tCHM13v2\t",
                "T2TE\tchr3\t90\t100\tGAIN\t3\tCHM13v2.0\t",
                "BAD1\tchr4\t10\t20\tLOSS\t1\tbanana38\t",
                "BAD2\tchr4\t30\t40\tLOSS\t1\thg83\t",
                "BAD3\tchr4\t50\t60\tLOSS\t1\tbuild99\t",
                "BAD4\tchr4\t70\t80\tLOSS\t1\tunknown\t",
                "BAD5\tchr4\t90\t100\tLOSS\t1\trandom_text\t",
                "MISS1\tchr4\t110\t120\tLOSS\t1\t\t",
                ""));

        try (Connection connection = Database.connect("jdbc:h2:mem:phase28_genome_builds;DB_CLOSE_DELAY=-1")) {
            Database.initialize(connection);
            CnvImportService importer = new CnvImportService(connection, new CnvParserFactory());
            var result = importer.importFile(input, null);

            assertTrue(result.success());
            assertEquals(19, result.recordsSeen());
            assertEquals(13, result.segmentsInserted());
            assertEquals(13, count(connection, "SELECT COUNT(*) FROM genomic_segments"));
            assertEquals(4, count(connection, "SELECT COUNT(*) FROM sample_test_results WHERE genome_build = 'GRCh37'"));
            assertEquals(4, count(connection, "SELECT COUNT(*) FROM sample_test_results WHERE genome_build = 'GRCh38'"));
            assertEquals(5, count(connection, "SELECT COUNT(*) FROM sample_test_results WHERE genome_build = 'T2T-CHM13'"));
            assertEquals(0, count(connection, """
                    SELECT COUNT(*)
                    FROM sample_test_results
                    WHERE genome_build NOT IN ('GRCh37', 'GRCh38', 'T2T-CHM13', 'NCBI36')
                    """));
            assertEquals(5, issueCount(connection, "Invalid Genome Build", "ERROR"));
            assertEquals(1, issueCount(connection, "Missing Genome Build", "ERROR"));
            assertEquals(0, count(connection, """
                    SELECT COUNT(*)
                    FROM genomic_segments gs
                    JOIN sample_test_results str ON str.sample_test_result_id = gs.sample_test_result_id
                    WHERE str.genome_build IN ('hg19', 'hg38', 'Build 37', 'Build 38', 'T2T', 'CHM13', 'unknown')
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
            assertEquals(12, result.issuesInserted());
            assertEquals(6, count(connection, "SELECT COUNT(*) FROM genomic_segments"));
            assertEquals(7, count(connection, "SELECT COUNT(*) FROM validation_issues WHERE severity = 'ERROR'"));
            assertEquals(5, count(connection, "SELECT COUNT(*) FROM validation_issues WHERE severity = 'WARNING'"));

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
            assertEquals(1, issueCount(connection, "Incomplete Breakpoint Event Group", "WARNING"));
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

    private boolean columnExists(Connection connection, String table, String column) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = ? AND COLUMN_NAME = ?
                """)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (var rs = ps.executeQuery()) {
                return rs.next() && rs.getLong(1) > 0;
            }
        }
    }

    private void assertNoDedicatedAnnotationNames(Connection connection) throws Exception {
        assertEquals(0, count(connection, """
                SELECT COUNT(*)
                FROM sample_test_results
                WHERE annotation_names LIKE '%chromosome%'
                   OR annotation_names LIKE '%Chr%'
                   OR annotation_names LIKE '%Start%'
                   OR annotation_names LIKE '%End%'
                   OR annotation_names LIKE '%SV_Type%'
                   OR annotation_names LIKE '%copy_number%'
                   OR annotation_names LIKE '%CopyNumber%'
                   OR annotation_names LIKE '%GenomeBuild%'
                   OR annotation_names LIKE '%hg_version%'
                   OR annotation_names LIKE '%Confidence%'
                """));
    }

    private void assertAnnotationAlignment(Connection connection) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT str.annotation_names, gs.annotations
                FROM genomic_segments gs
                JOIN sample_test_results str ON str.sample_test_result_id = gs.sample_test_result_id
                WHERE COALESCE(str.annotation_names, '') <> ''
                   OR COALESCE(gs.annotations, '') <> ''
                """)) {
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    String names = rs.getString("annotation_names");
                    String values = rs.getString("annotations");
                    int nameCount = splitAnnotationParts(names).length;
                    int valueCount = splitAnnotationParts(values).length;
                    assertEquals(nameCount, valueCount, "annotation_names and annotations must align");
                }
            }
        }
    }

    private String[] splitAnnotationParts(String value) {
        if (value == null || value.isEmpty()) {
            return new String[0];
        }
        String delimiter = value.contains("|") ? "\\|" : ";";
        return value.split(delimiter, -1);
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
