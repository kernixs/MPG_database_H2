package org.mpgdatabase.visualization.circos;

import java.util.List;

public record CircosReadinessResult(
        boolean circosAvailable,
        boolean genomeBuildSupported,
        boolean mixedGenomeBuilds,
        String reason,
        int cnvGainCount,
        int cnvLossCount,
        int translocationCount,
        int snvCount,
        String genomeBuild,
        List<String> genomeBuilds
) {
    public boolean canGenerate() {
        return circosAvailable && genomeBuildSupported && !mixedGenomeBuilds;
    }
}
