package org.mpgdatabase.report;

import org.mpgdatabase.dao.CoreDao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
                    gs.event_id,
                    gs.genomic_event_group_id,
                    geg.event_group_label,
                    gs.segment_id,
                    sa.accession_identifier AS sample_accession_id,
                    gs.chromosome,
                    gs.start_pos,
                    gs.stop_pos,
                    gs.event_type,
                    gs.copy_number,
                    str.calling_method,
                    str.genome_build,
                    gs.confidence,
                    sf.file_name AS source_file,
                    str.annotation_names,
                    gs.annotations
                FROM genomic_segments gs
                LEFT JOIN genomic_event_groups geg ON geg.genomic_event_group_id = gs.genomic_event_group_id
                JOIN sample_test_results str ON str.sample_test_result_id = gs.sample_test_result_id
                JOIN sample_tests st ON st.sample_test_id = str.sample_test_id
                JOIN sample_accessions sa ON sa.sample_accession_id = st.sample_accession_id
                LEFT JOIN source_files sf ON sf.source_file_id = str.source_file_id
                WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        addValues(sql, params, filters, "sample", "sa.accession_identifier");
        addValues(sql, params, filters, "event-id", "gs.event_id");
        addValues(sql, params, filters, "event-group", "geg.event_group_label");
        addValues(sql, params, filters, "event-type", "gs.event_type");
        addValues(sql, params, filters, "chromosome", "gs.chromosome");
        addValues(sql, params, filters, "calling-method", "str.calling_method");
        addValues(sql, params, filters, "genome-build", "str.genome_build");
        addValues(sql, params, filters, "confidence", "gs.confidence");
        if (filters.containsKey("start") || filters.containsKey("stop") || filters.containsKey("end")) {
            long start = parseLong(filters.getOrDefault("start", "0"));
            long stop = parseLong(filters.getOrDefault("end", filters.getOrDefault("stop", String.valueOf(Long.MAX_VALUE))));
            sql.append(" AND gs.start_pos <= ? AND gs.stop_pos >= ?\n");
            params.add(stop);
            params.add(start);
        }
        sql.append(" ORDER BY gs.chromosome, gs.start_pos, gs.stop_pos, gs.segment_id");

        List<AnnotationFilter> annotationFilters = annotationFilters(filters);
        List<SearchRow> rows;
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object value = params.get(i);
                if (value instanceof Long longValue) {
                    ps.setLong(i + 1, longValue);
                } else {
                    ps.setString(i + 1, value.toString());
                }
            }
            rows = rows(ps);
        }

        if (!annotationFilters.isEmpty()) {
            rows = rows.stream()
                    .filter(row -> matchesAnnotations(row, annotationFilters))
                    .toList();
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

    private boolean matchesAnnotations(SearchRow row, List<AnnotationFilter> filters) {
        String[] names = splitAnnotationParts(row.annotationNames());
        String[] values = splitAnnotationParts(row.annotations());
        if (names.length != values.length) {
            createAnnotationMismatchWarning(row);
            return false;
        }

        Map<String, String> annotationMap = new LinkedHashMap<>();
        for (int i = 0; i < names.length; i++) {
            annotationMap.put(names[i].trim().toLowerCase(Locale.ROOT), values[i].trim());
        }
        for (AnnotationFilter filter : filters) {
            String actualValue = annotationMap.get(filter.key().toLowerCase(Locale.ROOT));
            if (actualValue == null || filter.values().stream().noneMatch(value -> value.equals(actualValue))) {
                return false;
            }
        }
        return true;
    }

    private void createAnnotationMismatchWarning(SearchRow row) {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT COUNT(*) FROM validation_issues
                WHERE segment_id = ? AND issue_type = 'Annotation Count Mismatch'
                """)) {
            ps.setLong(1, row.segmentId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getLong(1) > 0) {
                    return;
                }
            }
            new CoreDao(connection).createValidationIssue(
                    row.segmentId(),
                    "Annotation Count Mismatch",
                    "annotation_names count does not match annotations count for segment " + row.segmentId(),
                    "WARNING");
        } catch (SQLException ignored) {
            // Search should never fail just because a validation warning could not be recorded.
        }
    }

    private String[] splitAnnotationParts(String value) {
        if (value == null || value.isEmpty()) {
            return new String[0];
        }
        return value.split(";", -1);
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
                        nullableLong(rs, "event_id"),
                        nullableLong(rs, "genomic_event_group_id"),
                        rs.getString("event_group_label"),
                        rs.getString("sample_accession_id"),
                        rs.getString("chromosome"),
                        rs.getLong("start_pos"),
                        rs.getLong("stop_pos"),
                        rs.getString("event_type"),
                        rs.getInt("copy_number"),
                        rs.getString("calling_method"),
                        rs.getString("genome_build"),
                        rs.getString("confidence"),
                        rs.getString("source_file"),
                        rs.getString("annotation_names"),
                        rs.getString("annotations")
                ));
            }
        }
        return rows;
    }

    private String table(List<SearchRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("EVENT_ID\tGENOMIC_EVENT_GROUP_ID\tEVENT_GROUP_LABEL\tSEGMENT_ID\tSAMPLE_ACCESSION_ID\tCHROMOSOME\tSTART_POS\tSTOP_POS\tEVENT_TYPE\tCOPY_NUMBER\tCALLING_METHOD\tGENOME_BUILD\tCONFIDENCE\tSOURCE_FILE\tANNOTATION_NAMES\tANNOTATIONS\tMATCHED_ANNOTATIONS\n");
        for (SearchRow row : rows) {
            sb.append(row.eventId() == null ? "" : row.eventId()).append('\t')
                    .append(row.genomicEventGroupId() == null ? "" : row.genomicEventGroupId()).append('\t')
                    .append(nullToEmpty(row.eventGroupLabel())).append('\t')
                    .append(row.segmentId()).append('\t')
                    .append(nullToEmpty(row.sampleAccessionId())).append('\t')
                    .append(nullToEmpty(row.chromosome())).append('\t')
                    .append(row.startPos()).append('\t')
                    .append(row.stopPos()).append('\t')
                    .append(nullToEmpty(row.eventType())).append('\t')
                    .append(row.copyNumber()).append('\t')
                    .append(nullToEmpty(row.callingMethod())).append('\t')
                    .append(nullToEmpty(row.genomeBuild())).append('\t')
                    .append(nullToEmpty(row.confidence())).append('\t')
                    .append(nullToEmpty(row.sourceFile())).append('\t')
                    .append(nullToEmpty(row.annotationNames())).append('\t')
                    .append(nullToEmpty(row.annotations())).append('\t')
                    .append(readableAnnotations(row))
                    .append('\n');
        }
        sb.append("\nRows: ").append(rows.size()).append('\n');
        return sb.toString();
    }

    private String readableAnnotations(SearchRow row) {
        String[] names = splitAnnotationParts(row.annotationNames());
        String[] values = splitAnnotationParts(row.annotations());
        if (names.length != values.length) {
            return "";
        }
        List<String> pairs = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            pairs.add(names[i].trim() + "=" + values[i].trim());
        }
        return String.join("; ", pairs);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private long parseLong(String value) {
        return Long.parseLong(value.replace(",", ""));
    }

    private String normalizeChromosome(String value) {
        return value.toLowerCase(Locale.ROOT).startsWith("chr") ? value : "chr" + value;
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record AnnotationFilter(String key, List<String> values) {
    }

    private record SearchRow(
            long segmentId,
            Long eventId,
            Long genomicEventGroupId,
            String eventGroupLabel,
            String sampleAccessionId,
            String chromosome,
            long startPos,
            long stopPos,
            String eventType,
            int copyNumber,
            String callingMethod,
            String genomeBuild,
            String confidence,
            String sourceFile,
            String annotationNames,
            String annotations
    ) {
    }
}
