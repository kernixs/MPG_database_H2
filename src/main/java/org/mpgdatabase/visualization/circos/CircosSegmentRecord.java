package org.mpgdatabase.visualization.circos;

public record CircosSegmentRecord(
        String chr,
        long start,
        long stop,
        int value,
        String eventType,
        String confidence,
        long segmentId,
        long sampleTestResultId
) {
}
