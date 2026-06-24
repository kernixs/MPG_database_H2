package org.mpgdatabase.search;

public record SearchResultRow(
        long sampleTestResultId,
        String mrn,
        String sampleAccession,
        String dnaSource,
        String testType,
        String callingMethod,
        String genomeBuild,
        String sourceFile,
        int cnvGainCount,
        int cnvLossCount,
        int translocationCount,
        int snvCount
) {
    public boolean circosReady() {
        return cnvGainCount > 0 || cnvLossCount > 0 || translocationCount > 0;
    }
}
