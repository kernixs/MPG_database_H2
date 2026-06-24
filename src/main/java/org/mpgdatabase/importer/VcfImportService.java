package org.mpgdatabase.importer;

import org.mpgdatabase.dao.CoreDao;
import org.mpgdatabase.dao.SmallVariantDao;
import org.mpgdatabase.model.Models.SmallVariant;
import org.mpgdatabase.model.Models.SmallVariantAnnotation;
import org.mpgdatabase.model.Models.SmallVariantSampleCall;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class VcfImportService {
    private static final int FIXED_VCF_COLUMNS = 9;

    private final Connection connection;

    public VcfImportService(Connection connection) {
        this.connection = connection;
    }

    public VcfImportResult importFile(Path path, String defaultGenomeBuild) {
        int recordsSeen = 0;
        int variantsSeen = 0;
        int sampleCallsInserted = 0;
        int annotationsInserted = 0;
        int issuesInserted = 0;
        Long sourceFileId = null;
        try {
            ParsedVcf parsed = parse(path);
            CoreDao coreDao = new CoreDao(connection);
            SmallVariantDao smallVariantDao = new SmallVariantDao(connection);
            long pipelineId = coreDao.findOrCreatePipeline("VCF Importer", "phase3-small-variants", null);
            sourceFileId = coreDao.createSourceFile(
                    path.getFileName().toString(),
                    path.toAbsolutePath().toString(),
                    pipelineId,
                    "IN_PROGRESS",
                    0,
                    null);
            String genomeBuild = resolveGenomeBuild(parsed.metadataLines(), defaultGenomeBuild);
            if (genomeBuild == null) {
                for (VcfDataRow row : parsed.rows()) {
                    recordsSeen++;
                    coreDao.createValidationIssue(
                            null,
                            sourceFileId,
                            row.lineNumber(),
                            null,
                            "Missing Genome Build",
                            "Genome build could not be resolved for VCF row " + row.lineNumber(),
                            "ERROR");
                    issuesInserted++;
                }
                coreDao.updateSourceFileStatus(sourceFileId, "FAILED", recordsSeen);
                return new VcfImportResult(path.getFileName().toString(), true, recordsSeen, 0, 0, 0, issuesInserted);
            }

            Map<String, Long> resultIdsBySample = new HashMap<>();
            for (String sampleName : parsed.sampleNames()) {
                resultIdsBySample.put(sampleName, createResultForSample(coreDao, sampleName, sourceFileId, pipelineId, genomeBuild));
            }

            for (VcfDataRow row : parsed.rows()) {
                recordsSeen++;
                if (isUnsupportedStructuralVariant(row)) {
                    coreDao.createValidationIssue(
                            null,
                            sourceFileId,
                            row.lineNumber(),
                            null,
                            "Unsupported VCF Structural Variant",
                            "SV VCF records are recognized but not supported in V1 at VCF line "
                                    + row.lineNumber() + ": " + structuralVariantDescription(row),
                            "WARNING");
                    issuesInserted++;
                    continue;
                }
                List<String> rowIssues = rowValidationIssues(row);
                if (!rowIssues.isEmpty()) {
                    for (String issue : rowIssues) {
                        coreDao.createValidationIssue(
                                null,
                                sourceFileId,
                                row.lineNumber(),
                                null,
                                issue,
                                issue + " at VCF line " + row.lineNumber(),
                                "ERROR");
                        issuesInserted++;
                    }
                    continue;
                }
                String[] alts = row.alt().split(",", -1);
                for (int altIndex = 0; altIndex < alts.length; altIndex++) {
                    String alt = alts[altIndex];
                    alt = alt.trim();
                    if (alt.isBlank()) {
                        continue;
                    }
                    String chromosome = normalizeChromosome(row.chromosome());
                    String variantType = variantType(row.ref(), alt);
                    String normalizedKey = String.join("|", genomeBuild, chromosome,
                            String.valueOf(row.position()), row.ref(), alt);
                    long smallVariantId = smallVariantDao.findOrCreateVariant(new SmallVariant(
                            0,
                            chromosome,
                            row.position(),
                            blankDotToNull(row.variantId()),
                            row.ref(),
                            alt,
                            variantType,
                            genomeBuild,
                            normalizedKey));
                    variantsSeen++;
                    annotationsInserted += createAnnotations(smallVariantDao, smallVariantId, row.info(), parsed.snpeffVersion());

                    for (String sampleName : parsed.sampleNames()) {
                        String sampleValues = row.sampleValues().get(sampleName);
                        if (sampleValues == null || sampleValues.isBlank()) {
                            coreDao.createValidationIssue(
                                    null,
                                    sourceFileId,
                                    row.lineNumber(),
                                    sampleName,
                                    "Missing Genotype",
                                    "Missing sample values for " + sampleName + " at VCF line " + row.lineNumber(),
                                    "WARNING");
                            issuesInserted++;
                            continue;
                        }
                        ParsedSampleCall parsedSampleCall = parseSampleCall(row.format(), sampleValues, altIndex + 1);
                        if (parsedSampleCall.genotype() == null) {
                            coreDao.createValidationIssue(
                                    null,
                                    sourceFileId,
                                    row.lineNumber(),
                                    sampleName,
                                    "Missing Genotype",
                                    "GT was missing at VCF line " + row.lineNumber() + " for " + sampleName,
                                    "WARNING");
                            issuesInserted++;
                        }
                        smallVariantDao.createSampleCall(new SmallVariantSampleCall(
                                0,
                                smallVariantId,
                                resultIdsBySample.get(sampleName),
                                parseDouble(row.qual()),
                                blankDotToNull(row.filter()),
                                parsedSampleCall.genotype(),
                                parsedSampleCall.phased(),
                                parsedSampleCall.refDepth(),
                                parsedSampleCall.altDepth(),
                                parsedSampleCall.totalDepth(),
                                parsedSampleCall.genotypeQuality(),
                                parsedSampleCall.alleleBalance(),
                                row.format(),
                                sampleValues,
                                row.info(),
                                row.rawLine(),
                                row.lineNumber()));
                        sampleCallsInserted++;
                    }
                }
            }
            coreDao.updateSourceFileStatus(sourceFileId, importStatus(sampleCallsInserted, issuesInserted), recordsSeen);
            return new VcfImportResult(
                    path.getFileName().toString(),
                    true,
                    recordsSeen,
                    variantsSeen,
                    sampleCallsInserted,
                    annotationsInserted,
                    issuesInserted);
        } catch (Exception e) {
            try {
                CoreDao coreDao = new CoreDao(connection);
                if (sourceFileId != null) {
                    coreDao.updateSourceFileStatus(sourceFileId, "FAILED", recordsSeen);
                }
                coreDao.createValidationIssue(null, sourceFileId, null, null, "VCF Import Failure", e.getMessage(), "ERROR");
            } catch (SQLException ignored) {
                // Keep original failure visible in the result.
            }
            return new VcfImportResult(path.getFileName().toString(), false, recordsSeen, variantsSeen,
                    sampleCallsInserted, annotationsInserted, issuesInserted + 1);
        }
    }

    public static boolean looksLikeVcf(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".vcf") || fileName.endsWith(".vcf.txt")) {
            return true;
        }
        try {
            for (String line : Files.readAllLines(path)) {
                if (line.isBlank()) {
                    continue;
                }
                return line.startsWith("##fileformat=VCF") || line.startsWith("#CHROM");
            }
        } catch (IOException ignored) {
            return false;
        }
        return false;
    }

    private ParsedVcf parse(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path);
        List<String> metadataLines = new ArrayList<>();
        List<String> sampleNames = new ArrayList<>();
        List<VcfDataRow> rows = new ArrayList<>();
        String snpeffVersion = null;
        boolean foundHeader = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int lineNumber = i + 1;
            if (line.startsWith("##")) {
                metadataLines.add(line);
                if (line.toLowerCase(Locale.ROOT).contains("snpeffversion")) {
                    snpeffVersion = line;
                }
                continue;
            }
            if (line.startsWith("#CHROM")) {
                String[] header = line.split("\\t", -1);
                if (header.length > FIXED_VCF_COLUMNS) {
                    for (int j = FIXED_VCF_COLUMNS; j < header.length; j++) {
                        if (!header[j].isBlank()) {
                            sampleNames.add(header[j].trim());
                        }
                    }
                }
                foundHeader = true;
                continue;
            }
            if (!foundHeader || line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\t", -1);
            Map<String, String> samples = new LinkedHashMap<>();
            for (int j = 0; j < sampleNames.size(); j++) {
                int column = FIXED_VCF_COLUMNS + j;
                samples.put(sampleNames.get(j), column < parts.length ? parts[column] : null);
            }
            rows.add(new VcfDataRow(
                    lineNumber,
                    get(parts, 0),
                    parseLong(get(parts, 1)),
                    get(parts, 2),
                    get(parts, 3),
                    get(parts, 4),
                    get(parts, 5),
                    get(parts, 6),
                    get(parts, 7),
                    get(parts, 8),
                    samples,
                    line));
        }
        if (sampleNames.isEmpty()) {
            throw new IOException("VCF does not contain a sample column after FORMAT");
        }
        return new ParsedVcf(metadataLines, sampleNames, rows, snpeffVersion);
    }

    private long createResultForSample(CoreDao coreDao, String sampleName, Long sourceFileId, long pipelineId, String genomeBuild)
            throws SQLException {
        long individualId = coreDao.findOrCreateIndividual("MRN-" + sampleName, "IND-" + sampleName);
        long sampleId = coreDao.findOrCreateSampleAccession(sampleName, individualId, null);
        long labProtocolId = coreDao.findOrCreateLabProtocol("NGS", null, "VCF small variant import");
        long sampleTestId = coreDao.createSampleTest(sampleId, labProtocolId, "VCF");
        return coreDao.createSampleTestResult(
                sampleTestId,
                pipelineId,
                sourceFileId,
                genomeBuild,
                "VCF-small-variant",
                null,
                null,
                null);
    }

    private String resolveGenomeBuild(List<String> metadataLines, String defaultGenomeBuild) {
        for (String line : metadataLines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("##reference=") || lower.startsWith("##assembly=")) {
                String normalized = GenomeBuildNormalizer.normalize(line.substring(line.indexOf('=') + 1));
                if (normalized != null) {
                    return normalized;
                }
            }
        }
        for (String line : metadataLines) {
            String normalized = GenomeBuildNormalizer.normalize(line);
            if (normalized != null) {
                return normalized;
            }
        }
        return GenomeBuildNormalizer.normalize(defaultGenomeBuild);
    }

    private List<String> rowValidationIssues(VcfDataRow row) {
        List<String> issues = new ArrayList<>();
        if (row.chromosome() == null || row.chromosome().isBlank()) {
            issues.add("Missing Chromosome");
        }
        if (row.position() == null) {
            issues.add("Invalid POS");
        }
        if (row.ref() == null || row.ref().isBlank() || ".".equals(row.ref())) {
            issues.add("Missing REF");
        }
        if (row.alt() == null || row.alt().isBlank() || ".".equals(row.alt())) {
            issues.add("Missing ALT");
        }
        return issues;
    }

    private boolean isUnsupportedStructuralVariant(VcfDataRow row) {
        if (row.alt() != null) {
            for (String alt : row.alt().split(",", -1)) {
                String trimmed = alt.trim();
                if (isSymbolicOrBreakendAlt(trimmed)) {
                    return true;
                }
            }
        }
        String svType = structuralVariantType(row.info());
        return svType != null && List.of(
                "BND",
                "CNV",
                "CPX",
                "CTX",
                "DEL",
                "DUP",
                "INS",
                "INV",
                "TRA",
                "TRANS"
        ).contains(svType.toUpperCase(Locale.ROOT));
    }

    private boolean isSymbolicOrBreakendAlt(String alt) {
        if (alt == null || alt.isBlank()) {
            return false;
        }
        return (alt.startsWith("<") && alt.endsWith(">"))
                || alt.contains("[")
                || alt.contains("]");
    }

    private String structuralVariantType(String info) {
        if (info == null || info.isBlank() || ".".equals(info)) {
            return null;
        }
        Map<String, String> fields = parseInfo(info);
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if ("SVTYPE".equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String structuralVariantDescription(VcfDataRow row) {
        String svType = structuralVariantType(row.info());
        if (svType == null || svType.isBlank()) {
            return "ALT=" + row.alt();
        }
        return "SVTYPE=" + svType + "; ALT=" + row.alt();
    }

    private ParsedSampleCall parseSampleCall(String format, String sampleValues, int altDepthIndex) {
        if (format == null || format.isBlank()) {
            return new ParsedSampleCall(null, null, null, null, null, null, null);
        }
        String[] keys = format.split(":", -1);
        String[] values = sampleValues == null ? new String[0] : sampleValues.split(":", -1);
        Map<String, String> parsed = new HashMap<>();
        for (int i = 0; i < keys.length; i++) {
            parsed.put(keys[i], i < values.length ? values[i] : null);
        }
        String genotype = blankDotToNull(parsed.get("GT"));
        Boolean phased = genotype == null ? null : genotype.contains("|");
        Integer totalDepth = parseInt(parsed.get("DP"));
        Integer genotypeQuality = parseInt(parsed.get("GQ"));
        Integer refDepth = null;
        Integer altDepth = null;
        String ad = parsed.get("AD");
        if (ad != null && !ad.isBlank()) {
            String[] depths = ad.split(",", -1);
            refDepth = depths.length > 0 ? parseInt(depths[0]) : null;
            altDepth = depths.length > altDepthIndex ? parseInt(depths[altDepthIndex]) : null;
        }
        Double alleleBalance = altDepth == null || totalDepth == null || totalDepth == 0
                ? null
                : (double) altDepth / totalDepth;
        return new ParsedSampleCall(
                genotype,
                phased,
                refDepth,
                altDepth,
                totalDepth,
                genotypeQuality == null ? null : genotypeQuality.doubleValue(),
                alleleBalance);
    }

    private int createAnnotations(SmallVariantDao dao, long smallVariantId, String info, String snpeffVersion)
            throws SQLException {
        if (info == null || info.isBlank() || ".".equals(info)) {
            return 0;
        }
        int inserted = 0;
        Map<String, String> infoFields = parseInfo(info);
        String ann = infoFields.get("ANN");
        if (ann != null && !ann.isBlank()) {
            for (String entry : ann.split(",", -1)) {
                String[] parts = entry.split("\\|", -1);
                dao.createAnnotation(new SmallVariantAnnotation(
                        0,
                        smallVariantId,
                        part(parts, 3),
                        part(parts, 4),
                        part(parts, 6),
                        part(parts, 1),
                        part(parts, 2),
                        part(parts, 9),
                        part(parts, 10),
                        null,
                        null,
                        "SnpEff",
                        snpeffVersion,
                        entry,
                        false));
                inserted++;
            }
        }
        for (String key : List.of("LOF", "NMD")) {
            String value = infoFields.get(key);
            if (value != null && !value.isBlank()) {
                dao.createAnnotation(new SmallVariantAnnotation(
                        0,
                        smallVariantId,
                        null,
                        null,
                        null,
                        key,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "SnpEff",
                        snpeffVersion,
                        value,
                        false));
                inserted++;
            }
        }
        return inserted;
    }

    private Map<String, String> parseInfo(String info) {
        Map<String, String> fields = new HashMap<>();
        for (String part : info.split(";", -1)) {
            if (part.isBlank()) {
                continue;
            }
            int equals = part.indexOf('=');
            if (equals < 0) {
                fields.put(part, "true");
            } else {
                fields.put(part.substring(0, equals), part.substring(equals + 1));
            }
        }
        return fields;
    }

    private String variantType(String ref, String alt) {
        if (ref.length() == 1 && alt.length() == 1) {
            return "SNV";
        }
        if (ref.length() < alt.length()) {
            return "INSERTION";
        }
        if (ref.length() > alt.length()) {
            return "DELETION";
        }
        return "INDEL";
    }

    private String normalizeChromosome(String chromosome) {
        String value = chromosome.trim();
        return value.toLowerCase(Locale.ROOT).startsWith("chr") ? value : "chr" + value;
    }

    private String importStatus(int sampleCallsInserted, int issuesInserted) {
        if (sampleCallsInserted == 0) {
            return "FAILED";
        }
        return issuesInserted == 0 ? "SUCCESS" : "PARTIAL_SUCCESS";
    }

    private String get(String[] parts, int index) {
        return index < parts.length ? parts[index] : null;
    }

    private String part(String[] parts, int index) {
        if (index >= parts.length || parts[index].isBlank()) {
            return null;
        }
        return parts[index];
    }

    private Long parseLong(String value) {
        try {
            return value == null || value.isBlank() ? null : Long.parseLong(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseInt(String value) {
        try {
            return value == null || value.isBlank() || ".".equals(value) ? null : Integer.parseInt(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double parseDouble(String value) {
        try {
            return value == null || value.isBlank() || ".".equals(value) ? null : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String blankDotToNull(String value) {
        return value == null || value.isBlank() || ".".equals(value) ? null : value;
    }

    private record ParsedVcf(
            List<String> metadataLines,
            List<String> sampleNames,
            List<VcfDataRow> rows,
            String snpeffVersion
    ) {
    }

    private record VcfDataRow(
            int lineNumber,
            String chromosome,
            Long position,
            String variantId,
            String ref,
            String alt,
            String qual,
            String filter,
            String info,
            String format,
            Map<String, String> sampleValues,
            String rawLine
    ) {
    }

    private record ParsedSampleCall(
            String genotype,
            Boolean phased,
            Integer refDepth,
            Integer altDepth,
            Integer totalDepth,
            Double genotypeQuality,
            Double alleleBalance
    ) {
    }
}
