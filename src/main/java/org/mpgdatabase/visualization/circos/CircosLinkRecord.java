package org.mpgdatabase.visualization.circos;

public record CircosLinkRecord(
        String chr1,
        long pos1,
        String chr2,
        long pos2,
        String linkType,
        String confidence,
        String eventGroupId,
        long linkId,
        long sampleTestResultId,
        int patientCount,
        int sampleCount,
        int eventCount
) {
}
