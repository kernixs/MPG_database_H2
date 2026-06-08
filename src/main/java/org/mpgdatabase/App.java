package org.mpgdatabase;

import org.mpgdatabase.db.Database;
import org.mpgdatabase.importer.ClinicalDecisionImportResult;
import org.mpgdatabase.importer.ClinicalDecisionImportService;
import org.mpgdatabase.importer.CnvImportService;
import org.mpgdatabase.importer.CnvParserFactory;
import org.mpgdatabase.importer.ImportResult;
import org.mpgdatabase.report.VerificationReport;
import org.mpgdatabase.report.VerificationService;
import org.mpgdatabase.report.SearchService;
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
            "end",
            "event-id",
            "event-type",
            "gene",
            "genome-build",
            "jdbc-url",
            "sample",
            "start",
            "stop"
    );

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "search".equalsIgnoreCase(args[0])) {
            runSearch(args);
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
        List<ClinicalDecisionImportResult> clinicalImportResults = new ArrayList<>();

        try (Connection connection = Database.connect(jdbcUrl)) {
            try {
                Database.initialize(connection);
                initialized = true;
            } catch (Exception e) {
                System.out.println("Database initialization failed: " + e.getMessage());
            }

            CnvImportService importer = new CnvImportService(connection, new CnvParserFactory(manualCallingMethod));
            for (Path path : cnvFiles(dataDir)) {
                importResults.add(importer.importFile(path, null));
            }
            ClinicalDecisionImportService clinicalImporter = new ClinicalDecisionImportService(connection);
            for (Path path : clinicalDecisionFiles(dataDir)) {
                clinicalImportResults.add(clinicalImporter.importFile(path));
            }

            VerificationService verification = new VerificationService(connection, outputDir);
            VerificationReport report = verification.verify(initialized, importResults, clinicalImportResults);
            System.out.print(verification.terminalSummary(report));
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
            filters = searchFilters(args);
        } catch (IllegalArgumentException e) {
            System.out.println("Search argument error: " + e.getMessage());
            System.out.println("Use --annotation Key=Value, for example: --annotation Gene=CFTR");
            return;
        }
        try (Connection connection = Database.connect(jdbcUrl)) {
            System.out.print(new SearchService(connection).search(filters));
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

    private static Map<String, String> searchFilters(String[] args) {
        Map<String, String> filters = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String option = args[i];
            if (!option.startsWith("--")) {
                throw new IllegalArgumentException("unexpected value '" + option + "'");
            }
            String key = option.substring(2);
            if (!SEARCH_FILTERS.contains(key)) {
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

    private static String optionValue(String[] args, String optionName) {
        for (int i = 0; i < args.length - 1; i++) {
            if (optionName.equals(args[i])) {
                return args[i + 1];
            }
        }
        return null;
    }

    private static List<Path> cnvFiles(Path dataDir) throws Exception {
        if (Files.isRegularFile(dataDir)) {
            return List.of(dataDir);
        }
        if (!Files.isDirectory(dataDir)) {
            return List.of(
                    dataDir.resolve("example.cnv"),
                    dataDir.resolve("test.cnv")
            );
        }
        try (Stream<Path> paths = Files.list(dataDir)) {
            return paths
                    .filter(App::isImportableFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
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

    private static boolean isImportableFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        return fileName.endsWith(".cnv") || fileName.endsWith(".tsv") || fileName.endsWith(".txt");
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
}
