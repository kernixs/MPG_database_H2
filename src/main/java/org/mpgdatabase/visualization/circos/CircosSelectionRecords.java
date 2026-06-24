package org.mpgdatabase.visualization.circos;

final class CircosSelectionRecords {
    private CircosSelectionRecords() {
    }

    record Patient(long individualId, String mrn, String externalIdentifier) {
        String displayName() {
            if (mrn != null && !mrn.isBlank()) {
                return "MRN " + mrn;
            }
            return externalIdentifier == null || externalIdentifier.isBlank()
                    ? "individual_id=" + individualId
                    : externalIdentifier;
        }
    }

    record SampleAccession(long sampleAccessionId, String accessionIdentifier, String dnaSource) {
    }

    record SampleTest(long sampleTestId, String testType, String technology, String manufacturer) {
        String displayName() {
            String lab = technology == null || technology.isBlank() ? "" : " | " + technology;
            String maker = manufacturer == null || manufacturer.isBlank() ? "" : " | " + manufacturer;
            return testType + lab + maker;
        }
    }

    record SampleTestResult(
            long sampleTestResultId,
            String genomeBuild,
            String callingMethod,
            String sourceFile,
            int cnvSegmentCount,
            int translocationCount
    ) {
    }
}
