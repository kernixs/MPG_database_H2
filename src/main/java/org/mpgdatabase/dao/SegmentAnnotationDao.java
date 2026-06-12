package org.mpgdatabase.dao;

import org.mpgdatabase.model.Models.SegmentAnnotation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SegmentAnnotationDao {
    private final Connection connection;

    public SegmentAnnotationDao(Connection connection) {
        this.connection = connection;
    }

    public void create(SegmentAnnotation annotation) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO segment_annotations
                    (segment_id, annotation_name, text_value, numeric_value, boolean_value,
                     value_type, source_column, ordinal_position)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setLong(1, annotation.segmentId());
            ps.setString(2, annotation.annotationName());
            ps.setString(3, annotation.textValue());
            if (annotation.numericValue() == null) {
                ps.setNull(4, Types.DOUBLE);
            } else {
                ps.setDouble(4, annotation.numericValue());
            }
            if (annotation.booleanValue() == null) {
                ps.setNull(5, Types.BOOLEAN);
            } else {
                ps.setBoolean(5, annotation.booleanValue());
            }
            ps.setString(6, annotation.valueType());
            ps.setString(7, annotation.sourceColumn());
            ps.setInt(8, annotation.ordinalPosition());
            ps.executeUpdate();
        }
    }

    public void createFromDelimited(long segmentId, String annotationNames, String annotations) throws SQLException {
        String[] names = splitParts(annotationNames);
        String[] values = splitParts(annotations);
        int rows = Math.min(names.length, values.length);
        for (int i = 0; i < rows; i++) {
            String sourceColumn = names[i].trim();
            String value = values[i].trim();
            if (sourceColumn.isBlank() || value.isBlank()) {
                continue;
            }
            create(toSegmentAnnotation(segmentId, sourceColumn, value, i + 1));
        }
    }

    public List<SegmentAnnotation> annotationsForSegment(long segmentId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT *
                FROM segment_annotations
                WHERE segment_id = ?
                ORDER BY ordinal_position, annotation_id
                """)) {
            ps.setLong(1, segmentId);
            List<SegmentAnnotation> rows = new ArrayList<>();
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new SegmentAnnotation(
                            rs.getLong("annotation_id"),
                            rs.getLong("segment_id"),
                            rs.getString("annotation_name"),
                            rs.getString("text_value"),
                            DaoSupport.nullableDouble(rs, "numeric_value"),
                            DaoSupport.nullableBoolean(rs, "boolean_value"),
                            rs.getString("value_type"),
                            rs.getString("source_column"),
                            rs.getInt("ordinal_position")
                    ));
                }
            }
            return rows;
        }
    }

    private SegmentAnnotation toSegmentAnnotation(long segmentId, String sourceColumn, String rawValue, int ordinal) {
        String annotationName = normalizeAnnotationName(sourceColumn);
        Double numericValue = parseDouble(rawValue);
        Boolean booleanValue = parseBoolean(rawValue);
        String valueType;
        String textValue = null;
        if (booleanValue != null && looksBooleanLike(sourceColumn, rawValue)) {
            valueType = "BOOLEAN";
        } else if (numericValue != null) {
            valueType = "NUMBER";
        } else {
            valueType = "TEXT";
            textValue = rawValue;
        }
        return new SegmentAnnotation(
                0,
                segmentId,
                annotationName,
                textValue,
                numericValue,
                booleanValue,
                valueType,
                sourceColumn,
                ordinal);
    }

    private String normalizeAnnotationName(String sourceColumn) {
        String compact = sourceColumn.trim()
                .replaceAll("[\\s\\-]+", "_")
                .replaceAll("_+", "_");
        String lower = compact.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "gene", "genes" -> "Gene";
            case "clinical", "classification", "class" -> sourceColumn.trim();
            case "lumpy" -> "Lumpy";
            case "cnvnator" -> "CNVNATOR";
            case "gnomad", "gnomad_count", "gnomad_sv_count" -> "gnomAD_count";
            case "dgv_pop_percent", "dgv_percent", "dgv_population_percent" -> "DGV_pop_percent";
            case "probecount", "probe_count", "numprobes", "num_probes",
                    "numberofprobes", "number_of_probes", "numberofsites", "number_of_sites" -> "probe_count";
            case "lrr", "meanlrr", "mean_lrr", "lrr_value" -> "LRR";
            case "baf", "meanbaf", "mean_baf", "baf_pattern" -> "BAF";
            default -> compact;
        };
    }

    private Double parseDouble(String value) {
        try {
            return Double.parseDouble(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean parseBoolean(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "true", "yes", "y", "present", "positive", "pos", "1" -> true;
            case "false", "no", "n", "absent", "negative", "neg", "0" -> false;
            default -> null;
        };
    }

    private boolean looksBooleanLike(String sourceColumn, String value) {
        String normalizedValue = value.trim().toLowerCase(Locale.ROOT);
        if (!List.of("true", "false", "yes", "no", "y", "n", "present", "absent",
                "positive", "negative", "pos", "neg").contains(normalizedValue)
                && !List.of("0", "1").contains(normalizedValue)) {
            return false;
        }
        String normalizedName = sourceColumn.trim().toLowerCase(Locale.ROOT);
        return normalizedName.startsWith("is_")
                || normalizedName.startsWith("has_")
                || normalizedName.endsWith("_flag")
                || normalizedName.endsWith("_status")
                || List.of("stitched", "is_valid").contains(normalizedName);
    }

    private String[] splitParts(String value) {
        if (value == null || value.isEmpty()) {
            return new String[0];
        }
        return value.split("[|;]", -1);
    }
}
