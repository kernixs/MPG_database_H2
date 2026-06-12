package org.mpgdatabase.importer;

import org.mpgdatabase.dao.CoreDao;
import org.mpgdatabase.dao.GenomicSegmentDao;
import org.mpgdatabase.model.Models.GenomicSegment;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
            Map<String, EventGroupState> eventGroups = new HashMap<>();
            Map<String, ResultContext> resultContexts = new HashMap<>();

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

                String rawGenomeBuild = resolveGenomeBuild(record, parsed, defaultGenomeBuild);
                if (hasMissingGenomeBuild(rawGenomeBuild)) {
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
                String genomeBuild = GenomeBuildNormalizer.normalize(rawGenomeBuild);
                if (genomeBuild == null) {
                    coreDao.createValidationIssue(
                            null,
                            sourceFileId,
                            record.lineNumber(),
                            record.sampleAccessionIdentifier(),
                            "Invalid Genome Build",
                            "Unsupported genome build '" + rawGenomeBuild + "' at line " + record.lineNumber(),
                            "ERROR");
                    issuesInserted++;
                    continue;
                }
                long individualId = coreDao.findOrCreateIndividual("IND-" + record.sampleAccessionIdentifier());
                long sampleId = coreDao.findOrCreateSampleAccession(
                        record.sampleAccessionIdentifier(),
                        individualId,
                        record.dnaSource());
                String callingMethod = recordCallingMethod(parsed.callingMethod(), record);
                String testType = testType(callingMethod);
                long labProtocolId = coreDao.findOrCreateLabProtocol(labProtocol(callingMethod), null, null);
                long pipelineId = coreDao.findOrCreatePipeline(pipeline(callingMethod), "phase2.5", null);
                ResultContext resultContext = resultContext(
                        resultContexts,
                        coreDao,
                        sampleId,
                        labProtocolId,
                        testType,
                        pipelineId,
                        sourceFileId,
                        genomeBuild,
                        callingMethod,
                        record);
                EventGroupState groupState = eventGroupState(eventGroups, record, sourceFileId, resultContext.resultId());
                String eventGroupId = groupState == null ? null : groupState.eventGroupLabel();
                long segmentId = segmentDao.create(new GenomicSegment(
                        0,
                        eventGroupId,
                        resultContext.resultId(),
                        resultContext.karyotypeId(),
                        record.chromosome(),
                        record.startPos(),
                        record.stopPos(),
                        record.eventType(),
                        record.copyNumber(),
                        genomeBuild,
                        record.confidence(),
                        record.arrayScore(),
                        record.numberOfSites(),
                        record.rawIscn(),
                        rawSegmentText(record),
                        record.annotations()
                ));
                segmentsInserted++;
                if (shouldLinkGroupedSegment(record, groupState)) {
                    coreDao.createGenomicLink(
                            null,
                            null,
                            groupState.eventGroupLabel(),
                            groupState.firstTransSegmentId(),
                            segmentId,
                            groupState.eventGroupType(),
                            null,
                            linkEvidence(record),
                            null);
                }
                if (groupState != null) {
                    groupState.addSegment(segmentId, record.eventType());
                }

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
            for (EventGroupState groupState : eventGroups.values()) {
                if (groupState.segmentCount() < 2) {
                    coreDao.createValidationIssue(
                            groupState.firstTransSegmentId(),
                            sourceFileId,
                            groupState.firstLineNumber(),
                            groupState.sampleAccessionId(),
                            "Incomplete Breakpoint Event Group",
                            groupState.eventGroupType() + " group " + groupState.eventGroupLabel()
                                    + " has fewer than 2 segments",
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

    private EventGroupState eventGroupState(
            Map<String, EventGroupState> eventGroups,
            CnvRecord record,
            Long sourceFileId,
            long resultId
    ) {
        if (!requiresEventGroup(record)) {
            return null;
        }
        String label = record.eventGroupId() == null || record.eventGroupId().isBlank()
                ? "AUTO-BREAKPOINT-" + sourceFileId + "-" + record.lineNumber()
                : record.eventGroupId();
        String groupKey = resultId + "|" + label;
        EventGroupState existing = eventGroups.get(groupKey);
        if (existing != null) {
            return existing;
        }
        EventGroupState created = new EventGroupState(
                record.eventGroupId(),
                label,
                eventGroupType(record.eventType()),
                record.sampleAccessionIdentifier(),
                record.lineNumber());
        eventGroups.put(groupKey, created);
        return created;
    }

    private ResultContext resultContext(
            Map<String, ResultContext> resultContexts,
            CoreDao coreDao,
            long sampleId,
            long labProtocolId,
            String testType,
            long pipelineId,
            Long sourceFileId,
            String genomeBuild,
            String callingMethod,
            CnvRecord record
    ) throws SQLException {
        String key = resultKey(sampleId, labProtocolId, testType, pipelineId, sourceFileId, genomeBuild, callingMethod, record);
        ResultContext existing = resultContexts.get(key);
        if (existing != null) {
            return existing;
        }
        long sampleTestId = coreDao.createSampleTest(sampleId, labProtocolId, testType);
        long resultId = coreDao.createSampleTestResult(
                sampleTestId,
                pipelineId,
                sourceFileId,
                genomeBuild,
                callingMethod,
                record.rawIscn(),
                record.annotationNames(),
                record.lineNumber());
        Long karyotypeId = record.hasRawIscn() && CallingMethodDetector.ISCN_DERIVED.equals(callingMethod)
                ? coreDao.createKaryotype(resultId, record.rawIscn())
                : null;
        ResultContext created = new ResultContext(resultId, karyotypeId);
        resultContexts.put(key, created);
        return created;
    }

    private String resultKey(
            long sampleId,
            long labProtocolId,
            String testType,
            long pipelineId,
            Long sourceFileId,
            String genomeBuild,
            String callingMethod,
            CnvRecord record
    ) {
        return sampleId + "|"
                + labProtocolId + "|"
                + testType + "|"
                + pipelineId + "|"
                + sourceFileId + "|"
                + genomeBuild + "|"
                + callingMethod + "|"
                + nullToEmpty(record.rawIscn()) + "|"
                + nullToEmpty(record.annotationNames());
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean shouldLinkGroupedSegment(CnvRecord record, EventGroupState groupState) {
        return groupState != null
                && groupState.firstTransSegmentId() != null;
    }

    private boolean requiresEventGroup(CnvRecord record) {
        return record.eventGroupId() != null && !record.eventGroupId().isBlank()
                || isBreakpointEvent(record.eventType());
    }

    private boolean isBreakpointEvent(String eventType) {
        return switch (eventType == null ? "" : eventType) {
            case "TRANS", "T", "INV", "INS", "DER", "DIC", "R", "RING", "COMPLEX" -> true;
            default -> false;
        };
    }

    private String eventGroupType(String eventType) {
        return switch (eventType == null ? "" : eventType) {
            case "TRANS", "T" -> "TRANSLOCATION";
            case "INV" -> "INVERSION";
            case "INS" -> "INSERTION";
            case "DER" -> "DERIVATIVE";
            case "DIC" -> "DICENTRIC";
            case "R", "RING" -> "RING";
            case "COMPLEX" -> "COMPLEX";
            default -> "GROUPED_EVENT";
        };
    }

    private String linkEvidence(CnvRecord record) {
        if (record.rawIscn() != null && !record.rawIscn().isBlank()) {
            return record.rawIscn();
        }
        return record.eventGroupId();
    }

    private String rawEventText(CnvRecord record) {
        String rawText;
        if (record.rawIscn() != null && !record.rawIscn().isBlank()) {
            rawText = record.rawIscn();
        } else {
            rawText = record.sourceFields().toString();
        }
        return rawText.length() <= 2000 ? rawText : rawText.substring(0, 2000);
    }

    private String rawSegmentText(CnvRecord record) {
        return rawEventText(record);
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
        return null;
    }

    private boolean hasMissingGenomeBuild(String genomeBuild) {
        return genomeBuild == null || genomeBuild.isBlank();
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
        if (record.sourceFields().containsKey("meanbaf")
                || record.sourceFields().containsKey("mean_baf")
                || record.sourceFields().containsKey("meanlrr")
                || record.sourceFields().containsKey("mean_lrr")
                || record.sourceFields().containsKey("lohscore")
                || record.sourceFields().containsKey("loh_score")
                || record.sourceFields().containsKey("rohscore")
                || record.sourceFields().containsKey("roh_score")
                || record.sourceFields().containsKey("bafshift")
                || record.sourceFields().containsKey("baf_shift")
                || record.sourceFields().containsKey("baf")
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

    private static final class EventGroupState {
        private final String eventGroupId;
        private final String eventGroupLabel;
        private final String eventGroupType;
        private final String sampleAccessionId;
        private final int firstLineNumber;
        private final List<Long> segmentIds = new ArrayList<>();
        private Long firstTransSegmentId;

        private EventGroupState(
                String eventGroupId,
                String eventGroupLabel,
                String eventGroupType,
                String sampleAccessionId,
                int firstLineNumber
        ) {
            this.eventGroupId = eventGroupId;
            this.eventGroupLabel = eventGroupLabel;
            this.eventGroupType = eventGroupType;
            this.sampleAccessionId = sampleAccessionId;
            this.firstLineNumber = firstLineNumber;
        }

        private String eventGroupId() {
            return eventGroupId;
        }

        private String eventGroupLabel() {
            return eventGroupLabel;
        }

        private String eventGroupType() {
            return eventGroupType;
        }

        private String sampleAccessionId() {
            return sampleAccessionId;
        }

        private int firstLineNumber() {
            return firstLineNumber;
        }

        private Long firstTransSegmentId() {
            return firstTransSegmentId;
        }

        private int segmentCount() {
            return segmentIds.size();
        }

        private void addSegment(long segmentId, String eventType) {
            segmentIds.add(segmentId);
            if (firstTransSegmentId == null) {
                firstTransSegmentId = segmentId;
            }
        }
    }

    private record ResultContext(long resultId, Long karyotypeId) {
    }
}
