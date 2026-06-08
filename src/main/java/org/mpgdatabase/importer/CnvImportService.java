package org.mpgdatabase.importer;

import org.mpgdatabase.dao.CoreDao;
import org.mpgdatabase.dao.GenomicSegmentDao;
import org.mpgdatabase.model.Models.GenomicSegment;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

public class CnvImportService {
    private final Connection connection;
    private final CnvParserFactory parserFactory;

    public CnvImportService(Connection connection, CnvParserFactory parserFactory) {
        this.connection = connection;
        this.parserFactory = parserFactory;
    }

    public ImportResult importFile(Path path, String defaultGenomeBuild) {
        int recordsSeen = 0;
        int segmentsInserted = 0;
        int issuesInserted = 0;
        Long sourceFileId = null;
        try {
            ParsedCnvFile parsed = parserFactory.parserFor(path).parse(path);
            CoreDao coreDao = new CoreDao(connection);
            GenomicSegmentDao segmentDao = new GenomicSegmentDao(connection);
            String fileCallingMethod = CallingMethodDetector.UNKNOWN.equals(parsed.callingMethod())
                    ? CallingMethodDetector.UNKNOWN
                    : parsed.callingMethod();
            long filePipelineId = coreDao.findOrCreatePipeline(pipeline(fileCallingMethod), "phase2.5", null);
            sourceFileId = coreDao.createSourceFile(
                    path.getFileName().toString(),
                    path.toAbsolutePath().toString(),
                    filePipelineId,
                    "IN_PROGRESS",
                    0,
                    null);

            for (CnvRecord record : parsed.records()) {
                recordsSeen++;
                if (!record.validForSegment()) {
                    coreDao.createValidationIssue(
                            null,
                            sourceFileId,
                            record.lineNumber(),
                            record.sampleAccessionIdentifier(),
                            issueType(record),
                            record.validationMessage(),
                            "ERROR");
                    issuesInserted++;
                    continue;
                }

                String genomeBuild = resolveGenomeBuild(record, parsed, defaultGenomeBuild);
                long individualId = coreDao.findOrCreateIndividual("IND-" + record.sampleAccessionIdentifier());
                long sampleId = coreDao.findOrCreateSampleAccession(
                        record.sampleAccessionIdentifier(),
                        individualId,
                        record.dnaSource());
                String callingMethod = recordCallingMethod(parsed.callingMethod(), record);
                String testType = testType(callingMethod);
                long labProtocolId = coreDao.findOrCreateLabProtocol(labProtocol(callingMethod), null, null);
                long sampleTestId = coreDao.createSampleTest(sampleId, labProtocolId, testType);
                long pipelineId = coreDao.findOrCreatePipeline(pipeline(callingMethod), "phase2.5", null);
                long resultId = coreDao.createSampleTestResult(
                        sampleTestId,
                        pipelineId,
                        sourceFileId,
                        hasMissingGenomeBuild(genomeBuild) ? "unknown" : genomeBuild,
                        callingMethod,
                        record.rawIscn(),
                        record.annotationNames(),
                        record.lineNumber());
                if (hasMissingGenomeBuild(genomeBuild)) {
                    coreDao.createValidationIssue(
                            null,
                            sourceFileId,
                            record.lineNumber(),
                            record.sampleAccessionIdentifier(),
                            "Missing Genome Build",
                            "Genome build is required before inserting genomic coordinates for line " + record.lineNumber(),
                            "ERROR");
                    issuesInserted++;
                    continue;
                }
                Long karyotypeId = record.hasRawIscn() && CallingMethodDetector.ISCN_DERIVED.equals(callingMethod)
                        ? coreDao.createKaryotype(resultId, record.rawIscn())
                        : null;
                long segmentId = segmentDao.create(new GenomicSegment(
                        0,
                        resultId,
                        karyotypeId,
                        record.chromosome(),
                        record.startPos(),
                        record.stopPos(),
                        null,
                        null,
                        record.eventType(),
                        record.copyNumber(),
                        record.arrayScore(),
                        null,
                        record.numberOfSites(),
                        record.annotations()
                ));
                segmentsInserted++;

                if (CallingMethodDetector.UNKNOWN.equals(callingMethod)) {
                    coreDao.createValidationIssue(
                            segmentId,
                            sourceFileId,
                            record.lineNumber(),
                            record.sampleAccessionIdentifier(),
                            "Unknown Calling Method",
                            "File type could not be detected",
                            "WARNING");
                    issuesInserted++;
                }
                for (String warningIssueType : record.warningIssueTypes()) {
                    coreDao.createValidationIssue(
                            segmentId,
                            sourceFileId,
                            record.lineNumber(),
                            record.sampleAccessionIdentifier(),
                            warningIssueType,
                            warningMessage(warningIssueType, record),
                            "WARNING");
                    issuesInserted++;
                }
            }
            coreDao.updateSourceFileStatus(sourceFileId, importStatus(segmentsInserted, issuesInserted), recordsSeen);
            return new ImportResult(path.getFileName().toString(), true, recordsSeen, segmentsInserted, issuesInserted);
        } catch (Exception e) {
            try {
                CoreDao coreDao = new CoreDao(connection);
                if (sourceFileId != null) {
                    coreDao.updateSourceFileStatus(sourceFileId, "FAILED", recordsSeen);
                }
                coreDao.createValidationIssue(null, "Import Failure", e.getMessage(), "ERROR");
            } catch (SQLException ignored) {
                // Keep the original import failure visible in the result.
            }
            return new ImportResult(path.getFileName().toString(), false, recordsSeen, segmentsInserted, issuesInserted + 1);
        }
    }

    private String resolveGenomeBuild(CnvRecord record, ParsedCnvFile parsed, String defaultGenomeBuild) {
        if (record.genomeBuild() != null && !record.genomeBuild().isBlank()) {
            return record.genomeBuild();
        }
        String metadataBuild = parsed.metadata().get("genome_build");
        if (metadataBuild != null && !metadataBuild.isBlank()) {
            return metadataBuild;
        }
        if (defaultGenomeBuild != null && !defaultGenomeBuild.isBlank()) {
            return defaultGenomeBuild;
        }
        return "unknown";
    }

    private boolean hasMissingGenomeBuild(String genomeBuild) {
        return genomeBuild == null || genomeBuild.isBlank() || "unknown".equalsIgnoreCase(genomeBuild);
    }

    private String importStatus(int segmentsInserted, int issuesInserted) {
        if (segmentsInserted == 0) {
            return "FAILED";
        }
        return issuesInserted == 0 ? "SUCCESS" : "PARTIAL_SUCCESS";
    }

    private String warningMessage(String warningIssueType, CnvRecord record) {
        if ("Unknown Event Type".equals(warningIssueType)) {
            return "Event type could not be mapped cleanly at line " + record.lineNumber()
                    + "; genomic coordinates were imported with event_type = UNKNOWN";
        }
        if ("Marker Chromosome Event".equals(warningIssueType)) {
            return "Marker chromosome event detected in raw ISCN at line " + record.lineNumber()
                    + "; marker events are not parsed as a supported segment event type in Phase 2.5";
        }
        if ("Annotation Count Mismatch".equals(warningIssueType)) {
            return "annotation_names count does not match annotations count at line " + record.lineNumber()
                    + "; segment was imported because core CNV fields were usable";
        }
        return warningIssueType + " detected in raw ISCN at line " + record.lineNumber();
    }

    private String issueType(CnvRecord record) {
        String issueType = record.sourceFields().get("issue_type");
        return issueType == null ? "Unparseable Row" : issueType;
    }

    private String recordCallingMethod(String detectedCallingMethod, CnvRecord record) {
        if (record.hasRawIscn()) {
            return CallingMethodDetector.ISCN_DERIVED;
        }
        if (record.sourceFields().containsKey("read_depth")
                || record.sourceFields().containsKey("coverage")
                || record.sourceFields().containsKey("bin_count")
                || record.sourceFields().containsKey("lumpy")
                || record.sourceFields().containsKey("cnvnator")
                || record.sourceFields().containsKey("sv_type")) {
            return CallingMethodDetector.NGS_DERIVED;
        }
        if (record.sourceFields().containsKey("baf")
                || record.sourceFields().containsKey("lrr")
                || record.sourceFields().containsKey("probe_count")) {
            return CallingMethodDetector.SNP_ARRAY_DERIVED;
        }
        if (record.sourceFields().containsKey("array_score") || record.sourceFields().containsKey("number_of_sites")) {
            return CallingMethodDetector.ARRAY_DERIVED;
        }
        return detectedCallingMethod;
    }

    private String testType(String callingMethod) {
        return switch (callingMethod) {
            case CallingMethodDetector.ISCN_DERIVED -> "ISCN";
            case CallingMethodDetector.ARRAY_DERIVED, CallingMethodDetector.SNP_ARRAY_DERIVED -> "Array";
            case CallingMethodDetector.NGS_DERIVED -> "NGS";
            case CallingMethodDetector.GENERIC_CNV -> "CNV";
            default -> "Unknown";
        };
    }

    private String labProtocol(String callingMethod) {
        return switch (callingMethod) {
            case CallingMethodDetector.ISCN_DERIVED -> "Karyotype";
            case CallingMethodDetector.ARRAY_DERIVED -> "Array";
            case CallingMethodDetector.SNP_ARRAY_DERIVED -> "SNP Array";
            case CallingMethodDetector.NGS_DERIVED -> "WGS";
            case CallingMethodDetector.GENERIC_CNV -> "Generic CNV";
            default -> "Unknown";
        };
    }

    private String pipeline(String callingMethod) {
        return switch (callingMethod) {
            case CallingMethodDetector.ISCN_DERIVED -> "ISCN Parser";
            case CallingMethodDetector.ARRAY_DERIVED -> "Array CNV Caller";
            case CallingMethodDetector.SNP_ARRAY_DERIVED -> "SNP Array CNV Caller";
            case CallingMethodDetector.NGS_DERIVED -> "NGS SV Caller";
            case CallingMethodDetector.GENERIC_CNV -> "Generic CNV Importer";
            default -> "Unknown Importer";
        };
    }
}
