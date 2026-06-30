package org.mpgdatabase.report;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SearchService {
    private final Connection connection;

    public SearchService(Connection connection) {
        this.connection = connection;
    }

    public String search(Map<String, String> filters) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT
                    gs.event_group_id,
                    gs.segment_id,
                    sa.accession_identifier AS sample_accession_id,
                    gs.chromosome,
                    gs.start_pos,
                    gs.stop_pos,
                    (gs.stop_pos - gs.start_pos + 1) AS cnv_size,
                    gs.event_type,
                    gs.copy_number,
                    gs.genome_build,
                    gs.confidence,
                    gs.raw_segment_text,
                    str.calling_method,
                    sf.file_name AS source_file
                FROM genomic_segments gs
                JOIN sample_test_results str ON str.sample_test_result_id = gs.sample_test_result_id
                JOIN sample_tests st ON st.sample_test_id = str.sample_test_id
                JOIN sample_accessions sa ON sa.sample_accession_id = st.sample_accession_id
                LEFT JOIN source_files sf ON sf.source_file_id = str.source_file_id
                WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        addValues(sql, params, filters, "sample", "sa.accession_identifier");
        addValues(sql, params, filters, "event-group", "gs.event_group_id");
        addValues(sql, params, filters, "event-type", "gs.event_type");
        addValues(sql, params, filters, "chromosome", "gs.chromosome");
        addValues(sql, params, filters, "calling-method", "str.calling_method");
        addValues(sql, params, filters, "genome-build", "gs.genome_build");
        addValues(sql, params, filters, "confidence", "gs.confidence");
        addCnvSizeFilters(sql, params, filters);
        if (filters.containsKey("start") || filters.containsKey("stop") || filters.containsKey("end")) {
            long start = parseLong(filters.getOrDefault("start", "0"));
            long stop = parseLong(filters.getOrDefault("end", filters.getOrDefault("stop", String.valueOf(Long.MAX_VALUE))));
            sql.append(" AND gs.start_pos <= ? AND gs.stop_pos >= ?\n");
            params.add(stop);
            params.add(start);
        }
        addAnnotationFilters(sql, params, annotationFilters(filters));
        sql.append(" ORDER BY gs.chromosome, gs.start_pos, gs.stop_pos, gs.segment_id");

        List<SearchRow> rows;
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            bindParams(ps, params);
            rows = rows(ps);
        }
        return table(rows);
    }

    private void addValues(
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
            params.add("chromosome".equals(filterName) ? normalizeChromosome(item) : item);
        }
    }

    private void addCnvSizeFilters(StringBuilder sql, List<Object> params, Map<String, String> filters) {
        String min = firstNonBlank(filters.get("cnv-size-min"), filters.get("min-size"));
        if (min != null) {
            sql.append(" AND (gs.stop_pos - gs.start_pos + 1) >= ?\n");
            params.add(parseLong(min));
        }
        String max = firstNonBlank(filters.get("cnv-size-max"), filters.get("max-size"));
        if (max != null) {
            sql.append(" AND (gs.stop_pos - gs.start_pos + 1) <= ?\n");
            params.add(parseLong(max));
        }
    }

    private void addAnnotationFilters(StringBuilder sql, List<Object> params, List<AnnotationFilter> filters) {
        for (AnnotationFilter filter : filters) {
            sql.append("""
                     AND EXISTS (
                         SELECT 1
                         FROM segment_annotations san
                         WHERE san.segment_id = gs.segment_id
                           AND LOWER(san.annotation_name) = LOWER(?)
                           AND (
                    """);
            params.add(filter.key());
            for (int i = 0; i < filter.values().size(); i++) {
                if (i > 0) {
                    sql.append(" OR ");
                }
                sql.append("LOWER(san.text_value) = LOWER(?)");
                params.add(filter.values().get(i));
                Double numeric = parseDoubleOrNull(filter.values().get(i));
                if (numeric != null) {
                    sql.append(" OR san.numeric_value = ?");
                    params.add(numeric);
                }
                Boolean bool = parseBooleanOrNull(filter.values().get(i));
                if (bool != null) {
                    sql.append(" OR san.boolean_value = ?");
                    params.add(bool);
                }
            }
            sql.append("""
                           )
                     )
                    """);
        }
    }

    private List<AnnotationFilter> annotationFilters(Map<String, String> filters) {
        List<AnnotationFilter> annotationFilters = new ArrayList<>();
        addAnnotationFilter(annotationFilters, "Gene", filters.get("gene"));
        addAnnotationFilter(annotationFilters, "Class", filters.get("class"));
        String rawAnnotations = filters.get("annotation");
        if (rawAnnotations == null || rawAnnotations.isBlank()) {
            return annotationFilters;
        }
        for (String rawAnnotation : rawAnnotations.split("\\R")) {
            int equals = rawAnnotation.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = rawAnnotation.substring(0, equals).trim();
            String rawValues = rawAnnotation.substring(equals + 1);
            addAnnotationFilter(annotationFilters, key, rawValues);
        }
        return annotationFilters;
    }

    private void addAnnotationFilter(List<AnnotationFilter> filters, String key, String rawValues) {
        if (key == null || key.isBlank() || rawValues == null || rawValues.isBlank()) {
            return;
        }
        List<String> values = splitValues(rawValues);
        if (!values.isEmpty()) {
            filters.add(new AnnotationFilter(key.trim(), values));
        }
    }

    private void bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object value = params.get(i);
            if (value instanceof Long longValue) {
                ps.setLong(i + 1, longValue);
            } else if (value instanceof Double doubleValue) {
                ps.setDouble(i + 1, doubleValue);
            } else if (value instanceof Boolean booleanValue) {
                ps.setBoolean(i + 1, booleanValue);
            } else {
                ps.setString(i + 1, value.toString());
            }
        }
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

    private List<SearchRow> rows(PreparedStatement ps) throws SQLException {
        List<SearchRow> rows = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new SearchRow(
                        rs.getLong("segment_id"),
                        rs.getString("event_group_id"),
                        rs.getString("sample_accession_id"),
                        rs.getString("chromosome"),
                        rs.getLong("start_pos"),
                        rs.getLong("stop_pos"),
                        rs.getLong("cnv_size"),
                        rs.getString("event_type"),
                        rs.getInt("copy_number"),
                        rs.getString("genome_build"),
                        rs.getString("confidence"),
                        rs.getString("raw_segment_text"),
                        rs.getString("calling_method"),
                        rs.getString("source_file")
                ));
            }
        }
        return rows;
    }

    private String table(List<SearchRow> rows) throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append("EVENT_GROUP_ID\tSEGMENT_ID\tSAMPLE_ACCESSION_ID\tCHROMOSOME\tSTART_POS\tSTOP_POS\tCNV_SIZE\tEVENT_TYPE\tCOPY_NUMBER\tGENOME_BUILD\tCONFIDENCE\tRAW_SEGMENT_TEXT\tCALLING_METHOD\tSOURCE_FILE\tMATCHED_ANNOTATIONS\n");
        for (SearchRow row : rows) {
            sb.append(nullToEmpty(row.eventGroupId())).append('\t')
                    .append(row.segmentId()).append('\t')
                    .append(nullToEmpty(row.sampleAccessionId())).append('\t')
                    .append(nullToEmpty(row.chromosome())).append('\t')
                    .append(row.startPos()).append('\t')
                    .append(row.stopPos()).append('\t')
                    .append(row.cnvSize()).append('\t')
                    .append(nullToEmpty(row.eventType())).append('\t')
                    .append(row.copyNumber()).append('\t')
                    .append(nullToEmpty(row.genomeBuild())).append('\t')
                    .append(nullToEmpty(row.confidence())).append('\t')
                    .append(nullToEmpty(row.rawSegmentText())).append('\t')
                    .append(nullToEmpty(row.callingMethod())).append('\t')
                    .append(nullToEmpty(row.sourceFile())).append('\t')
                    .append(readableAnnotations(row.segmentId()))
                    .append('\n');
        }
        sb.append("\nRows: ").append(rows.size()).append('\n');
        return sb.toString();
    }

    private String readableAnnotations(long segmentId) throws SQLException {
        List<String> pairs = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT annotation_name, text_value, numeric_value, boolean_value, value_type
                FROM segment_annotations
                WHERE segment_id = ?
                ORDER BY ordinal_position, annotation_id
                """)) {
            ps.setLong(1, segmentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    pairs.add(rs.getString("annotation_name") + "=" + displayAnnotationValue(rs));
                }
            }
        }
        return String.join("; ", pairs);
    }

    private String displayAnnotationValue(ResultSet rs) throws SQLException {
        String valueType = rs.getString("value_type");
        if ("NUMBER".equals(valueType)) {
            double value = rs.getDouble("numeric_value");
            if (rs.wasNull()) {
                return "";
            }
            return Math.rint(value) == value ? String.valueOf((long) value) : String.valueOf(value);
        }
        if ("BOOLEAN".equals(valueType)) {
            boolean value = rs.getBoolean("boolean_value");
            if (rs.wasNull()) {
                return "";
            }
            return String.valueOf(value);
        }
        return nullToEmpty(rs.getString("text_value"));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private long parseLong(String value) {
        return Long.parseLong(value.replace(",", ""));
    }

    private Double parseDoubleOrNull(String value) {
        try {
            return Double.parseDouble(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean parseBooleanOrNull(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "y", "present", "positive", "pos", "1" -> true;
            case "false", "no", "n", "absent", "negative", "neg", "0" -> false;
            default -> null;
        };
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

    private record AnnotationFilter(String key, List<String> values) {
    }

    private record SearchRow(
            long segmentId,
            String eventGroupId,
            String sampleAccessionId,
            String chromosome,
            long startPos,
            long stopPos,
            long cnvSize,
            String eventType,
            int copyNumber,
            String genomeBuild,
            String confidence,
            String rawSegmentText,
            String callingMethod,
            String sourceFile
    ) {
    }
}
