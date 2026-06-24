package org.mpgdatabase.visualization.circos;

import org.mpgdatabase.search.GenomicSearchService;
import org.mpgdatabase.search.SearchResultRow;
import org.mpgdatabase.search.SearchResultSummary;
import org.mpgdatabase.search.SearchScope;

import java.io.PrintStream;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class CircosWorkflow {
    private final Connection connection;
    private final CircosConfig config;
    private final Scanner input;
    private final PrintStream out;

    public CircosWorkflow(Connection connection, CircosConfig config, Scanner input, PrintStream out) {
        this.connection = connection;
        this.config = config;
        this.input = input;
        this.out = out;
    }

    public void run() throws Exception {
        new CircosCleanupService(config).ensureDirectories();
        out.println("MPG Genomic Search and Circos Generator");
        out.println("---------------------------------------");
        while (true) {
            out.println();
            out.println("1) Advanced genomic search");
            out.println("2) Exit");
            int choice = readChoice("Select workflow:", 2);
            if (choice == 2) {
                out.println("Goodbye.");
                return;
            }
            runAdvancedSearch();
        }
    }

    private void runAdvancedSearch() throws Exception {
        SearchScope scope = readScope();
        Map<String, String> filters = readFilters(scope);
        out.println();
        out.println("[1/5] Running database search");
        SearchResultSummary summary = new GenomicSearchService(connection).search(scope, filters);
        printSummary(summary);
        if (summary.rows().isEmpty()) {
            return;
        }
        while (true) {
            out.println();
            out.println("1) View matched results");
            out.println("2) Select result(s) and generate Circos");
            out.println("3) Start a new search");
            out.println("4) Exit");
            int choice = readChoice("Select next step:", 4);
            if (choice == 1) {
                printRows(summary.rows());
            } else if (choice == 2) {
                generateFromSelection(summary.rows());
                return;
            } else if (choice == 3) {
                return;
            } else {
                out.println("Goodbye.");
                System.exit(0);
            }
        }
    }

    private SearchScope readScope() {
        out.println();
        out.println("Search scope");
        out.println("1) CNV/SV only");
        out.println("2) SNV/indel only");
        out.println("3) All genomic results");
        int choice = readChoice("Select search scope:", 3);
        return switch (choice) {
            case 1 -> SearchScope.CNV_SV;
            case 2 -> SearchScope.SNV_INDEL;
            default -> SearchScope.ALL;
        };
    }

    private Map<String, String> readFilters(SearchScope scope) {
        Map<String, String> filters = new LinkedHashMap<>();
        out.println();
        out.println("Enter filters. Press Enter to skip any filter.");
        ask(filters, "mrn", "MRN");
        ask(filters, "accession_number", "Sample accession");
        ask(filters, "specimen_type", "Specimen/DNA source");
        ask(filters, "case_type", "Case type (planned)");
        ask(filters, "clinical_indication", "Clinical indication (planned)");
        ask(filters, "phenotype_terms", "Phenotype terms (planned)");
        ask(filters, "record_date_start", "Record date start (planned)");
        ask(filters, "record_date_end", "Record date end (planned)");
        ask(filters, "test_source_method", "Test/source method");
        ask(filters, "source_file_report", "Source file/report name");
        ask(filters, "genome_build", "Genome build, for example GRCh38");
        ask(filters, "review_status", "Review status (planned)");
        ask(filters, "gene", "Gene");
        ask(filters, "chromosome", "Chromosome, for example chr1");
        ask(filters, "region_start", "Region start");
        ask(filters, "region_end", "Region end");
        ask(filters, "clinical_significance", "Clinical significance (planned)");
        ask(filters, "omim_association", "OMIM association (planned)");
        ask(filters, "evidence_confidence", "Evidence/confidence");
        ask(filters, "raw_data_available", "Raw data available (planned)");

        if (scope == SearchScope.CNV_SV || scope == SearchScope.ALL) {
            out.println();
            out.println("CNV/SV filters");
            ask(filters, "sv_cnv_event_type", "SV/CNV event type, for example GAIN or LOSS");
            ask(filters, "cytoband_interval_start", "Cytoband interval start (planned)");
            ask(filters, "cytoband_interval_end", "Cytoband interval end (planned)");
            ask(filters, "event_size_bp_minimum", "Minimum event size bp");
            ask(filters, "event_size_bp_maximum", "Maximum event size bp");
            ask(filters, "copy_number", "Copy number");
            ask(filters, "parser_confidence_minimum", "Parser confidence minimum (planned)");
            ask(filters, "unsupported_parse_failure_reason", "Unsupported parse failure reason (planned)");
            ask(filters, "centromeric_repetitive_region_flag", "Centromeric/repetitive region flag (planned)");
            ask(filters, "internal_recurrence_count_minimum", "Internal recurrence count minimum (planned)");
        }

        if (scope == SearchScope.SNV_INDEL || scope == SearchScope.ALL) {
            out.println();
            out.println("SNV/indel filters");
            ask(filters, "variant_type", "Variant type");
            ask(filters, "genomic_position", "Genomic position");
            ask(filters, "reference_allele", "Reference allele");
            ask(filters, "alternate_allele", "Alternate allele");
            ask(filters, "transcript_id", "Transcript ID");
            ask(filters, "hgvs_cdna", "HGVS cDNA");
            ask(filters, "hgvs_protein", "HGVS protein");
            ask(filters, "molecular_consequence", "Molecular consequence");
            ask(filters, "zygosity", "Zygosity");
            ask(filters, "minimum_vaf", "Minimum VAF");
            ask(filters, "minimum_read_depth", "Minimum read depth");
            ask(filters, "caller_filter_status", "Caller filter status");
            ask(filters, "clinical_classification_source", "Clinical classification source (planned)");
            ask(filters, "variant_id", "Variant ID");
            ask(filters, "dbsnp_rsid", "dbSNP rsID");
            ask(filters, "annotation_source_version", "Annotation source/version (planned)");
            ask(filters, "preferred_transcript_only", "Preferred transcript only (planned)");
            ask(filters, "review_priority", "Review priority (planned)");
        }
        return filters;
    }

    private void ask(Map<String, String> filters, String key, String label) {
        out.println(label + ":");
        String line = readLine();
        if (line == null) {
            return;
        }
        String value = line.trim();
        if (!value.isBlank()) {
            filters.put(key, value);
        }
    }

    private void printSummary(SearchResultSummary summary) {
        out.println();
        out.println("Search summary");
        out.println("Scope: " + summary.scope());
        out.println("Patients: " + summary.patientCount());
        out.println("Sample test results: " + summary.sampleTestResultCount());
        out.println("SNV/indel calls: " + summary.snvCount());
        out.println("CNV gains: " + summary.cnvGainCount());
        out.println("CNV losses: " + summary.cnvLossCount());
        out.println("Translocations: " + summary.translocationCount());
        out.println("Circos: " + (summary.circosAvailable() ? "available" : "not available")
                + " - " + summary.circosReason());
        if (!summary.unavailableFilters().isEmpty()) {
            out.println("Skipped planned filters: " + String.join(", ", summary.unavailableFilters()));
        }
    }

    private void printRows(List<SearchResultRow> rows) {
        out.println();
        out.println("Matched results");
        for (int i = 0; i < rows.size(); i++) {
            SearchResultRow row = rows.get(i);
            out.println((i + 1) + ") result_id=" + row.sampleTestResultId()
                    + " | MRN=" + nullToDash(row.mrn())
                    + " | accession=" + nullToDash(row.sampleAccession())
                    + " | test=" + nullToDash(row.testType())
                    + " | build=" + nullToDash(row.genomeBuild())
                    + " | gains=" + row.cnvGainCount()
                    + " | losses=" + row.cnvLossCount()
                    + " | translocations=" + row.translocationCount()
                    + " | SNVs=" + row.snvCount()
                    + " | " + (row.circosReady() ? "Circos-ready" : "table-only"));
        }
    }

    private void generateFromSelection(List<SearchResultRow> rows) throws Exception {
        printRows(rows);
        out.println();
        out.println("Select result numbers separated by commas, or type all:");
        String line = readLine();
        if (line == null) {
            out.println("No selection entered.");
            return;
        }
        String selection = line.trim();
        List<SearchResultRow> selectedRows = selectedRows(rows, selection);
        List<Long> ids = selectedRows.stream()
                .filter(SearchResultRow::circosReady)
                .map(SearchResultRow::sampleTestResultId)
                .toList();
        int skipped = selectedRows.size() - ids.size();
        if (skipped > 0) {
            out.println("Skipped " + skipped + " selected table-only result(s) with no CNV/SV plot-ready events.");
        }
        if (ids.isEmpty()) {
            out.println("No selected results contain CNV gain/loss segments or translocation links.");
            return;
        }

        out.println();
        out.println("Generating Circos plot...");
        out.println("[2/5] Checking Circos readiness");
        CircosReadinessResult readiness = new CircosReadinessChecker(connection).check(ids);
        out.println("Selected results: " + ids.size());
        out.println("Genome build: " + nullToDash(readiness.genomeBuild()));
        out.println("Events: gains=" + readiness.cnvGainCount()
                + ", losses=" + readiness.cnvLossCount()
                + ", translocations=" + readiness.translocationCount()
                + ", SNVs/table-only=" + readiness.snvCount());
        if (!readiness.canGenerate()) {
            out.println("Cannot generate Circos plot: " + readiness.reason());
            if (readiness.mixedGenomeBuilds()) {
                out.println("Genome builds selected: " + String.join(", ", readiness.genomeBuilds()));
            }
            return;
        }

        out.println("[3/5] Exporting TSV files");
        CircosExportResult export = new CircosExportService(connection, config).export(ids);
        if (!export.hasPlotReadyEvents()) {
            out.println("No plot-ready events found.");
            return;
        }

        out.println("[4/5] Running R/circlize and writing SVG");
        try {
            new CircosRRunner(config).render(export);
        } catch (IllegalStateException e) {
            out.println("Circos TSV export completed, but SVG rendering did not finish.");
            out.println(e.getMessage());
            out.println("Exported TSV files are still available:");
            printExports(export);
            return;
        }

        out.println("[5/5] " + (config.cleanupTemporaryFiles() ? "Cleaning temporary files" : "Keeping TSV/R temporary files"));
        if (config.cleanupTemporaryFiles()) {
            new CircosCleanupService(config).cleanupTemporaryFiles(export);
        }
        out.println();
        out.println("Done.");
        out.println();
        out.println("Circos plot generated:");
        out.println(export.svgOutput().toAbsolutePath());
        out.println();
        printExports(export);
        out.println("Counts: gains=" + export.gainCount()
                + ", losses=" + export.lossCount()
                + ", translocations=" + export.translocationCount());
    }

    private List<SearchResultRow> selectedRows(List<SearchResultRow> rows, String selection) {
        if ("all".equalsIgnoreCase(selection)) {
            return rows;
        }
        List<SearchResultRow> selected = new ArrayList<>();
        String[] parts = selection.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            try {
                int index = Integer.parseInt(trimmed);
                if (index >= 1 && index <= rows.size()) {
                    selected.add(rows.get(index - 1));
                } else {
                    out.println("Ignoring out-of-range selection: " + trimmed);
                }
            } catch (NumberFormatException e) {
                out.println("Ignoring invalid selection: " + trimmed);
            }
        }
        return selected;
    }

    private void printExports(CircosExportResult export) {
        out.println("Exported TSV/script files:");
        out.println(export.gainEventsTsv().toAbsolutePath());
        out.println(export.lossEventsTsv().toAbsolutePath());
        out.println(export.connectionsTsv().toAbsolutePath());
        out.println(export.generatedScript().toAbsolutePath());
    }

    private int readChoice(String prompt, int max) {
        while (true) {
            out.println(prompt);
            String line = readLine();
            if (line == null) {
                return max;
            }
            String value = line.trim();
            try {
                int choice = Integer.parseInt(value);
                if (choice >= 1 && choice <= max) {
                    return choice;
                }
            } catch (NumberFormatException ignored) {
            }
            out.println("Enter a number from 1 to " + max + ".");
        }
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String readLine() {
        return input.hasNextLine() ? input.nextLine() : null;
    }
}
