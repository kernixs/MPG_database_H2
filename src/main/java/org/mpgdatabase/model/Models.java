package org.mpgdatabase.model;

public final class Models {
    private Models() {
    }

    public record Individual(long id, String mrn, String externalIdentifier) {
    }

    public record SampleAccession(long id, String accessionIdentifier, long individualId, String dnaSource) {
    }

    public record LabProtocol(long id, String technology, String manufacturer, String miscellaneous) {
    }

    public record SampleTest(long id, long sampleAccessionId, long labProtocolId, String testType) {
    }

    public record Pipeline(long id, String softwareName, String softwareVersion, String settingsUsed) {
    }

    public record SampleTestResult(
            long id,
            long sampleTestId,
            long pipelineId,
            Long sourceFileId,
            String genomeBuild,
            String callingMethod,
            String rawIscn,
            String annotationNames
    ) {
    }

    public record Karyotype(
            long id,
            long sampleTestResultId,
            String karyotypeText,
            Integer cloneNumber,
            Integer cellCount,
            String abnormalities
    ) {
    }

    public record GenomicSegment(
            long id,
            String eventGroupId,
            long sampleTestResultId,
            Long karyotypeId,
            String chromosome,
            long startPos,
            long stopPos,
            String eventType,
            int copyNumber,
            String genomeBuild,
            String confidence,
            String rawIscn,
            String rawSegmentText,
            String annotations
    ) {
    }

    public record SegmentAnnotation(
            long id,
            long segmentId,
            String annotationName,
            String textValue,
            Double numericValue,
            Boolean booleanValue,
            String valueType,
            String sourceColumn,
            int ordinalPosition
    ) {
    }

    public record SmallVariant(
            long id,
            String chromosome,
            long position,
            String variantId,
            String refAllele,
            String altAllele,
            String variantType,
            String genomeBuild,
            String normalizedKey
    ) {
    }

    public record SmallVariantSampleCall(
            long id,
            long smallVariantId,
            long sampleTestResultId,
            Double qual,
            String filterStatus,
            String genotype,
            Boolean phased,
            Integer refDepth,
            Integer altDepth,
            Integer totalDepth,
            Double genotypeQuality,
            Double alleleBalance,
            String formatKeys,
            String sampleValues,
            String infoRaw,
            String rawVcfLine,
            int lineNumber
    ) {
    }

    public record SmallVariantAnnotation(
            long id,
            long smallVariantId,
            String gene,
            String geneId,
            String transcript,
            String consequence,
            String impact,
            String hgvsC,
            String hgvsP,
            String clinvarStatus,
            Double populationAf,
            String annotationSource,
            String annotationVersion,
            String annotationRaw,
            Boolean isPrimaryTranscript
    ) {
    }

    public record ValidationIssue(long id, Long segmentId, String issueType, String issueMessage, String severity) {
    }

    public record VariantClassification(
            long id,
            long segmentId,
            String classificationLabel,
            String guidelineSystem,
            String guidelineVersion,
            Double evidenceScore,
            String evidenceSummary,
            String classifiedBy,
            String reviewStatus,
            boolean current,
            Long supersedesClassificationId
    ) {
    }

    public record SignedOutCall(
            long id,
            long segmentId,
            long classificationId,
            long individualId,
            long sampleTestResultId,
            String clinicalSignificance,
            String relevanceToIndication,
            String interpretationText,
            String signedOutStatus,
            String signedOutBy,
            String reportText,
            String reportVersion,
            Long amendedFromSignedOutCallId
    ) {
    }

    public record Note(
            long id,
            String targetTable,
            long targetId,
            String noteType,
            String noteText,
            String author
    ) {
    }
}
