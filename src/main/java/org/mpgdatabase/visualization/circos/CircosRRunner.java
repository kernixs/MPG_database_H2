package org.mpgdatabase.visualization.circos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class CircosRRunner {
    private final CircosConfig config;

    public CircosRRunner(CircosConfig config) {
        this.config = config;
    }

    public void render(CircosExportResult export) throws IOException, InterruptedException {
        verifyRDependencies();
        String template = Files.readString(config.templateDir().resolve("circos_template.R"), StandardCharsets.UTF_8);
        String script = template
                .replace("__SPECIES__", rString(export.circlizeSpecies()))
                .replace("__GAIN_TSV__", rString(export.gainEventsTsv().toAbsolutePath().toString()))
                .replace("__LOSS_TSV__", rString(export.lossEventsTsv().toAbsolutePath().toString()))
                .replace("__CONNECTIONS_TSV__", rString(export.connectionsTsv().toAbsolutePath().toString()))
                .replace("__SVG_OUTPUT__", rString(export.svgOutput().toAbsolutePath().toString()))
                .replace("__PLOT_TITLE__", rString("CNV and Translocation Circos Plot"))
                .replace("__PLOT_SUBTITLE__", rString(export.plotLabel()
                        + " | genome_build=" + export.genomeBuild()
                        + " | results=" + export.sampleTestResultIds()));
        Files.writeString(export.generatedScript(), script, StandardCharsets.UTF_8);
        runRscript(export.generatedScript(), export);
    }

    private void verifyRDependencies() throws IOException, InterruptedException {
        Process process = new ProcessBuilder(
                "Rscript",
                "-e",
                "if (!requireNamespace('circlize', quietly=TRUE) || !requireNamespace('svglite', quietly=TRUE)) quit(status=2)")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int status = process.waitFor();
        if (status != 0) {
            throw new IllegalStateException("""
                    R with the circlize and svglite packages is required to render Circos plots.
                    Install R packages with: install.packages(c("circlize", "svglite"))
                    R output:
                    """ + output);
        }
    }

    private void runRscript(Path script, CircosExportResult export) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("Rscript", script.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int status = process.waitFor();
        writeLog(export, status, output);
        if (status != 0) {
            throw new IllegalStateException("Rscript failed while rendering Circos plot. See "
                    + config.logDir().resolve("circos_generation.log"));
        }
        if (!Files.exists(export.svgOutput())) {
            throw new IllegalStateException("Rscript finished but did not create " + export.svgOutput());
        }
    }

    private void writeLog(CircosExportResult export, int status, String output) throws IOException {
        Files.createDirectories(config.logDir());
        List<String> lines = new ArrayList<>();
        lines.add("timestamp=" + Instant.now());
        lines.add("plot_label=" + export.plotLabel());
        lines.add("sample_test_result_ids=" + export.sampleTestResultIds());
        lines.add("status=" + status);
        lines.add("script=" + export.generatedScript().toAbsolutePath());
        lines.add("svg=" + export.svgOutput().toAbsolutePath());
        lines.add("r_output=");
        lines.add(output == null ? "" : output);
        lines.add("");
        Files.write(config.logDir().resolve("circos_generation.log"),
                lines,
                StandardCharsets.UTF_8,
                Files.exists(config.logDir().resolve("circos_generation.log"))
                        ? java.nio.file.StandardOpenOption.APPEND
                        : java.nio.file.StandardOpenOption.CREATE);
    }

    private String rString(String value) {
        String escaped = value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }
}
