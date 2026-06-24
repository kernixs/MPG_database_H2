package org.mpgdatabase.visualization.circos;

import java.io.IOException;
import java.nio.file.Files;

public class CircosCleanupService {
    private final CircosConfig config;

    public CircosCleanupService(CircosConfig config) {
        this.config = config;
    }

    public void ensureDirectories() throws IOException {
        Files.createDirectories(config.exportDir());
        Files.createDirectories(config.outputDir());
        Files.createDirectories(config.templateDir());
        Files.createDirectories(config.logDir());
    }

    public void cleanupTemporaryFiles(CircosExportResult export) throws IOException {
        Files.deleteIfExists(export.gainEventsTsv());
        Files.deleteIfExists(export.lossEventsTsv());
        Files.deleteIfExists(export.connectionsTsv());
        Files.deleteIfExists(export.generatedScript());
        try (var entries = Files.list(export.exportDir())) {
            if (entries.findAny().isEmpty()) {
                Files.deleteIfExists(export.exportDir());
            }
        }
    }
}
