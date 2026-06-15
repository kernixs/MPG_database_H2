package org.mpgdatabase;

import org.mpgdatabase.db.Database;
import org.mpgdatabase.importer.ClinicalDecisionImportResult;
import org.mpgdatabase.importer.ClinicalDecisionImportService;
import org.mpgdatabase.importer.ImportManager;
import org.mpgdatabase.importer.ImportResult;
import org.mpgdatabase.importer.VcfImportResult;
import org.mpgdatabase.report.VerificationReport;
import org.mpgdatabase.report.VerificationService;
import org.mpgdatabase.report.SearchService;
import org.mpgdatabase.report.SmallVariantSearchService;
import org.h2.tools.Server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public class App {
    private static final Set<String> SEARCH_FILTERS = Set.of(
            "annotation",
            "calling-method",
            "chromosome",
            "class",
            "confidence",
            "cnv-size-max",
            "cnv-size-min",
            "end",
            "event-group",
            "event-type",
            "gene",
            "genome-build",
            "jdbc-url",
            "max-size",
            "min-size",
            "sample",
            "start",
            "stop"
    );
    private static final Set<String> VCF_SEARCH_FILTERS = Set.of(
            "allele-balance-max",
            "allele-balance-min",
            "alt-depth-max",
            "alt-depth-min",
            "chromosome",
            "consequence",
            "end",
            "filter-status",
            "gene",
            "genome-build",
            "genotype",
            "impact",
            "jdbc-url",
            "max-allele-balance",
            "max-alt-depth",
            "max-total-depth",
            "min-allele-balance",
            "min-alt-depth",
            "min-total-depth",
            "sample",
            "start",
            "stop",
            "total-depth-max",
            "total-depth-min",
            "variant-id",
            "variant-type"
    );

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "search".equalsIgnoreCase(args[0])) {
            runSearch(args);
            return;
        }
        if (args.length > 0 && "vcf-search".equalsIgnoreCase(args[0])) {
            runVcfSearch(args);
            return;
        }
        if (args.length > 0 && "console".equalsIgnoreCase(args[0])) {
            runConsole(args);
            return;
        }
        if (args.length > 0 && "clinical-import".equalsIgnoreCase(args[0])) {
            runClinicalImport(args);
            return;
        }
        Path dataDir = Path.of(args.length > 0 ? args[0] : "data");
        Path outputDir = Path.of(args.length > 1 ? args[1] : "output");
        String jdbcUrl = args.length > 2 ? args[2] : "jdbc:h2:file:./output/mpg_database_h2";
        String manualCallingMethod = optionValue(args, "--calling-method-override");

        boolean initialized = false;
        List<ImportResult> importResults = new ArrayList<>();
        List<VcfImportResult> vcfImportResults = new ArrayList<>();
        List<ClinicalDecisionImportResult> clinicalImportResults = new ArrayList<>();

        try (Connection connection = Database.connect(jdbcUrl)) {
            try {
                Database.initialize(connection);
                initialized = true;
            } catch (Exception e) {
                System.out.println("Database initialization failed: " + e.getMessage());
            }

            ImportManager.GenomicImportResults genomicImportResults =
                    new ImportManager(connection, manualCallingMethod).importGenomicFiles(dataDir);
            importResults.addAll(genomicImportResults.cnvResults());
            vcfImportResults.addAll(genomicImportResults.vcfResults());
            ClinicalDecisionImportService clinicalImporter = new ClinicalDecisionImportService(connection);
            for (Path path : clinicalDecisionFiles(dataDir)) {
                clinicalImportResults.add(clinicalImporter.importFile(path));
            }

            VerificationService verification = new VerificationService(connection, outputDir);
            VerificationReport report = verification.verify(initialized, importResults, vcfImportResults, clinicalImportResults);
            System.out.print(verification.terminalSummary(report));
            printVcfImportResults(vcfImportResults);
            printClinicalImportResults(clinicalImportResults);
        }
    }

    private static void runConsole(String[] args) throws Exception {
        String jdbcUrl = optionValue(args, "--jdbc-url");
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            jdbcUrl = "jdbc:h2:file:./output/mpg_database_h2";
        }
        String port = optionValue(args, "--port");
        if (port == null || port.isBlank()) {
            port = "8082";
        }

        Files.createDirectories(Path.of("output"));
        try (Connection connection = Database.connect(jdbcUrl)) {
            Database.initialize(connection);
        }

        Server server = Server.createWebServer("-web", "-webPort", port).start();
        System.out.println("H2 Web Console: " + server.getURL());
        System.out.println("JDBC URL: " + jdbcUrl);
        System.out.println("User: <leave blank>");
        System.out.println("Password: <leave blank>");
        System.out.println("Press Ctrl+C to stop the console.");
        Thread.currentThread().join();
    }

    private static void runSearch(String[] args) throws Exception {
        String jdbcUrl = optionValue(args, "--jdbc-url");
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            jdbcUrl = "jdbc:h2:file:./output/mpg_database_h2";
        }
        Map<String, String> filters;
        try {
            filters = searchFilters(args, SEARCH_FILTERS);
        } catch (IllegalArgumentException e) {
            System.out.println("Search argument error: " + e.getMessage());
            System.out.println("Use --annotation Key=Value, for example: --annotation Gene=CFTR");
            return;
        }
        try (Connection connection = Database.connect(jdbcUrl)) {
            System.out.print(new SearchService(connection).search(filters));
        }
    }

    private static void runVcfSearch(String[] args) throws Exception {
        String jdbcUrl = optionValue(args, "--jdbc-url");
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            jdbcUrl = "jdbc:h2:file:./output/mpg_database_h2";
        }
        Map<String, String> filters;
        try {
            filters = searchFilters(args, VCF_SEARCH_FILTERS);
        } catch (IllegalArgumentException e) {
            System.out.println("VCF search argument error: " + e.getMessage());
            System.out.println("Use --gene OR4F5, --consequence missense_variant, or --chromosome chr1 --start 69000 --stop 70000");
            return;
        }
        normalizeVcfAliases(filters);
        try (Connection connection = Database.connect(jdbcUrl)) {
            System.out.print(new SmallVariantSearchService(connection).search(filters));
        }
    }

    private static void runClinicalImport(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: clinical-import <clinical_decisions.tsv> [--jdbc-url jdbc:h2:file:./output/mpg_database_h2]");
            return;
        }
        String jdbcUrl = optionValue(args, "--jdbc-url");
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            jdbcUrl = "jdbc:h2:file:./output/mpg_database_h2";
        }
        String outputDir = optionValue(args, "--output-dir");
        if (outputDir == null || outputDir.isBlank()) {
            outputDir = "output";
        }
        Path input = Path.of(args[1]);
        try (Connection connection = Database.connect(jdbcUrl)) {
            Database.initialize(connection);
            var result = new ClinicalDecisionImportService(connection).importFile(input);
            System.out.println("Clinical decision file imported: " + (result.success() ? "PASS" : "FAIL"));
            System.out.println("file=" + result.fileName());
            System.out.println("records=" + result.recordsSeen());
            System.out.println("classifications=" + result.classificationsInserted());
            System.out.println("signed_out_calls=" + result.signedOutCallsInserted());
            System.out.println("notes=" + result.notesInserted());

            VerificationService verification = new VerificationService(connection, Path.of(outputDir));
            VerificationReport report = verification.verify(true, List.of(), List.of(result));
            System.out.print(verification.terminalSummary(report));
        }
    }

    private static Map<String, String> searchFilters(String[] args, Set<String> allowedFilters) {
        Map<String, String> filters = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String option = args[i];
            if (!option.startsWith("--")) {
                throw new IllegalArgumentException("unexpected value '" + option + "'");
            }
            String key = option.substring(2);
            if (!allowedFilters.contains(key)) {
                throw new IllegalArgumentException("unknown search option '--" + key + "'");
            }
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("missing value for '--" + key + "'");
            }
            String value = args[i + 1];
            if ("annotation".equals(key) && filters.containsKey(key)) {
                filters.put(key, filters.get(key) + "\n" + value);
            } else {
                filters.put(key, value);
            }
            i++;
        }
        filters.remove("jdbc-url");
        return filters;
    }

    private static void normalizeVcfAliases(Map<String, String> filters) {
        moveAlias(filters, "allele-balance-min", "min-allele-balance");
        moveAlias(filters, "allele-balance-max", "max-allele-balance");
        moveAlias(filters, "alt-depth-min", "min-alt-depth");
        moveAlias(filters, "alt-depth-max", "max-alt-depth");
        moveAlias(filters, "total-depth-min", "min-total-depth");
        moveAlias(filters, "total-depth-max", "max-total-depth");
    }

    private static void moveAlias(Map<String, String> filters, String alias, String canonical) {
        String value = filters.remove(alias);
        if (value != null && !value.isBlank() && !filters.containsKey(canonical)) {
            filters.put(canonical, value);
        }
    }

    private static String optionValue(String[] args, String optionName) {
        for (int i = 0; i < args.length - 1; i++) {
            if (optionName.equals(args[i])) {
                return args[i + 1];
            }
        }
        return null;
    }

    private static List<Path> clinicalDecisionFiles(Path dataDir) throws Exception {
        if (!Files.isDirectory(dataDir)) {
            return List.of();
        }
        Path clinicalDir = dataDir.resolve("clinical");
        if (!Files.isDirectory(clinicalDir)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(clinicalDir)) {
            return paths
                    .filter(App::isClinicalDecisionFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private static boolean isClinicalDecisionFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        return fileName.endsWith(".tsv") || fileName.endsWith(".txt");
    }

    private static void printClinicalImportResults(List<ClinicalDecisionImportResult> clinicalImportResults) {
        if (clinicalImportResults.isEmpty()) {
            return;
        }
        System.out.println("\nClinical Decision Import Status\n-------------------------------");
        for (ClinicalDecisionImportResult result : clinicalImportResults) {
            System.out.println(result.fileName() + " imported: " + (result.success() ? "PASS" : "FAIL"));
            System.out.println("records=" + result.recordsSeen()
                    + " classifications=" + result.classificationsInserted()
                    + " signed_out_calls=" + result.signedOutCallsInserted()
                    + " notes=" + result.notesInserted());
        }
    }

    private static void printVcfImportResults(List<VcfImportResult> vcfImportResults) {
        if (vcfImportResults.isEmpty()) {
            return;
        }
        System.out.println("\nVCF Import Status\n-----------------");
        for (VcfImportResult result : vcfImportResults) {
            System.out.println(result.fileName() + " imported: " + (result.success() ? "PASS" : "FAIL"));
            System.out.println("records=" + result.recordsSeen()
                    + " variants=" + result.variantsInsertedOrReused()
                    + " sample_calls=" + result.sampleCallsInserted()
                    + " annotations=" + result.annotationsInserted()
                    + " issues=" + result.issuesInserted());
        }
    }
}
