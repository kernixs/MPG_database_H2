package org.mpgdatabase.visualization.circos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class CircosDemoDataSeeder {
    private static final String PIPELINE_NAME = "Synthetic Circos Bulk Demo";
    private static final String PIPELINE_VERSION = "v1";
    private static final String SOURCE_FILE = "synthetic_circos_bulk_demo.cnv";
    private static final String SOURCE_PATH = "synthetic://circos_bulk_demo";
    private static final String GENOME_BUILD = "GRCh38";
    private static final String[] CHROMOSOMES = {
            "chr1", "chr2", "chr3", "chr4", "chr5", "chr6", "chr7", "chr8",
            "chr9", "chr10", "chr11", "chr12", "chr13", "chr14", "chr15", "chr16",
            "chr17", "chr18", "chr19", "chr20", "chr21", "chr22"
    };
    private static final long[] CHROMOSOME_LENGTHS = {
            248_956_422L, 242_193_529L, 198_295_559L, 190_214_555L, 181_538_259L, 170_805_979L,
            159_345_973L, 145_138_636L, 138_394_717L, 133_797_422L, 135_086_622L, 133_275_309L,
            114_364_328L, 107_043_718L, 101_991_189L, 90_338_345L, 83_257_441L, 80_373_285L,
            58_617_616L, 64_444_167L, 46_709_983L, 50_818_468L
    };

    private final Connection connection;

    public CircosDemoDataSeeder(Connection connection) {
        this.connection = connection;
    }

    public SeedSummary seed() throws SQLException {
        long pipelineId = ensurePipeline();
        long sourceFileId = ensureSourceFile(pipelineId);
        long wgsProtocolId = ensureLabProtocol("WGS", "Synthetic Demo");
        long arrayProtocolId = ensureLabProtocol("Array", "Synthetic Demo");

        int individuals = 0;
        int accessions = 0;
        int tests = 0;
        int results = 0;
        int segments = 0;
        int links = 0;
        List<String> mrns = new ArrayList<>();

        for (int patient = 1; patient <= 10; patient++) {
            String mrn = "MRN-CIRCOS-TEST-%03d".formatted(patient);
            mrns.add(mrn);
            long individualId = ensureIndividual(mrn, "IND-CIRCOS-TEST-%03d".formatted(patient));
            individuals++;

            for (int accessionOrdinal = 1; accessionOrdinal <= 3; accessionOrdinal++) {
                String accession = "CIRCOS_TEST_%03d_%s".formatted(patient, accessionSuffix(accessionOrdinal));
                long accessionId = ensureSampleAccession(accession, individualId, dnaSource(accessionOrdinal));
                accessions++;

                for (int testOrdinal = 1; testOrdinal <= 2; testOrdinal++) {
                    long labProtocolId = testOrdinal == 1 ? wgsProtocolId : arrayProtocolId;
                    String testType = testOrdinal == 1 ? "WGS CNV/SV" : "Array CNV/SV";
                    long sampleTestId = ensureSampleTest(accessionId, labProtocolId, testType);
                    tests++;
                    long resultId = ensureSampleTestResult(
                            sampleTestId,
                            pipelineId,
                            sourceFileId,
                            testOrdinal == 1 ? "Synthetic WGS Circos Demo" : "Synthetic Array Circos Demo");
                    results++;
                    if (alreadySeeded(resultId)) {
                        continue;
                    }
                    SeededEvents seeded = seedResult(resultId, patient, accessionOrdinal, testOrdinal);
                    segments += seeded.segments();
                    links += seeded.links();
                }
            }
        }

        return new SeedSummary(individuals, accessions, tests, results, segments, links, mrns);
    }

    private SeededEvents seedResult(long resultId, int patient, int accession, int test) throws SQLException {
        int segments = 0;
        int links = 0;
        for (int i = 1; i <= 20; i++) {
            insertCnvSegment(resultId, patient, accession, test, i, true);
            segments++;
        }
        for (int i = 1; i <= 20; i++) {
            insertCnvSegment(resultId, patient, accession, test, i, false);
            segments++;
        }
        for (int i = 1; i <= 20; i++) {
            long sourceSegmentId = insertTranslocationSegment(resultId, patient, accession, test, i, true);
            long targetSegmentId = insertTranslocationSegment(resultId, patient, accession, test, i, false);
            insertTranslocationLink(resultId, patient, accession, test, i, sourceSegmentId, targetSegmentId);
            segments += 2;
            links++;
        }
        return new SeededEvents(segments, links);
    }

    private void insertCnvSegment(long resultId, int patient, int accession, int test, int ordinal, boolean gain)
            throws SQLException {
        int chrIndex = Math.floorMod(patient * 3 + accession * 5 + test * 7 + ordinal * 2 + (gain ? 0 : 11),
                CHROMOSOMES.length);
        long length = gain
                ? 1_500_000L + ordinal * 175_000L
                : 2_000_000L + ordinal * 225_000L;
        long start = coordinate(chrIndex, patient, accession, test, ordinal, gain ? 13 : 29, length);
        String eventType = gain
                ? switch (ordinal % 3) {
                    case 0 -> "AMP";
                    case 1 -> "GAIN";
                    default -> "DUP";
                }
                : ordinal % 2 == 0 ? "DEL" : "LOSS";
        int copyNumber = switch (eventType) {
            case "AMP" -> 5;
            case "GAIN", "DUP" -> 3;
            default -> 1;
        };
        String group = "BULK_P%03d_A%d_T%d_%s_%02d".formatted(
                patient, accession, test, gain ? "GAIN" : "LOSS", ordinal);
        insertSegment(
                resultId,
                group,
                CHROMOSOMES[chrIndex],
                start,
                start + length,
                eventType,
                copyNumber,
                confidence(ordinal),
                null,
                "Synthetic %s segment %s:%d-%d".formatted(eventType, CHROMOSOMES[chrIndex], start, start + length),
                "synthetic_circos_bulk_demo");
    }

    private long insertTranslocationSegment(long resultId, int patient, int accession, int test, int ordinal, boolean source)
            throws SQLException {
        int chrIndex = Math.floorMod(patient + accession * 4 + test * 6 + ordinal * (source ? 3 : 5)
                + (source ? 0 : 9), CHROMOSOMES.length);
        long length = 2_000L;
        long start = coordinate(chrIndex, patient, accession, test, ordinal, source ? 41 : 67, length);
        String group = "BULK_P%03d_A%d_T%d_TX_%02d".formatted(patient, accession, test, ordinal);
        return insertSegment(
                resultId,
                group,
                CHROMOSOMES[chrIndex],
                start,
                start + length,
                "TRANS",
                2,
                confidence(ordinal),
                "synthetic t(%s;%s)".formatted(shortChr(CHROMOSOMES[chrIndex]), source ? "?" : "*"),
                "Synthetic translocation %s breakpoint %s:%d-%d".formatted(
                        source ? "source" : "target", CHROMOSOMES[chrIndex], start, start + length),
                "synthetic_circos_bulk_demo");
    }

    private void insertTranslocationLink(
            long resultId,
            int patient,
            int accession,
            int test,
            int ordinal,
            long sourceSegmentId,
            long targetSegmentId
    ) throws SQLException {
        String group = "BULK_P%03d_A%d_T%d_TX_%02d".formatted(patient, accession, test, ordinal);
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO genomic_links
                    (genomic_event_group_id, event_id, event_group_id, source_segment_id, target_segment_id,
                     link_type, orientation, evidence, confidence)
                VALUES (NULL, NULL, ?, ?, ?, 'TRANSLOCATION', NULL, ?, ?)
                """)) {
            ps.setString(1, group);
            ps.setLong(2, sourceSegmentId);
            ps.setLong(3, targetSegmentId);
            ps.setString(4, "synthetic translocation pair " + group);
            ps.setString(5, confidence(ordinal));
            ps.executeUpdate();
        }
    }

    private long insertSegment(
            long resultId,
            String eventGroupId,
            String chromosome,
            long start,
            long stop,
            String eventType,
            int copyNumber,
            String confidence,
            String rawIscn,
            String rawSegmentText,
            String annotations
    ) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO genomic_segments
                    (event_group_id, sample_test_result_id, karyotype_id, chromosome, start_pos, stop_pos,
                     event_size_bp, event_type, copy_number, genome_build, confidence, raw_segment_text, ambiguity_flag)
                VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, eventGroupId);
            ps.setLong(2, resultId);
            ps.setString(3, chromosome);
            ps.setLong(4, start);
            ps.setLong(5, stop);
            ps.setLong(6, stop >= start ? stop - start + 1 : 0);
            ps.setString(7, eventType);
            ps.setInt(8, copyNumber);
            ps.setString(9, GENOME_BUILD);
            ps.setString(10, confidence);
            ps.setString(11, segmentText(rawIscn, rawSegmentText, annotations));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("No generated segment_id returned.");
                }
                return keys.getLong(1);
            }
        }
    }

    private long ensureIndividual(String mrn, String externalIdentifier) throws SQLException {
        Long existing = findId("SELECT individual_id FROM individuals WHERE mrn = ?", mrn);
        if (existing != null) {
            return existing;
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO individuals (mrn, external_identifier)
                VALUES (?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, mrn);
            ps.setString(2, externalIdentifier);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    private long ensureSampleAccession(String accession, long individualId, String dnaSource) throws SQLException {
        Long existing = findId("SELECT sample_accession_id FROM sample_accessions WHERE accession_identifier = ?", accession);
        if (existing != null) {
            return existing;
        }
        long sampleId = ensureSample(accession, individualId, dnaSource);
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO sample_accessions (accession_identifier, sample_id, accession_dna_source)
                VALUES (?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, accession);
            ps.setLong(2, sampleId);
            ps.setString(3, dnaSource);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    private long ensureSample(String accession, long individualId, String dnaSource) throws SQLException {
        Long existing = findId("SELECT sample_id FROM samples WHERE sample_identifier = ?", accession);
        if (existing != null) {
            return existing;
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO samples (individual_id, sample_identifier, dna_source)
                VALUES (?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, individualId);
            ps.setString(2, accession);
            ps.setString(3, dnaSource);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    private long ensureLabProtocol(String technology, String manufacturer) throws SQLException {
        Long existing = findId("""
                SELECT lab_protocol_id
                FROM lab_protocols
                WHERE technology = ?
                  AND COALESCE(manufacturer, '') = COALESCE(?, '')
                """, technology, manufacturer);
        if (existing != null) {
            return existing;
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO lab_protocols (technology, manufacturer, miscellaneous)
                VALUES (?, ?, 'Synthetic Circos demo testing')
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, technology);
            ps.setString(2, manufacturer);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    private long ensurePipeline() throws SQLException {
        Long existing = findId("""
                SELECT pipeline_id
                FROM pipelines
                WHERE software_name = ?
                  AND software_version = ?
                """, PIPELINE_NAME, PIPELINE_VERSION);
        if (existing != null) {
            return existing;
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO pipelines (software_name, software_version, settings_used)
                VALUES (?, ?, 'bulk_demo_seed')
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, PIPELINE_NAME);
            ps.setString(2, PIPELINE_VERSION);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    private long ensureSourceFile(long pipelineId) throws SQLException {
        Long existing = findId("SELECT source_file_id FROM source_files WHERE file_path = ?", SOURCE_PATH);
        if (existing != null) {
            return existing;
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO source_files (file_name, file_path, pipeline_id, import_status, row_count, notes)
                VALUES (?, ?, ?, 'IMPORTED', 4800, 'Synthetic bulk Circos demo data')
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, SOURCE_FILE);
            ps.setString(2, SOURCE_PATH);
            ps.setLong(3, pipelineId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    private long ensureSampleTest(long accessionId, long labProtocolId, String testType) throws SQLException {
        Long existing = findId("""
                SELECT sample_test_id
                FROM sample_tests
                WHERE sample_accession_id = ?
                  AND lab_protocol_id = ?
                  AND test_type = ?
                """, accessionId, labProtocolId, testType);
        if (existing != null) {
            return existing;
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO sample_tests (sample_accession_id, lab_protocol_id, test_type)
                VALUES (?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, accessionId);
            ps.setLong(2, labProtocolId);
            ps.setString(3, testType);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    private long ensureSampleTestResult(
            long sampleTestId,
            long pipelineId,
            long sourceFileId,
            String callingMethod
    ) throws SQLException {
        Long existing = findId("""
                SELECT sample_test_result_id
                FROM sample_test_results
                WHERE sample_test_id = ?
                  AND pipeline_id = ?
                  AND source_file_id = ?
                  AND calling_method = ?
                """, sampleTestId, pipelineId, sourceFileId, callingMethod);
        if (existing != null) {
            return existing;
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO sample_test_results
                    (sample_test_id, pipeline_id, source_file_id, calling_method)
                VALUES (?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, sampleTestId);
            ps.setLong(2, pipelineId);
            ps.setLong(3, sourceFileId);
            ps.setString(4, callingMethod);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    private boolean alreadySeeded(long resultId) throws SQLException {
        Long count = findId("""
                SELECT COUNT(*)
                FROM genomic_segments
                WHERE sample_test_result_id = ?
                  AND raw_segment_text LIKE '%synthetic_circos_bulk_demo%'
                """, resultId);
        return count != null && count > 0;
    }

    private String segmentText(String rawIscn, String rawSegmentText, String marker) {
        String base = rawSegmentText != null ? rawSegmentText : rawIscn;
        if (marker == null || marker.isBlank()) {
            return base;
        }
        if (base == null || base.isBlank()) {
            return marker;
        }
        return base + " | " + marker;
    }

    private Long findId(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                return null;
            }
        }
    }

    private long generatedId(PreparedStatement ps) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("No generated id returned.");
            }
            return keys.getLong(1);
        }
    }

    private long coordinate(int chrIndex, int patient, int accession, int test, int ordinal, int salt, long span) {
        long chromosomeLength = CHROMOSOME_LENGTHS[chrIndex];
        long available = Math.max(1_000_000L, chromosomeLength - span - 1_000_000L);
        long seed = patient * 9_176_311L + accession * 1_048_573L + test * 262_147L + ordinal * 65_537L + salt;
        return 500_000L + Math.floorMod(seed * 9_973L, available);
    }

    private String confidence(int ordinal) {
        return switch (ordinal % 3) {
            case 0 -> "HIGH";
            case 1 -> "MEDIUM";
            default -> "LOW";
        };
    }

    private String accessionSuffix(int accessionOrdinal) {
        return switch (accessionOrdinal) {
            case 1 -> "TUMOR_A";
            case 2 -> "TUMOR_B";
            default -> "BLOOD";
        };
    }

    private String dnaSource(int accessionOrdinal) {
        return switch (accessionOrdinal) {
            case 1 -> "Tumor tissue";
            case 2 -> "Metastatic tumor tissue";
            default -> "Peripheral blood";
        };
    }

    private String shortChr(String chromosome) {
        return chromosome.startsWith("chr") ? chromosome.substring(3) : chromosome;
    }

    private record SeededEvents(int segments, int links) {
    }

    public record SeedSummary(
            int individuals,
            int accessions,
            int tests,
            int results,
            int segments,
            int links,
            List<String> mrns
    ) {
    }
}
