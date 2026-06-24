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
    public static final int DEFAULT_LINK_LIMIT = 50;
    public static final int HARD_LINK_LIMIT = 100;

    public static CircosConfig defaults() {
        Path root = Path.of("circos");
        return new CircosConfig(
                root.resolve("exports"),
                root.resolve("output"),
                root.resolve("templates"),
                root.resolve("logs"),
                DEFAULT_LINK_LIMIT,
                false);
    }

    public CircosConfig withCleanupTemporaryFiles(boolean cleanup) {
        return new CircosConfig(exportDir, outputDir, templateDir, logDir, maxLinks, cleanup);
    }

    public CircosConfig withMaxLinks(int limit) {
        int bounded = Math.max(1, Math.min(limit, HARD_LINK_LIMIT));
        return new CircosConfig(exportDir, outputDir, templateDir, logDir, bounded, cleanupTemporaryFiles);
    }
}
