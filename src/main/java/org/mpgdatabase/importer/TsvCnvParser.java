package org.mpgdatabase.importer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class TsvCnvParser implements CnvParser {

    private static final List<String> CORE_COLUMNS = List.of(
        "sample_accession_id",
        "event_group_id",
        "chromosome",
        "start_pos",
        "stop_pos",
        "event_type",
        "sv_type",
        "type",
        "copy_number",
        "confidence",
        "genome_build",
        "hg_version",
        "raw_iscn",
        "dna_source",
        "annotation_names",
        "annotations"
    );
    private static final List<String> VALID_EVENT_TYPES = List.of(
        "DEL",
        "DUP",
        "GAIN",
        "AMP",
        "LOSS",
        "CNV",
        "INV",
        "INS",
        "TRANS",
        "T",
        "ADD",
        "DER",
        "IDIC",
        "DIC",
        "I",
        "R",
        "RING",
        "COMPLEX",
        "ROH",
        "UPD",
        "TRISOMY",
        "MONOSOMY",
        "NEUTRAL"
    );

    private final String manualCallingMethod;

    public TsvCnvParser() {
        this(null);
    }

    public TsvCnvParser(String manualCallingMethod) {
        this.manualCallingMethod = manualCallingMethod;
    }

    @Override
    public ParsedCnvFile parse(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path);
        Map<String, String> metadata = new HashMap<>();
        List<CnvRecord> records = new ArrayList<>();
        List<String> header = null;
        List<String> originalHeader = null;
        boolean unsupportedRawArrayEvidence = false;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("#")) {
                readMetadata(line, metadata);
                continue;
            }
            if (header == null) {
                originalHeader = originalHeader(line);
                header = normalizeHeader(line);
                unsupportedRawArrayEvidence = isUnsupportedRawArrayEvidence(header);
                if (unsupportedRawArrayEvidence) {
                    records.add(invalidRecord(
                            i + 1,
                            "Unsupported Array Evidence File",
                            "File appears to be a raw array probe/manifest/evidence file, not a final called CNV interval file"));
                } else {
                    records.addAll(validateHeader(header, i + 1));
                }
                continue;
            }
            if (unsupportedRawArrayEvidence) {
                continue;
            }
            records.add(readRecord(header, originalHeader, line, i + 1));
        }

        if (header == null) {
            records.add(
                invalidRecord(
                    1,
                    "Missing Header",
                    "CNV file does not contain a TSV header"
                )
            );
        }
        String callingMethod =
            header == null
                ? CallingMethodDetector.UNKNOWN
                : CallingMethodDetector.detect(
                      metadata,
                      header,
                      path,
                      manualCallingMethod
                  );
        return new ParsedCnvFile(metadata, callingMethod, records);
    }

    private void readMetadata(String line, Map<String, String> metadata) {
        String trimmed = line.substring(1).trim();
        int equals = trimmed.indexOf('=');
        if (equals > 0) {
            String key = normalizeColumnName(trimmed.substring(0, equals));
            String value = trimmed.substring(equals + 1).trim();
            metadata.put(key, value);
            if (isGenomeBuildColumn(key)) {
                metadata.put("genome_build", normalizeGenomeBuildMetadataValue(value));
            }
        }
    }

    private List<String> normalizeHeader(String line) {
        String[] parts = line.split("\\t", -1);
        List<String> header = new ArrayList<>(parts.length);
        for (String part : parts) {
            header.add(normalizeColumnName(part));
        }
        return header;
    }

    private List<String> originalHeader(String line) {
        String[] parts = line.split("\\t", -1);
        List<String> header = new ArrayList<>(parts.length);
        for (String part : parts) {
            header.add(part.trim());
        }
        return header;
    }

    private String normalizeColumnName(String columnName) {
        String normalized = columnName.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\-]+", "_")
                .replaceAll("_+", "_");
        return switch (normalized) {
            case
                "sampleid",
                "sample_id",
                "sample",
                "accession",
                "accession_id",
                "fid" -> "sample_accession_id";
            case "group_id", "event_id", "variant_id", "pair_id", "link_id", "breakend_id" -> "event_group_id";
            case "iscn", "iid" -> "raw_iscn";
            case "chr" -> "chromosome";
            case "bp1", "start", "start_position" -> "start_pos";
            case "bp2", "stop", "end", "end_pos", "end_position" -> "stop_pos";
            case "cnv_type", "aberration", "eventtype" -> "event_type";
            case "sv_type" -> "sv_type";
            case "cn", "copy", "copynumber", "copy_number" -> "copy_number";
            case "genomebuild", "genome_build", "build", "assembly", "grid_genomicbuild" -> "genome_build";
            case "hg_version" -> "hg_version";
            case "probecount", "probe_count", "numprobes", "num_probes", "numberofprobes", "number_of_probes",
                    "numberofsites", "number_of_sites" -> "number_of_sites";
            case "arrayscore", "array_score", "score", "cnvscore" -> "array_score";
            case "confidencescore", "callconfidence" -> "confidence";
            default -> normalized;
        };
    }

    private List<CnvRecord> validateHeader(
        List<String> header,
        int lineNumber
    ) {
        List<CnvRecord> issues = new ArrayList<>();
        for (String required : List.of(
            "sample_accession_id",
            "chromosome",
            "start_pos",
            "stop_pos"
        )) {
            requireColumn(header, issues, lineNumber, required);
        }
        if (
            !header.contains("event_type") &&
            !header.contains("sv_type") &&
            !header.contains("type")
        ) {
            requireColumn(header, issues, lineNumber, "event_type");
        }
        return issues;
    }

    private boolean isUnsupportedRawArrayEvidence(List<String> header) {
        return containsAny(header,
                "probename",
                "systematicname",
                "pvaluelogratio",
                "gprocessedsignal",
                "rprocessedsignal",
                "allelea",
                "alleleb",
                "baf",
                "lrr",
                "mapinfo")
                && !hasCalledCnvInterval(header);
    }

    private boolean hasCalledCnvInterval(List<String> header) {
        return header.contains("sample_accession_id")
                && header.contains("chromosome")
                && header.contains("start_pos")
                && header.contains("stop_pos")
                && (header.contains("event_type") || header.contains("sv_type") || header.contains("type"));
    }

    private boolean containsAny(List<String> values, String... candidates) {
        for (String candidate : candidates) {
            if (values.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private void requireColumn(
        List<String> header,
        List<CnvRecord> issues,
        int lineNumber,
        String columnName
    ) {
        if (!header.contains(columnName)) {
            issues.add(
                invalidRecord(
                    lineNumber,
                    "Missing Required Column",
                    "Missing required column: " + columnName
                )
            );
        }
    }

    private CnvRecord readRecord(
        List<String> header,
        List<String> originalHeader,
        String line,
        int lineNumber
    ) {
        String[] values = line.split("\\t", -1);
        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, String> originalFields = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String value = i < values.length ? cleanValue(values[i]) : null;
            fields.put(header.get(i), value);
            originalFields.put(originalHeader.get(i), value);
        }

        String sample = fields.get("sample_accession_id");
        String eventGroupId = fields.get("event_group_id");
        String chromosome = normalizeChromosome(fields.get("chromosome"));
        Long start = parseLong(fields.get("start_pos"));
        Long stop = parseLong(fields.get("stop_pos"));
        String explicitEvent = firstNonBlank(
            fields.get("event_type"),
            fields.get("sv_type"),
            fields.get("type")
        );
        Integer copyNumber = resolveCopyNumber(
            fields.get("copy_number"),
            fields.get("type"),
            explicitEvent
        );
        String eventType = resolveEventType(
            fields.get("event_type"),
            fields.get("sv_type"),
            fields.get("type"),
            copyNumber
        );
        List<String> warningIssueTypes = new ArrayList<>(warningIssueTypes(fields.get("raw_iscn")));
        boolean unknownEventType = eventType == null || !isKnownEventType(eventType);
        if (unknownEventType) {
            eventType = "UNKNOWN";
            if (!warningIssueTypes.contains("Marker Chromosome Event")) {
                warningIssueTypes.add("Unknown Event Type");
            }
        }
        if (copyNumber == null && "UNKNOWN".equals(eventType)) {
            copyNumber = 2;
        }
        AnnotationPair annotationPair = annotationPair(header, originalHeader, originalFields, fields);
        String annotationNames = annotationPair.names();
        String annotations = annotationPair.values();

        String validation = null;
        String issueType = null;
        if (values.length != header.size()) {
            issueType = "Unparseable Row";
            validation =
                "Unparseable row at line " +
                lineNumber +
                ": expected " +
                header.size() +
                " columns but found " +
                values.length;
        } else if (sample == null) {
            issueType = "Unparseable Row";
            validation =
                "Unparseable row at line " +
                lineNumber +
                ": missing sample accession identifier";
        } else if (chromosome == null) {
            issueType = "Missing Chromosome";
            validation = "Missing chromosome at line " + lineNumber;
        } else if (fields.get("start_pos") == null) {
            issueType = "Missing Start Position";
            validation = "Missing start position at line " + lineNumber;
        } else if (fields.get("stop_pos") == null) {
            issueType = "Missing End Position";
            validation = "Missing end position at line " + lineNumber;
        } else if (start == null || stop == null || copyNumber == null) {
            issueType = "Unparseable Row";
            validation =
                "Unparseable row at line " +
                lineNumber +
                ": one or more numeric fields could not be parsed";
        } else if (start > stop) {
            issueType = "Invalid Interval";
            validation =
                "Invalid interval at line " +
                lineNumber +
                ": start_pos is greater than stop_pos";
        }
        if (countParts(annotationNames) != countParts(annotations)) {
            warningIssueTypes.add("Annotation Count Mismatch");
        }
        if (issueType != null) {
            fields.put("issue_type", issueType);
        }

        return new CnvRecord(
            lineNumber,
            sample,
            eventGroupId,
            chromosome,
            start,
            stop,
            eventType,
            copyNumber,
            parseDouble(fields.get("array_score")),
            fields.get("confidence"),
            parseInt(fields.get("number_of_sites")),
            firstNonBlank(fields.get("genome_build"), fields.get("hg_version")),
            fields.get("raw_iscn"),
            fields.get("dna_source"),
            annotationNames,
            annotations,
            fields,
            validation,
            warningIssueTypes
        );
    }

    private CnvRecord invalidRecord(
        int lineNumber,
        String issueType,
        String message
    ) {
        return new CnvRecord(
            lineNumber,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            Map.of("issue_type", issueType),
            message,
            List.of()
        );
    }

    private List<String> warningIssueTypes(String rawIscn) {
        if (rawIscn == null || rawIscn.isBlank()) {
            return List.of();
        }
        String normalized = rawIscn.toLowerCase(Locale.ROOT);
        List<String> warnings = new ArrayList<>();
        if (normalized.contains("mar(")
                || normalized.contains("marker(")
                || normalized.contains("+mar")
                || normalized.matches(".*(^|[,/])\\s*mar([,;/]|$).*")) {
            warnings.add("Marker Chromosome Event");
        }
        if (normalized.contains("?")) {
            warnings.add("Uncertain Breakpoint");
        }
        if (normalized.contains("/")) {
            warnings.add("Mosaic Karyotype");
        }
        return warnings;
    }

    private boolean isKnownEventType(String eventType) {
        String normalized = normalizeEvent(eventType);
        if (normalized == null) {
            return false;
        }
        return "UNKNOWN".equals(normalized) || VALID_EVENT_TYPES.contains(normalized) || normalized.matches("[+-](CHR)?[0-9XYM]+");
    }

    private String normalizeEvent(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private String normalizeChromosome(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.toLowerCase(Locale.ROOT).startsWith("chr")
            ? trimmed
            : "chr" + trimmed;
    }

    private String resolveEventType(
        String explicitEventType,
        String svType,
        String typeValue,
        Integer copyNumber
    ) {
        String normalized = normalizeEvent(explicitEventType);
        if (normalized != null) {
            return normalized;
        }
        normalized = normalizeEvent(svType);
        if (normalized != null) {
            return normalized;
        }
        if (typeValue != null && parseInt(typeValue) == null) {
            return normalizeEvent(typeValue);
        }
        if (copyNumber == null) {
            return null;
        }
        if (copyNumber > 2) {
            return "DUP";
        }
        if (copyNumber < 2) {
            return "LOSS";
        }
        return "NEUTRAL";
    }

    private Integer resolveCopyNumber(
        String copyNumber,
        String typeValue,
        String eventType
    ) {
        Integer parsed = parseInt(copyNumber);
        if (parsed != null) {
            return parsed;
        }
        parsed = parseInt(typeValue);
        if (parsed != null) {
            return parsed;
        }
        String normalizedEvent = normalizeEvent(eventType);
        if (
            "DUP".equals(normalizedEvent) ||
            "GAIN".equals(normalizedEvent) ||
            "AMP".equals(normalizedEvent)
        ) {
            return 3;
        }
        if ("DEL".equals(normalizedEvent) || "LOSS".equals(normalizedEvent)) {
            return 1;
        }
        if (
            "TRANS".equals(normalizedEvent) ||
            "T".equals(normalizedEvent) ||
            "INV".equals(normalizedEvent) ||
            "INS".equals(normalizedEvent) ||
            "DER".equals(normalizedEvent) ||
            "DIC".equals(normalizedEvent) ||
            "R".equals(normalizedEvent) ||
            "RING".equals(normalizedEvent) ||
            "COMPLEX".equals(normalizedEvent) ||
            "ROH".equals(normalizedEvent) ||
            "UPD".equals(normalizedEvent) ||
            "NEUTRAL".equals(normalizedEvent)
        ) {
            return 2;
        }
        if ("TRISOMY".equals(normalizedEvent)) {
            return 3;
        }
        if ("MONOSOMY".equals(normalizedEvent)) {
            return 1;
        }
        return null;
    }

    private AnnotationPair annotationPair(
        List<String> header,
        List<String> originalHeader,
        Map<String, String> originalFields,
        Map<String, String> fields
    ) {
        String explicitNames = fields.get("annotation_names");
        String explicitValues = fields.get("annotations");
        if (explicitNames != null && explicitValues != null) {
            return explicitAnnotationPair(explicitNames, explicitValues);
        }
        return new AnnotationPair(
                annotationNames(header, originalHeader, originalFields),
                annotations(header, originalHeader, originalFields));
    }

    private AnnotationPair explicitAnnotationPair(String rawNames, String rawValues) {
        String[] names = annotationParts(rawNames);
        String[] values = annotationParts(rawValues);
        List<String> keptNames = new ArrayList<>();
        List<String> keptValues = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            String name = names[i].trim();
            String value = i < values.length ? values[i].trim() : "";
            if (name.isBlank() || CORE_COLUMNS.contains(normalizeColumnName(name))) {
                continue;
            }
            keptNames.add(name);
            keptValues.add(value);
        }
        return new AnnotationPair(
                keptNames.isEmpty() ? null : String.join("|", keptNames),
                keptValues.isEmpty() ? null : String.join("|", keptValues));
    }

    private String annotationNames(
        List<String> header,
        List<String> originalHeader,
        Map<String, String> originalFields
    ) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            if (!CORE_COLUMNS.contains(header.get(i))) {
                names.add(originalHeader.get(i));
            }
        }
        return String.join("|", names);
    }

    private String annotations(
        List<String> header,
        List<String> originalHeader,
        Map<String, String> originalFields
    ) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            String value = nullToEmpty(originalFields.get(originalHeader.get(i)));
            if (!CORE_COLUMNS.contains(header.get(i))) {
                values.add(value);
            }
        }
        return String.join("|", values);
    }

    private int countParts(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        return annotationParts(value).length;
    }

    private String normalizeAnnotationList(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return String.join("|", annotationParts(value));
    }

    private String[] annotationParts(String value) {
        String delimiter = value.contains("|") ? "\\|" : ";";
        return value.split(delimiter, -1);
    }

    private record AnnotationPair(String names, String values) {
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String cleanValue(String value) {
        String cleaned = blankToNull(value == null ? null : value.trim());
        if (
            cleaned != null &&
            cleaned.length() >= 2 &&
            cleaned.startsWith("\"") &&
            cleaned.endsWith("\"")
        ) {
            return cleaned.substring(1, cleaned.length() - 1);
        }
        return cleaned;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private Long parseLong(String value) {
        try {
            return value == null
                ? null
                : Long.parseLong(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseInt(String value) {
        try {
            return value == null
                ? null
                : Integer.parseInt(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double parseDouble(String value) {
        try {
            return value == null ? null : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean isGenomeBuildColumn(String columnName) {
        return "genome_build".equals(columnName) || "hg_version".equals(columnName);
    }

    private String normalizeGenomeBuildMetadataValue(String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.split("[:;]");
        for (String part : parts) {
            if (GenomeBuildNormalizer.normalize(part) != null) {
                return part.trim();
            }
        }
        return value.trim();
    }
}
