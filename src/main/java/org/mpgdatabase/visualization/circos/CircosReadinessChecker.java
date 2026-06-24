package org.mpgdatabase.visualization.circos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class CircosReadinessChecker {
    private final Connection connection;
    private final CircosValidationService validation = new CircosValidationService();

    public CircosReadinessChecker(Connection connection) {
        this.connection = connection;
    }

    public CircosReadinessResult check(List<Long> sampleTestResultIds) throws SQLException {
        if (sampleTestResultIds == null || sampleTestResultIds.isEmpty()) {
            return new CircosReadinessResult(false, false, false,
                    "No sample test results selected.", 0, 0, 0, 0, null, List.of());
        }
        Counts counts = counts(sampleTestResultIds);
        List<String> builds = genomeBuilds(sampleTestResultIds);
        boolean mixed = builds.size() > 1;
        boolean supported = !builds.isEmpty();
        String selectedBuild = builds.isEmpty() ? null : builds.get(0);
        if (supported && !mixed) {
            try {
                validation.circlizeSpecies(selectedBuild);
            } catch (IllegalArgumentException e) {
                supported = false;
            }
        }
        boolean available = counts.gains() > 0 || counts.losses() > 0 || counts.translocations() > 0;
        String reason;
        if (mixed) {
            reason = "Selected results contain mixed genome builds.";
        } else if (!supported) {
            reason = "Selected results have an unsupported or missing genome build.";
        } else if (available) {
            reason = "CNV/SV plot-ready events found.";
        } else {
            reason = "No CNV gain/loss segments or translocation links found.";
        }
        return new CircosReadinessResult(
                available,
                supported,
                mixed,
                reason,
                counts.gains(),
                counts.losses(),
                counts.translocations(),
                counts.snvs(),
                selectedBuild,
                builds);
    }

    private Counts counts(List<Long> ids) throws SQLException {
        String placeholders = placeholders(ids.size());
        String sql = """
                SELECT
                    COUNT(DISTINCT CASE WHEN UPPER(gs.event_type) IN ('GAIN','DUP','DUPLICATION','AMP','AMPLIFICATION') THEN gs.segment_id END) AS gain_count,
                    COUNT(DISTINCT CASE WHEN UPPER(gs.event_type) IN ('LOSS','DEL','DELETION') THEN gs.segment_id END) AS loss_count,
                    COUNT(DISTINCT gl.link_id) AS translocation_count,
                    COUNT(DISTINCT svc.small_variant_call_id) AS snv_count
                FROM sample_test_results str
                LEFT JOIN genomic_segments gs ON gs.sample_test_result_id = str.sample_test_result_id
                LEFT JOIN genomic_links gl
                    ON UPPER(gl.link_type) = 'TRANSLOCATION'
                   AND gl.source_segment_id IN (
                        SELECT segment_id FROM genomic_segments WHERE sample_test_result_id = str.sample_test_result_id
                   )
                   AND gl.target_segment_id IN (
                        SELECT segment_id FROM genomic_segments WHERE sample_test_result_id = str.sample_test_result_id
                   )
                LEFT JOIN small_variant_sample_calls svc ON svc.sample_test_result_id = str.sample_test_result_id
                WHERE str.sample_test_result_id IN (%s)
                """.formatted(placeholders);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bindIds(ps, ids);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Counts(
                            rs.getInt("gain_count"),
                            rs.getInt("loss_count"),
                            rs.getInt("translocation_count"),
                            rs.getInt("snv_count"));
                }
            }
        }
        return new Counts(0, 0, 0, 0);
    }

    private List<String> genomeBuilds(List<Long> ids) throws SQLException {
        String sql = "SELECT DISTINCT genome_build FROM sample_test_results WHERE sample_test_result_id IN (%s) ORDER BY genome_build"
                .formatted(placeholders(ids.size()));
        Set<String> builds = new LinkedHashSet<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bindIds(ps, ids);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String build = rs.getString("genome_build");
                    if (build != null && !build.isBlank()) {
                        builds.add(build);
                    }
                }
            }
        }
        return new ArrayList<>(builds);
    }

    private void bindIds(PreparedStatement ps, List<Long> ids) throws SQLException {
        for (int i = 0; i < ids.size(); i++) {
            ps.setLong(i + 1, ids.get(i));
        }
    }

    private String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private record Counts(int gains, int losses, int translocations, int snvs) {
    }
}
