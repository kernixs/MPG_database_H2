package org.mpgdatabase.importer;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CallingMethodDetector {
    public static final String ISCN_DERIVED = "ISCN-derived";
    public static final String ARRAY_DERIVED = "Array-derived";
    public static final String SNP_ARRAY_DERIVED = "SNP-array-derived";
    public static final String NGS_DERIVED = "NGS-derived";
    public static final String GENERIC_CNV = "Generic CNV";
    public static final String UNKNOWN = "Unknown";

    private CallingMethodDetector() {
    }

    public static String detect(Map<String, String> metadata, List<String> normalizedHeader, Path path, String manualOverride) {
        if (manualOverride != null && !manualOverride.isBlank()) {
            return manualOverride;
        }
        String metadataType = firstPresent(metadata, "calling_method", "file_type", "assay_type", "data_type");
        if (metadataType != null) {
            return normalizeCallingMethod(metadataType);
        }
        if (containsAny(normalizedHeader, "raw_iscn", "karyotype_text")) {
            return ISCN_DERIVED;
        }
        if (containsAny(normalizedHeader, "read_depth", "coverage", "bin_count")) {
            return NGS_DERIVED;
        }
        if (containsAll(normalizedHeader, "sv_type", "hg_version")
                || containsAny(normalizedHeader, "lumpy", "cnvnator")) {
            return NGS_DERIVED;
        }
        if (containsAll(normalizedHeader, "chromosome", "start_pos", "stop_pos")
                && containsAny(normalizedHeader, "meanbaf", "mean_baf", "meanlrr", "mean_lrr",
                "lohscore", "loh_score", "rohscore", "roh_score", "bafshift", "baf_shift")) {
            return SNP_ARRAY_DERIVED;
        }
        if (containsAny(normalizedHeader, "baf", "lrr", "probe_count")) {
            return SNP_ARRAY_DERIVED;
        }
        if (containsAll(normalizedHeader, "chromosome", "start_pos", "stop_pos")
                && containsAny(normalizedHeader, "logratio", "log_ratio", "meanlogratio", "mean_log_ratio")) {
            return ARRAY_DERIVED;
        }
        if (containsAny(normalizedHeader, "array_score", "number_of_sites")) {
            return ARRAY_DERIVED;
        }
        if (containsAll(normalizedHeader, "chromosome", "start_pos", "stop_pos")
                && (containsAny(normalizedHeader, "event_type", "sv_type", "type"))
                && (containsAny(normalizedHeader, "copy_number", "type"))) {
            return GENERIC_CNV;
        }
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.contains("wgs") || fileName.contains("ngs")) {
            return NGS_DERIVED;
        }
        if (fileName.contains("array")) {
            return ARRAY_DERIVED;
        }
        if (fileName.contains("iscn")) {
            return ISCN_DERIVED;
        }
        return UNKNOWN;
    }

    private static String firstPresent(Map<String, String> metadata, String... keys) {
        for (String key : keys) {
            String value = metadata.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String normalizeCallingMethod(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "iscn", "iscn-derived", "karyotype" -> ISCN_DERIVED;
            case "array", "array-derived" -> ARRAY_DERIVED;
            case "snp-array", "snp array", "snp-array-derived" -> SNP_ARRAY_DERIVED;
            case "ngs", "wgs", "ngs-derived", "wgs-derived" -> NGS_DERIVED;
            case "generic", "generic cnv", "generic-cnv" -> GENERIC_CNV;
            default -> value.trim();
        };
    }

    private static boolean containsAny(List<String> values, String... needles) {
        for (String needle : needles) {
            if (values.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAll(List<String> values, String... needles) {
        for (String needle : needles) {
            if (!values.contains(needle)) {
                return false;
            }
        }
        return true;
    }
}
