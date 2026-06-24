package org.mpgdatabase.visualization.circos;

import java.nio.file.Path;
import java.util.List;

public record CircosExportResult(
        long sampleTestResultId,
        List<Long> sampleTestResultIds,
        String plotLabel,
        String genomeBuild,
        String circlizeSpecies,
        Path exportDir,
        Path gainEventsTsv,
        Path lossEventsTsv,
        Path connectionsTsv,
        Path generatedScript,
        Path svgOutput,
        int gainCount,
        int lossCount,
        int translocationCount
) {
    public boolean hasPlotReadyEvents() {
        return gainCount > 0 || lossCount > 0 || translocationCount > 0;
    }
}
