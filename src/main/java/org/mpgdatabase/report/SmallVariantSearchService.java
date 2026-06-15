package org.mpgdatabase.report;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SmallVariantSearchService {
    private final Connection connection;

    public SmallVariantSearchService(Connection connection) {
        this.connection = connection;
    }

    public String search(Map<String, String> filters) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT
                    sv.small_variant_id,
                    sa.accession_identifier AS sample_accession_id,
                    sv.chromosome,
                    sv.position,
                    sv.variant_id,
                    sv.ref_allele,
                    sv.alt_allele,
                    sv.variant_type,
                    sv.genome_build,
                    svc.qual,
                    svc.filter_status,
                    svc.genotype,
                    svc.phased,
                    svc.ref_depth,
                    svc.alt_depth,
                    svc.total_depth,
                    svc.genotype_quality,
                    svc.allele_balance,
                    sf.file_name AS source_file
                FROM small_variants sv
                JOIN small_variant_sample_calls svc ON svc.small_variant_id = sv.small_variant_id
                JOIN sample_test_results str ON str.sample_test_result_id = svc.sample_test_result_id
                JOIN sample_tests st ON st.sample_test_id = str.sample_test_id
                JOIN sample_accessions sa ON sa.sample_accession_id = st.sample_accession_id
                LEFT JOIN source_files sf ON sf.source_file_id = str.source_file_id
                WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        addValues(sql, params, filters, "sample", "sa.accession_identifier", false);
        addValues(sql, params, filters, "chromosome", "sv.chromosome", true);
        addValues(sql, params, filters, "variant-id", "sv.variant_id", false);
        addValues(sql, params, filters, "variant-type", "sv.variant_type", false);
        addValues(sql, params, filters, "genome-build", "sv.genome_build", false);
        addValues(sql, params, filters, "genotype", "svc.genotype", false);
        addValues(sql, params, filters, "filter-status", "svc.filter_status", false);
        addRegion(sql, params, filters);
        addNumericFilter(sql, params, filters, "min-alt-depth", "svc.alt_depth", ">=");
        addNumericFilter(sql, params, filters, "max-alt-depth", "svc.alt_depth", "<=");
        addNumericFilter(sql, params, filters, "min-total-depth", "svc.total_depth", ">=");
        addNumericFilter(sql, params, filters, "max-total-depth", "svc.total_depth", "<=");
        addDecimalFilter(sql, params, filters, "min-allele-balance", "svc.allele_balance", ">=");
        addDecimalFilter(sql, params, filters, "max-allele-balance", "svc.allele_balance", "<=");
        addAnnotationValues(sql, params, filters, "gene", "sva.gene");
        addAnnotationValues(sql, params, filters, "consequence", "sva.consequence");
        addAnnotationValues(sql, params, filters, "impact", "sva.impact");
        sql.append(" ORDER BY sv.chromosome, sv.position, sv.small_variant_id, sample_accession_id");

        List<SmallVariantSearchRow> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new SmallVariantSearchRow(
                            rs.getLong("small_variant_id"),
                            rs.getString("sample_accession_id"),
                            rs.getString("chromosome"),
                            rs.getLong("position"),
                            rs.getString("variant_id"),
                            rs.getString("ref_allele"),
                            rs.getString("alt_allele"),
                            rs.getString("variant_type"),
                            rs.getString("genome_build"),
                            nullableDouble(rs, "qual"),
                            rs.getString("filter_status"),
                            rs.getString("genotype"),
                            nullableBoolean(rs, "phased"),
                            nullableInt(rs, "ref_depth"),
                            nullableInt(rs, "alt_depth"),
                            nullableInt(rs, "total_depth"),
                            nullableDouble(rs, "genotype_quality"),
                            nullableDouble(rs, "allele_balance"),
                            rs.getString("source_file")
                    ));
                }
            }
        }
        return table(rows);
    }

    private void addValues(
            StringBuilder sql,
            List<Object> params,
            Map<String, String> filters,
            String filterName,
            String column,
            boolean chromosome
    ) {
        String value = filters.get(filterName);
        if (value == null || value.isBlank()) {
            return;
        }
        List<String> values = splitValues(value);
        if (values.isEmpty()) {
            return;
        }
        sql.append(" AND ").append(column);
        if (values.size() == 1) {
            sql.append(" = ?\n");
        } else {
            sql.append(" IN (");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append('?');
            }
            sql.append(")\n");
        }
        for (String item : values) {
            params.add(chromosome ? normalizeChromosome(item) : item);
        }
    }

    private void addRegion(StringBuilder sql, List<Object> params, Map<String, String> filters) {
        String start = filters.get("start");
        String stop = firstNonBlank(filters.get("end"), filters.get("stop"));
        if (start != null && !start.isBlank()) {
            sql.append(" AND sv.position >= ?\n");
            params.add(parseLong(start));
        }
        if (stop != null && !stop.isBlank()) {
            sql.append(" AND sv.position <= ?\n");
            params.add(parseLong(stop));
        }
    }

    private void addNumericFilter(
            StringBuilder sql,
            List<Object> params,
            Map<String, String> filters,
            String filterName,
            String column,
            String operator
    ) {
        String value = filters.get(filterName);
        if (value == null || value.isBlank()) {
            return;
        }
        sql.append(" AND ").append(column).append(' ').append(operator).append(" ?\n");
        params.add(parseLong(value).intValue());
    }

    private void addDecimalFilter(
            StringBuilder sql,
            List<Object> params,
            Map<String, String> filters,
            String filterName,
            String column,
            String operator
    ) {
        String value = filters.get(filterName);
        if (value == null || value.isBlank()) {
            return;
        }
        sql.append(" AND ").append(column).append(' ').append(operator).append(" ?\n");
        params.add(Double.parseDouble(value));
    }

    private void addAnnotationValues(
            StringBuilder sql,
            List<Object> params,
            Map<String, String> filters,
            String filterName,
            String column
    ) {
        String value = filters.get(filterName);
        if (value == null || value.isBlank()) {
            return;
        }
        List<String> values = splitValues(value);
        if (values.isEmpty()) {
            return;
        }
        sql.append("""
                 AND EXISTS (
                     SELECT 1
                     FROM small_variant_annotations sva
                     WHERE sva.small_variant_id = sv.small_variant_id
                       AND\040""").append(column);
        if (values.size() == 1) {
            sql.append(" = ?\n");
        } else {
            sql.append(" IN (");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append('?');
            }
            sql.append(")\n");
        }
        sql.append(" )\n");
        params.addAll(values);
    }

    private void bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object value = params.get(i);
            if (value instanceof Long longValue) {
                ps.setLong(i + 1, longValue);
            } else if (value instanceof Integer intValue) {
                ps.setInt(i + 1, intValue);
            } else if (value instanceof Double doubleValue) {
                ps.setDouble(i + 1, doubleValue);
            } else {
                ps.setString(i + 1, value.toString());
            }
        }
    }

    private String table(List<SmallVariantSearchRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("SMALL_VARIANT_ID\tSAMPLE_ACCESSION_ID\tCHROMOSOME\tPOSITION\tVARIANT_ID\tREF_ALLELE\tALT_ALLELE\tVARIANT_TYPE\tGENOME_BUILD\tQUAL\tFILTER_STATUS\tGENOTYPE\tPHASED\tREF_DEPTH\tALT_DEPTH\tTOTAL_DEPTH\tGENOTYPE_QUALITY\tALLELE_BALANCE\tSOURCE_FILE\tANNOTATIONS\n");
        for (SmallVariantSearchRow row : rows) {
            sb.append(row.smallVariantId()).append('\t')
                    .append(nullToEmpty(row.sampleAccessionId())).append('\t')
                    .append(nullToEmpty(row.chromosome())).append('\t')
                    .append(row.position()).append('\t')
                    .append(nullToEmpty(row.variantId())).append('\t')
                    .append(nullToEmpty(row.refAllele())).append('\t')
                    .append(nullToEmpty(row.altAllele())).append('\t')
                    .append(nullToEmpty(row.variantType())).append('\t')
                    .append(nullToEmpty(row.genomeBuild())).append('\t')
                    .append(nullToEmpty(row.qual())).append('\t')
                    .append(nullToEmpty(row.filterStatus())).append('\t')
                    .append(nullToEmpty(row.genotype())).append('\t')
                    .append(nullToEmpty(row.phased())).append('\t')
                    .append(nullToEmpty(row.refDepth())).append('\t')
                    .append(nullToEmpty(row.altDepth())).append('\t')
                    .append(nullToEmpty(row.totalDepth())).append('\t')
                    .append(nullToEmpty(row.genotypeQuality())).append('\t')
                    .append(nullToEmpty(row.alleleBalance())).append('\t')
                    .append(nullToEmpty(row.sourceFile())).append('\t')
                    .append(readableAnnotations(row.smallVariantId()))
                    .append('\n');
        }
        sb.append("\nRows: ").append(rows.size()).append('\n');
        return sb.toString();
    }

    private String readableAnnotations(long smallVariantId) {
        List<String> pairs = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT gene, consequence, impact, transcript, hgvs_c, hgvs_p
                FROM small_variant_annotations
                WHERE small_variant_id = ?
                ORDER BY small_variant_annotation_id
                """)) {
            ps.setLong(1, smallVariantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    pairs.add("Gene=" + nullToEmpty(rs.getString("gene"))
                            + "; Consequence=" + nullToEmpty(rs.getString("consequence"))
                            + "; Impact=" + nullToEmpty(rs.getString("impact"))
                            + "; Transcript=" + nullToEmpty(rs.getString("transcript"))
                            + "; HGVS.c=" + nullToEmpty(rs.getString("hgvs_c"))
                            + "; HGVS.p=" + nullToEmpty(rs.getString("hgvs_p")));
                }
            }
        } catch (SQLException e) {
            return "";
        }
        return String.join(" | ", pairs);
    }

    private List<String> splitValues(String value) {
        List<String> values = new ArrayList<>();
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                values.add(part.trim());
            }
        }
        return values;
    }

    private Long parseLong(String value) {
        return Long.parseLong(value.replace(",", ""));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String normalizeChromosome(String value) {
        return value.toLowerCase(Locale.ROOT).startsWith("chr") ? value : "chr" + value;
    }

    private Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private Boolean nullableBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    private String nullToEmpty(Object value) {
        return value == null ? "" : value.toString();
    }

    private record SmallVariantSearchRow(
            long smallVariantId,
            String sampleAccessionId,
            String chromosome,
            long position,
            String variantId,
            String refAllele,
            String altAllele,
            String variantType,
            String genomeBuild,
            Double qual,
            String filterStatus,
            String genotype,
            Boolean phased,
            Integer refDepth,
            Integer altDepth,
            Integer totalDepth,
            Double genotypeQuality,
            Double alleleBalance,
            String sourceFile
    ) {
    }
}
