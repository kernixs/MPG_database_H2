package org.mpgdatabase.visualization.circos;

import java.nio.file.Path;

public record CircosConfig(
        Path exportDir,
        Path outputDir,
        Path templateDir,
        Path logDir,
        int maxLinks,
        boolean cleanupTemporaryFiles
) {
    public static CircosConfig defaults() {
        Path root = Path.of("circos");
        return new CircosConfig(
                root.resolve("exports"),
                root.resolve("output"),
                root.resolve("templates"),
                root.resolve("logs"),
                100,
                false);
    }

    public CircosConfig withCleanupTemporaryFiles(boolean cleanup) {
        return new CircosConfig(exportDir, outputDir, templateDir, logDir, maxLinks, cleanup);
    }
}
