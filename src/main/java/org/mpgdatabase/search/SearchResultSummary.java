package org.mpgdatabase.search;

import java.util.List;

public record SearchResultSummary(
        SearchScope scope,
        int patientCount,
        int sampleTestResultCount,
        int snvCount,
        int cnvGainCount,
        int cnvLossCount,
        int translocationCount,
        List<SearchResultRow> rows,
        List<String> unavailableFilters
) {
    public boolean circosAvailable() {
        return cnvGainCount > 0 || cnvLossCount > 0 || translocationCount > 0;
    }

    public String circosReason() {
        if (circosAvailable()) {
            return "CNV/SV plot-ready events found.";
        }
        return "No CNV gain/loss segments or translocation links found.";
    }
}
