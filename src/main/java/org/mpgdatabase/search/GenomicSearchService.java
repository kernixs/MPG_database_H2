package org.mpgdatabase.search;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class GenomicSearchService {
    private static final Set<String> UNSUPPORTED_FILTERS = Set.of(
            "case_type",
            "clinical_indication",
            "phenotype_terms",
            "record_date_start",
            "record_date_end",
            "review_status",
            "clinical_significance",
            "omim_association",
            "raw_data_available",
            "cytoband_interval_start",
            "cytoband_interval_end",
            "parser_confidence_minimum",
            "unsupported_parse_failure_reason",
            "centromeric_repetitive_region_flag",
            "internal_recurrence_count_minimum",
            "clinical_classification_source",
            "annotation_source_version",
            "preferred_transcript_only",
            "review_priority"
    );

    private final Connection connection;

    public GenomicSearchService(Connection connection) {
        this.connection = connection;
    }

    public SearchResultSummary search(SearchScope scope, Map<String, String> filters) throws SQLException {
        List<Long> resultIds = matchingResultIds(scope, filters);
        if (resultIds.isEmpty()) {
            return new SearchResultSummary(scope, 0, 0, 0, 0, 0, 0, List.of(), unavailableFilters(filters));
        }
        List<SearchResultRow> rows = rowsForResults(resultIds);
        int patients = distinctPatients(rows);
        int snvCount = rows.stream().mapToInt(SearchResultRow::snvCount).sum();
        int gains = rows.stream().mapToInt(SearchResultRow::cnvGainCount).sum();
        int losses = rows.stream().mapToInt(SearchResultRow::cnvLossCount).sum();
        int translocations = rows.stream().mapToInt(SearchResultRow::translocationCount).sum();
        return new SearchResultSummary(
                scope,
                patients,
                rows.size(),
                snvCount,
                gains,
                losses,
                translocations,
                rows,
                unavailableFilters(filters));
    }

    private List<Long> matchingResultIds(SearchScope scope, Map<String, String> filters) throws SQLException {
        Set<Long> ids = new LinkedHashSet<>();
        if (scope == SearchScope.CNV_SV || scope == SearchScope.ALL) {
            ids.addAll(cnvResultIds(filters));
        }
        if (scope == SearchScope.SNV_INDEL || scope == SearchScope.ALL) {
            ids.addAll(snvResultIds(filters));
        }
        return new ArrayList<>(ids);
    }

    private List<Long> cnvResultIds(Map<String, String> filters) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT str.sample_test_result_id
                FROM sample_test_results str
                JOIN sample_tests st ON st.sample_test_id = str.sample_test_id
                JOIN sample_accessions sa ON sa.sample_accession_id = st.sample_accession_id
                JOIN samples s ON s.sample_id = sa.sample_id
                JOIN individuals i ON i.individual_id = s.individual_id
                LEFT JOIN lab_protocols lp ON lp.lab_protocol_id = st.lab_protocol_id
                LEFT JOIN source_files sf ON sf.source_file_id = str.source_file_id
                JOIN genomic_segments gs ON gs.sample_test_result_id = str.sample_test_result_id
                WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        addCommonFilters(sql, params, filters, "gs.chromosome", "gs.start_pos", "gs.stop_pos", "gs.genome_build");
        addLike(sql, params, filters, "gene", """
                EXISTS (
                    SELECT 1 FROM segment_annotations san
                    WHERE san.segment_id = gs.segment_id
                      AND LOWER(san.annotation_name) = 'gene'
                      AND LOWER(san.text_value) LIKE LOWER(?)
                )
                """);
        addEquals(sql, params, filters, "evidence_confidence", "gs.confidence");
        addEquals(sql, params, filters, "sv_cnv_event_type", "gs.event_type");
        addNumber(sql, params, filters, "copy_number", "gs.copy_number", "=");
        addNumber(sql, params, filters, "event_size_bp_minimum", "(gs.stop_pos - gs.start_pos + 1)", ">=");
        addNumber(sql, params, filters, "event_size_bp_maximum", "(gs.stop_pos - gs.start_pos + 1)", "<=");
        sql.append(" ORDER BY str.sample_test_result_id");
        return ids(sql, params);
    }

    private List<Long> snvResultIds(Map<String, String> filters) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT str.sample_test_result_id
                FROM sample_test_results str
                JOIN sample_tests st ON st.sample_test_id = str.sample_test_id
                JOIN sample_accessions sa ON sa.sample_accession_id = st.sample_accession_id
                JOIN samples s ON s.sample_id = sa.sample_id
                JOIN individuals i ON i.individual_id = s.individual_id
                LEFT JOIN lab_protocols lp ON lp.lab_protocol_id = st.lab_protocol_id
                LEFT JOIN source_files sf ON sf.source_file_id = str.source_file_id
                JOIN small_variant_sample_calls svc ON svc.sample_test_result_id = str.sample_test_result_id
                JOIN small_variants sv ON sv.small_variant_id = svc.small_variant_id
                LEFT JOIN small_variant_annotations sva ON sva.small_variant_id = sv.small_variant_id
                WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        addCommonFilters(sql, params, filters, "sv.chromosome", "sv.position", "sv.position", "sv.genome_build");
        addLike(sql, params, filters, "gene", "sva.gene LIKE ?");
        addEquals(sql, params, filters, "evidence_confidence", "svc.filter_status");
        addEquals(sql, params, filters, "variant_type", "sv.variant_type");
        addNumber(sql, params, filters, "genomic_position", "sv.position", "=");
        addEquals(sql, params, filters, "reference_allele", "sv.ref_allele");
        addEquals(sql, params, filters, "alternate_allele", "sv.alt_allele");
        addLike(sql, params, filters, "transcript_id", "sva.transcript LIKE ?");
        addLike(sql, params, filters, "hgvs_cdna", "sva.hgvs_c LIKE ?");
        addLike(sql, params, filters, "hgvs_protein", "sva.hgvs_p LIKE ?");
        addLike(sql, params, filters, "molecular_consequence", "sva.consequence LIKE ?");
        addEquals(sql, params, filters, "zygosity", "svc.genotype");
        addDecimal(sql, params, filters, "minimum_vaf", "svc.allele_balance", ">=");
        addNumber(sql, params, filters, "minimum_read_depth", "svc.total_depth", ">=");
        addEquals(sql, params, filters, "caller_filter_status", "svc.filter_status");
        addEquals(sql, params, filters, "variant_id", "sv.variant_id");
        addEquals(sql, params, filters, "dbsnp_rsid", "sv.variant_id");
        sql.append(" ORDER BY str.sample_test_result_id");
        return ids(sql, params);
    }

    private void addCommonFilters(
            StringBuilder sql,
            List<Object> params,
            Map<String, String> filters,
            String chromosomeColumn,
            String startColumn,
            String stopColumn,
            String genomeBuildColumn
    ) {
        addEquals(sql, params, filters, "mrn", "i.mrn");
        addEquals(sql, params, filters, "accession_number", "sa.accession_identifier");
        addLike(sql, params, filters, "specimen_type", "s.dna_source LIKE ?");
        String method = value(filters, "test_source_method");
        if (method != null) {
            sql.append(" AND (LOWER(str.calling_method) LIKE LOWER(?) OR LOWER(st.test_type) LIKE LOWER(?) OR LOWER(lp.technology) LIKE LOWER(?))\n");
            String like = like(method);
            params.add(like);
            params.add(like);
            params.add(like);
        }
        addLike(sql, params, filters, "source_file_report", "sf.file_name LIKE ?");
        addEquals(sql, params, filters, "genome_build", genomeBuildColumn);
        addEquals(sql, params, filters, "chromosome", chromosomeColumn, true);
        String start = value(filters, "region_start");
        String end = value(filters, "region_end");
        if (start != null || end != null) {
            long regionStart = start == null ? 0 : Long.parseLong(start);
            long regionEnd = end == null ? Long.MAX_VALUE : Long.parseLong(end);
            sql.append(" AND ").append(startColumn).append(" <= ? AND ").append(stopColumn).append(" >= ?\n");
            params.add(regionEnd);
            params.add(regionStart);
        }
    }

    private List<SearchResultRow> rowsForResults(List<Long> ids) throws SQLException {
        String placeholders = placeholders(ids.size());
        String sql = """
                SELECT
                    str.sample_test_result_id,
                    i.mrn,
                    sa.accession_identifier,
                    s.dna_source,
                    st.test_type,
                    str.calling_method,
                    COALESCE(MAX(gs.genome_build), MAX(sv.genome_build)) AS genome_build,
                    sf.file_name,
                    COUNT(DISTINCT CASE WHEN UPPER(gs.event_type) IN ('GAIN','DUP','DUPLICATION','AMP','AMPLIFICATION') THEN gs.segment_id END) AS gain_count,
                    COUNT(DISTINCT CASE WHEN UPPER(gs.event_type) IN ('LOSS','DEL','DELETION') THEN gs.segment_id END) AS loss_count,
                    COUNT(DISTINCT gl.link_id) AS translocation_count,
                    COUNT(DISTINCT svc.small_variant_call_id) AS snv_count
                FROM sample_test_results str
                JOIN sample_tests st ON st.sample_test_id = str.sample_test_id
                JOIN sample_accessions sa ON sa.sample_accession_id = st.sample_accession_id
                JOIN samples s ON s.sample_id = sa.sample_id
                JOIN individuals i ON i.individual_id = s.individual_id
                LEFT JOIN source_files sf ON sf.source_file_id = str.source_file_id
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
                LEFT JOIN small_variants sv ON sv.small_variant_id = svc.small_variant_id
                WHERE str.sample_test_result_id IN (%s)
                GROUP BY
                    str.sample_test_result_id,
                    i.mrn,
                    sa.accession_identifier,
                    s.dna_source,
                    st.test_type,
                    str.calling_method,
                    sf.file_name
                ORDER BY str.sample_test_result_id
                """.formatted(placeholders);
        List<SearchResultRow> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < ids.size(); i++) {
                ps.setLong(i + 1, ids.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new SearchResultRow(
                            rs.getLong("sample_test_result_id"),
                            rs.getString("mrn"),
                            rs.getString("accession_identifier"),
                            rs.getString("dna_source"),
                            rs.getString("test_type"),
                            rs.getString("calling_method"),
                            rs.getString("genome_build"),
                            rs.getString("file_name"),
                            rs.getInt("gain_count"),
                            rs.getInt("loss_count"),
                            rs.getInt("translocation_count"),
                            rs.getInt("snv_count")));
                }
            }
        }
        return rows;
    }

    private List<Long> ids(StringBuilder sql, List<Object> params) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    private void addEquals(StringBuilder sql, List<Object> params, Map<String, String> filters, String key, String column) {
        addEquals(sql, params, filters, key, column, false);
    }

    private void addEquals(
            StringBuilder sql,
            List<Object> params,
            Map<String, String> filters,
            String key,
            String column,
            boolean chromosome
    ) {
        String value = value(filters, key);
        if (value == null) {
            return;
        }
        sql.append(" AND ").append(column).append(" = ?\n");
        params.add(chromosome ? normalizeChromosome(value) : value);
    }

    private void addLike(StringBuilder sql, List<Object> params, Map<String, String> filters, String key, String condition) {
        String value = value(filters, key);
        if (value == null) {
            return;
        }
        sql.append(" AND ").append(condition).append('\n');
        params.add(like(value));
    }

    private void addNumber(
            StringBuilder sql,
            List<Object> params,
            Map<String, String> filters,
            String key,
            String column,
            String operator
    ) {
        String value = value(filters, key);
        if (value == null) {
            return;
        }
        sql.append(" AND ").append(column).append(' ').append(operator).append(" ?\n");
        params.add(Long.parseLong(value));
    }

    private void addDecimal(
            StringBuilder sql,
            List<Object> params,
            Map<String, String> filters,
            String key,
            String column,
            String operator
    ) {
        String value = value(filters, key);
        if (value == null) {
            return;
        }
        sql.append(" AND ").append(column).append(' ').append(operator).append(" ?\n");
        params.add(Double.parseDouble(value));
    }

    private void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object value = params.get(i);
            if (value instanceof Long longValue) {
                ps.setLong(i + 1, longValue);
            } else if (value instanceof Double doubleValue) {
                ps.setDouble(i + 1, doubleValue);
            } else {
                ps.setString(i + 1, value.toString());
            }
        }
    }

    private List<String> unavailableFilters(Map<String, String> filters) {
        List<String> unavailable = new ArrayList<>();
        for (String key : filters.keySet()) {
            if (UNSUPPORTED_FILTERS.contains(key) && value(filters, key) != null) {
                unavailable.add(key);
            }
        }
        return unavailable;
    }

    private int distinctPatients(List<SearchResultRow> rows) {
        return (int) rows.stream().map(SearchResultRow::mrn).distinct().count();
    }

    private String value(Map<String, String> filters, String key) {
        String value = filters.get(key);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String like(String value) {
        return "%" + value.trim() + "%";
    }

    private String normalizeChromosome(String value) {
        String trimmed = value.trim();
        return trimmed.toLowerCase(Locale.ROOT).startsWith("chr") ? trimmed : "chr" + trimmed;
    }

    private String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }
}
