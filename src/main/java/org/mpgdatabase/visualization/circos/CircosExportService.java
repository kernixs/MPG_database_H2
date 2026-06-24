package org.mpgdatabase.visualization.circos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CircosExportService {
    private static final DateTimeFormatter LABEL_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final Connection connection;
    private final CircosConfig config;
    private final CircosValidationService validation;

    public CircosExportService(Connection connection, CircosConfig config) {
        this.connection = connection;
        this.config = config;
        this.validation = new CircosValidationService();
    }

    public CircosExportResult export(long sampleTestResultId) throws SQLException, IOException {
        return export(List.of(sampleTestResultId));
    }

    public CircosExportResult export(List<Long> sampleTestResultIds) throws SQLException, IOException {
        if (sampleTestResultIds == null || sampleTestResultIds.isEmpty()) {
            throw new IllegalArgumentException("At least one sample_test_result_id is required.");
        }
        Files.createDirectories(config.exportDir());
        Files.createDirectories(config.outputDir());
        Files.createDirectories(config.templateDir());
        Files.createDirectories(config.logDir());

        List<Long> selectedIds = List.copyOf(sampleTestResultIds);
        String genomeBuild = genomeBuild(selectedIds);
        String species = validation.circlizeSpecies(genomeBuild);

        List<CircosSegmentRecord> gains = segments(selectedIds, List.of(
                "GAIN", "DUP", "DUPLICATION", "AMP", "AMPLIFICATION"));
        List<CircosSegmentRecord> losses = segments(selectedIds, List.of(
                "LOSS", "DEL", "DELETION"));
        List<CircosLinkRecord> links = translocationLinks(selectedIds).stream()
                .limit(config.maxLinks())
                .toList();

        String label = plotLabel(selectedIds);
        Path resultExportDir = config.exportDir().resolve(label);
        Files.createDirectories(resultExportDir);
        Path gainTsv = resultExportDir.resolve("events_gain.tsv");
        Path lossTsv = resultExportDir.resolve("events_loss.tsv");
        Path connectionsTsv = resultExportDir.resolve("connections.tsv");
        writeSegments(gainTsv, gains);
        writeSegments(lossTsv, losses);
        writeLinks(connectionsTsv, links);

        Path svg = config.outputDir().resolve("circos_" + label + ".svg");
        Path generatedScript = resultExportDir.resolve("circos_" + label + ".R");
        return new CircosExportResult(
                selectedIds.get(0),
                selectedIds,
                label,
                genomeBuild,
                species,
                resultExportDir,
                gainTsv,
                lossTsv,
                connectionsTsv,
                generatedScript,
                svg,
                gains.size(),
                losses.size(),
                links.stream().mapToInt(record -> Math.max(1, record.eventCount())).sum());
    }

    private String plotLabel(List<Long> selectedIds) {
        String timestamp = LABEL_FORMAT.format(LocalDateTime.now());
        if (selectedIds.size() == 1) {
            return "result_" + selectedIds.get(0) + "_" + timestamp;
        }
        return "cohort_" + selectedIds.size() + "_results_" + timestamp;
    }

    private String genomeBuild(List<Long> sampleTestResultIds) throws SQLException {
        String sql = """
                SELECT DISTINCT genome_build
                FROM sample_test_results
                WHERE sample_test_result_id IN (%s)
                ORDER BY genome_build
                """.formatted(placeholders(sampleTestResultIds.size()));
        List<String> builds = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bindIds(ps, sampleTestResultIds);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String build = rs.getString("genome_build");
                    if (build != null && !build.isBlank()) {
                        builds.add(build);
                    }
                }
            }
        }
        if (builds.isEmpty()) {
            throw new IllegalArgumentException("Selected results do not have a genome build.");
        }
        if (builds.size() > 1) {
            throw new IllegalArgumentException("Selected results contain mixed genome builds: " + builds);
        }
        return builds.get(0);
    }

    private List<CircosSegmentRecord> segments(List<Long> sampleTestResultIds, List<String> eventTypes)
            throws SQLException {
        String eventPlaceholders = placeholders(eventTypes.size());
        String idPlaceholders = placeholders(sampleTestResultIds.size());
        String sql = """
                SELECT segment_id, sample_test_result_id, chromosome, start_pos, stop_pos, copy_number, event_type, confidence
                FROM genomic_segments
                WHERE sample_test_result_id IN (%s)
                  AND UPPER(event_type) IN (%s)
                """.formatted(idPlaceholders, eventPlaceholders);
        List<CircosSegmentRecord> records = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int index = bindIds(ps, sampleTestResultIds);
            for (String eventType : eventTypes) {
                ps.setString(index++, eventType);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long segmentId = rs.getLong("segment_id");
                    String chr = validation.normalizeChromosome(rs.getString("chromosome"));
                    long start = rs.getLong("start_pos");
                    long stop = rs.getLong("stop_pos");
                    validation.validateInterval(chr, start, stop, "segment_id=" + segmentId);
                    records.add(new CircosSegmentRecord(
                            chr,
                            start,
                            stop,
                            rs.getInt("copy_number"),
                            rs.getString("event_type"),
                            rs.getString("confidence"),
                            segmentId,
                            rs.getLong("sample_test_result_id")));
                }
            }
        }
        records.sort(Comparator.comparingInt((CircosSegmentRecord record) -> chromosomeRank(record.chr()))
                .thenComparingLong(CircosSegmentRecord::start));
        return records;
    }

    private List<CircosLinkRecord> translocationLinks(List<Long> sampleTestResultIds) throws SQLException {
        List<RawLink> rawLinks = rawTranslocationLinks(sampleTestResultIds);
        if (sampleTestResultIds.size() == 1) {
            return rawLinks.stream()
                    .map(raw -> new CircosLinkRecord(
                            raw.chr1(),
                            raw.pos1(),
                            raw.chr2(),
                            raw.pos2(),
                            raw.linkType(),
                            raw.confidence(),
                            raw.eventGroupId(),
                            raw.linkId(),
                            raw.sampleTestResultId(),
                            1,
                            1,
                            1))
                    .toList();
        }
        return aggregateCohortLinks(rawLinks);
    }

    private List<RawLink> rawTranslocationLinks(List<Long> sampleTestResultIds) throws SQLException {
        String placeholders = placeholders(sampleTestResultIds.size());
        String sql = """
                SELECT
                    gl.link_id,
                    gl.event_group_id,
                    src.sample_test_result_id,
                    src.chromosome AS chr1,
                    src.start_pos AS pos1,
                    tgt.chromosome AS chr2,
                    tgt.start_pos AS pos2,
                    gl.link_type,
                    gl.confidence,
                    i.individual_id,
                    sa.sample_accession_id
                FROM genomic_links gl
                JOIN genomic_segments src ON src.segment_id = gl.source_segment_id
                JOIN genomic_segments tgt ON tgt.segment_id = gl.target_segment_id
                JOIN sample_test_results str ON str.sample_test_result_id = src.sample_test_result_id
                JOIN sample_tests st ON st.sample_test_id = str.sample_test_id
                JOIN sample_accessions sa ON sa.sample_accession_id = st.sample_accession_id
                JOIN individuals i ON i.individual_id = sa.individual_id
                WHERE src.sample_test_result_id IN (%s)
                  AND tgt.sample_test_result_id IN (%s)
                  AND UPPER(gl.link_type) = 'TRANSLOCATION'
                ORDER BY gl.link_id
                """.formatted(placeholders, placeholders);
        List<RawLink> records = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int index = bindIds(ps, sampleTestResultIds);
            bindIds(ps, sampleTestResultIds, index);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long linkId = rs.getLong("link_id");
                    String chr1 = validation.normalizeChromosome(rs.getString("chr1"));
                    String chr2 = validation.normalizeChromosome(rs.getString("chr2"));
                    long pos1 = rs.getLong("pos1");
                    long pos2 = rs.getLong("pos2");
                    validation.validateInterval(chr1, pos1, pos1, "link_id=" + linkId + " source");
                    validation.validateInterval(chr2, pos2, pos2, "link_id=" + linkId + " target");
                    records.add(new RawLink(
                            chr1,
                            pos1,
                            chr2,
                            pos2,
                            rs.getString("link_type"),
                            rs.getString("confidence"),
                            rs.getString("event_group_id"),
                            linkId,
                            rs.getLong("sample_test_result_id"),
                            rs.getLong("individual_id"),
                            rs.getLong("sample_accession_id")));
                }
            }
        }
        return records;
    }

    private List<CircosLinkRecord> aggregateCohortLinks(List<RawLink> rawLinks) {
        Map<String, LinkAggregate> aggregates = new HashMap<>();
        for (RawLink raw : rawLinks) {
            boolean forward = chromosomeRank(raw.chr1()) <= chromosomeRank(raw.chr2());
            String chr1 = forward ? raw.chr1() : raw.chr2();
            String chr2 = forward ? raw.chr2() : raw.chr1();
            long pos1 = forward ? raw.pos1() : raw.pos2();
            long pos2 = forward ? raw.pos2() : raw.pos1();
            String key = chr1 + "\t" + chr2;
            aggregates.computeIfAbsent(key, ignored -> new LinkAggregate(chr1, chr2)).add(raw, pos1, pos2);
        }
        return aggregates.values().stream()
                .map(LinkAggregate::toRecord)
                .sorted(Comparator.comparingInt(CircosLinkRecord::patientCount).reversed()
                        .thenComparingInt(CircosLinkRecord::eventCount).reversed()
                        .thenComparingInt(record -> chromosomeRank(record.chr1()))
                        .thenComparingInt(record -> chromosomeRank(record.chr2())))
                .toList();
    }

    private void writeSegments(Path path, List<CircosSegmentRecord> records) throws IOException {
        StringBuilder sb = new StringBuilder("chr\tstart\tstop\tvalue\tevent_type\tconfidence\tsegment_id\tsample_test_result_id\n");
        for (CircosSegmentRecord record : records) {
            sb.append(tsv(record.chr())).append('\t')
                    .append(record.start()).append('\t')
                    .append(record.stop()).append('\t')
                    .append(record.value()).append('\t')
                    .append(tsv(record.eventType())).append('\t')
                    .append(tsv(record.confidence())).append('\t')
                    .append(record.segmentId()).append('\t')
                    .append(record.sampleTestResultId()).append('\n');
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private void writeLinks(Path path, List<CircosLinkRecord> records) throws IOException {
        StringBuilder sb = new StringBuilder("chr1\tpos1\tchr2\tpos2\tlink_type\tconfidence\tevent_group_id\tlink_id\tsample_test_result_id\tpatient_count\tsample_count\tevent_count\n");
        for (CircosLinkRecord record : records) {
            sb.append(tsv(record.chr1())).append('\t')
                    .append(record.pos1()).append('\t')
                    .append(tsv(record.chr2())).append('\t')
                    .append(record.pos2()).append('\t')
                    .append(tsv(record.linkType())).append('\t')
                    .append(tsv(record.confidence())).append('\t')
                    .append(tsv(record.eventGroupId())).append('\t')
                    .append(record.linkId()).append('\t')
                    .append(record.sampleTestResultId()).append('\t')
                    .append(record.patientCount()).append('\t')
                    .append(record.sampleCount()).append('\t')
                    .append(record.eventCount()).append('\n');
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private int bindIds(PreparedStatement ps, List<Long> ids) throws SQLException {
        return bindIds(ps, ids, 1);
    }

    private int bindIds(PreparedStatement ps, List<Long> ids, int startIndex) throws SQLException {
        int index = startIndex;
        for (Long id : ids) {
            ps.setLong(index++, id);
        }
        return index;
    }

    private String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private int chromosomeRank(String chr) {
        String value = chr == null ? "" : chr.replaceFirst("(?i)^chr", "");
        return switch (value.toUpperCase()) {
            case "X" -> 23;
            case "Y" -> 24;
            default -> {
                try {
                    yield Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    yield 100;
                }
            }
        };
    }

    private String tsv(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private record RawLink(
            String chr1,
            long pos1,
            String chr2,
            long pos2,
            String linkType,
            String confidence,
            String eventGroupId,
            long linkId,
            long sampleTestResultId,
            long individualId,
            long sampleAccessionId
    ) {
    }

    private static class LinkAggregate {
        private final String chr1;
        private final String chr2;
        private final Set<Long> patientIds = new HashSet<>();
        private final Set<Long> sampleAccessionIds = new HashSet<>();
        private long linkId = Long.MAX_VALUE;
        private long pos1Total;
        private long pos2Total;
        private int eventCount;

        LinkAggregate(String chr1, String chr2) {
            this.chr1 = chr1;
            this.chr2 = chr2;
        }

        void add(RawLink raw, long pos1, long pos2) {
            patientIds.add(raw.individualId());
            sampleAccessionIds.add(raw.sampleAccessionId());
            linkId = Math.min(linkId, raw.linkId());
            pos1Total += pos1;
            pos2Total += pos2;
            eventCount++;
        }

        CircosLinkRecord toRecord() {
            long averagePos1 = eventCount == 0 ? 0 : Math.round((double) pos1Total / eventCount);
            long averagePos2 = eventCount == 0 ? 0 : Math.round((double) pos2Total / eventCount);
            return new CircosLinkRecord(
                    chr1,
                    averagePos1,
                    chr2,
                    averagePos2,
                    "TRANSLOCATION",
                    "COHORT",
                    "COHORT_" + chr1 + "_" + chr2,
                    linkId == Long.MAX_VALUE ? 0 : linkId,
                    0,
                    patientIds.size(),
                    sampleAccessionIds.size(),
                    eventCount);
        }
    }
}
