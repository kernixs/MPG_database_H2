package org.mpgdatabase.importer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public class ImportManager {
    private final Connection connection;
    private final String manualCallingMethod;

    public ImportManager(Connection connection, String manualCallingMethod) {
        this.connection = connection;
        this.manualCallingMethod = manualCallingMethod;
    }

    public GenomicImportResults importGenomicFiles(Path dataDir) throws Exception {
        List<ImportResult> cnvResults = new ArrayList<>();
        CnvImportService cnvImporter = new CnvImportService(connection, new CnvParserFactory(manualCallingMethod));
        for (Path path : cnvFiles(dataDir)) {
            cnvResults.add(cnvImporter.importFile(path, null));
        }

        List<VcfImportResult> vcfResults = new ArrayList<>();
        VcfImportService vcfImporter = new VcfImportService(connection);
        for (Path path : vcfFiles(dataDir)) {
            vcfResults.add(vcfImporter.importFile(path, null));
        }

        return new GenomicImportResults(cnvResults, vcfResults);
    }

    private List<Path> cnvFiles(Path dataDir) throws Exception {
        if (Files.isRegularFile(dataDir)) {
            return VcfImportService.looksLikeVcf(dataDir) ? List.of() : List.of(dataDir);
        }
        if (!Files.isDirectory(dataDir)) {
            return List.of(
                    dataDir.resolve("example.cnv"),
                    dataDir.resolve("test.cnv")
            );
        }
        try (Stream<Path> paths = Files.list(dataDir)) {
            return paths
                    .filter(this::isImportableCnvFile)
                    .filter(path -> !VcfImportService.looksLikeVcf(path))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private List<Path> vcfFiles(Path dataDir) throws Exception {
        if (Files.isRegularFile(dataDir)) {
            return VcfImportService.looksLikeVcf(dataDir) ? List.of(dataDir) : List.of();
        }
        if (!Files.isDirectory(dataDir)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(dataDir)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(VcfImportService::looksLikeVcf)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private boolean isImportableCnvFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        return fileName.endsWith(".cnv") || fileName.endsWith(".tsv") || fileName.endsWith(".txt");
    }

    public record GenomicImportResults(
            List<ImportResult> cnvResults,
            List<VcfImportResult> vcfResults
    ) {
    }
}
