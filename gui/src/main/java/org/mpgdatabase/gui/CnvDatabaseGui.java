package org.mpgdatabase.gui;

import org.mpgdatabase.db.Database;
import org.mpgdatabase.importer.CnvImportService;
import org.mpgdatabase.importer.CnvParserFactory;
import org.mpgdatabase.importer.ImportResult;
import org.mpgdatabase.importer.VcfImportResult;
import org.mpgdatabase.importer.VcfImportService;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public class CnvDatabaseGui extends JFrame {
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> SUPPORTED_EVENTS = Set.of(
            "DEL", "DUP", "GAIN", "AMP", "LOSS", "CNV", "INV", "INS", "TRANS", "T",
            "ADD", "DER", "IDIC", "DIC", "I", "R", "RING", "COMPLEX", "ROH",
            "UPD", "TRISOMY", "MONOSOMY", "NEUTRAL", "UNKNOWN"
    );
    private static final Set<String> CORE_COLUMNS = Set.of(
            "sample_accession_id",
            "event_group_id",
            "chromosome",
            "start_pos",
            "stop_pos",
            "event_type",
            "copy_number",
            "genome_build",
            "confidence",
            "raw_iscn",
            "annotation_names",
            "annotations"
    );

    private final Path configPath;
    private Connection connection;
    private Path databasePath;
    private Path selectedImportFile;
    private JTable resultsTable;
    private DefaultTableModel tableModel;
    private JTextArea sqlEditor;
    private JTextArea messages;
    private JTextField selectedFileField;
    private JLabel databaseLabel;
    private JLabel connectionStatusLabel;
    private JLabel individualsCountLabel;
    private JLabel samplesCountLabel;
    private JLabel cnvCallsCountLabel;
    private JLabel smallVariantsCountLabel;
    private JLabel fileSummaryLabel;
    private JLabel querySummaryLabel;
    private JLabel queryTimeLabel;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            new CnvDatabaseGui().setVisible(true);
        });
    }

    public CnvDatabaseGui() {
        super("CNV Database GUI");
        this.configPath = appDirectory().resolve("db_gui.properties");
        buildUi();
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1160, 780));
        setLocationRelativeTo(null);
        connectOnStartup();
    }

    private void buildUi() {
        setJMenuBar(menuBar());
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        root.add(statusBand(), BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.add(importPanel(), BorderLayout.NORTH);
        center.add(queryAndResults(), BorderLayout.CENTER);
        root.add(center, BorderLayout.CENTER);
        root.add(messagePanel(), BorderLayout.SOUTH);
        setContentPane(root);
    }

    private JMenuBar menuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");
        JMenuItem open = new JMenuItem("Open/Create Database...");
        open.addActionListener(e -> openDatabase());
        JMenuItem importFile = new JMenuItem("Import CNV/SV or VCF File...");
        importFile.addActionListener(e -> chooseAndImport());
        JMenuItem export = new JMenuItem("Export Results as TSV...");
        export.addActionListener(e -> exportResults());
        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(e -> dispose());
        file.add(open);
        file.add(importFile);
        file.add(export);
        file.addSeparator();
        file.add(exit);

        JMenu database = new JMenu("Database");
        JMenuItem refresh = new JMenuItem("Refresh Status");
        refresh.addActionListener(e -> refreshStatus());
        database.add(refresh);

        JMenu presets = new JMenu("Query Presets");
        addPreset(presets, "Show all individuals", """
                SELECT individual_id, mrn, external_identifier
                FROM individuals
                ORDER BY individual_id
                LIMIT 100
                """);
        addPreset(presets, "Show all samples", """
                SELECT sa.sample_accession_id, sa.accession_identifier, sa.dna_source, i.individual_id
                FROM sample_accessions sa
                JOIN individuals i ON i.individual_id = sa.individual_id
                ORDER BY sa.sample_accession_id
                LIMIT 100
                """);
        addPreset(presets, "Universal genomic results", universalQuery());
        addPreset(presets, "Show all CNV/SV segments", defaultQuery());
        addPreset(presets, "Show all SNV/indel variants", smallVariantQuery());
        addPreset(presets, "Show recent imports", """
                SELECT sf.source_file_id, sf.file_name, sf.file_path, sf.import_status, sf.row_count,
                       sf.imported_at, p.software_name AS importer, sf.notes
                FROM source_files sf
                JOIN pipelines p ON p.pipeline_id = sf.pipeline_id
                ORDER BY sf.source_file_id DESC
                LIMIT 100
                """);
        addPreset(presets, "Count CNV calls by sample", """
                SELECT sa.accession_identifier AS sample_id, COUNT(*) AS cnv_calls
                FROM genomic_segments gs
                JOIN sample_test_results str ON str.sample_test_result_id = gs.sample_test_result_id
                JOIN sample_tests st ON st.sample_test_id = str.sample_test_id
                JOIN sample_accessions sa ON sa.sample_accession_id = st.sample_accession_id
                GROUP BY sa.accession_identifier
                ORDER BY cnv_calls DESC, sample_id
                LIMIT 100
                """);
        addPreset(presets, "Count CNV calls by source file", """
                SELECT COALESCE(sf.file_name, '(unknown)') AS source_file, COUNT(*) AS cnv_calls
                FROM genomic_segments gs
                JOIN sample_test_results str ON str.sample_test_result_id = gs.sample_test_result_id
                LEFT JOIN source_files sf ON sf.source_file_id = str.source_file_id
                GROUP BY sf.file_name
                ORDER BY cnv_calls DESC
                LIMIT 100
                """);
        addPreset(presets, "Count CNV calls by assay/pipeline", """
                SELECT lp.technology, st.test_type, p.software_name AS pipeline, COUNT(*) AS cnv_calls
                FROM genomic_segments gs
                JOIN sample_test_results str ON str.sample_test_result_id = gs.sample_test_result_id
                JOIN sample_tests st ON st.sample_test_id = str.sample_test_id
                JOIN lab_protocols lp ON lp.lab_protocol_id = st.lab_protocol_id
                JOIN pipelines p ON p.pipeline_id = str.pipeline_id
                GROUP BY lp.technology, st.test_type, p.software_name
                ORDER BY cnv_calls DESC
                LIMIT 100
                """);
        addPreset(presets, "CNV calls for sample ID", """
                SELECT *
                FROM (
                    %s
                ) q
                WHERE sample_id = 'GUI001'
                LIMIT 100
                """.formatted(defaultQueryWithoutLimit()));
        addPreset(presets, "CNV calls overlapping interval", """
                SELECT *
                FROM (
                    %s
                ) q
                WHERE chromosome = 'chr5'
                  AND start_pos <= 150000000
                  AND stop_pos >= 70000000
                LIMIT 100
                """.formatted(defaultQueryWithoutLimit()));
        addPreset(presets, "Universal results on chr7", """
                SELECT *
                FROM (
                    %s
                ) q
                WHERE chromosome = 'chr7'
                ORDER BY start_pos, stop_pos, result_type
                LIMIT 100
                """.formatted(universalQueryWithoutLimit()));
        addPreset(presets, "SNV/indel by gene", """
                SELECT *
                FROM (
                    %s
                ) q
                WHERE genes LIKE '%%OR4F5%%'
                LIMIT 100
                """.formatted(smallVariantQueryWithoutLimit()));
        addPreset(presets, "SNV/indel in interval", """
                SELECT *
                FROM (
                    %s
                ) q
                WHERE chromosome = 'chr1'
                  AND position BETWEEN 69000 AND 70000
                LIMIT 100
                """.formatted(smallVariantQueryWithoutLimit()));
        addPreset(presets, "Show database tables", """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'PUBLIC'
                ORDER BY table_name
                """);

        JMenu help = new JMenu("Help");
        JMenuItem expectedFormat = new JMenuItem("Expected CNV Format");
        expectedFormat.addActionListener(e -> showInfo("Expected CNV Format", """
                Required columns:
                sample_accession_id, chromosome, start_pos, stop_pos, event_type, copy_number, genome_build

                Optional columns:
                event_group_id, confidence, raw_iscn, raw_segment_text, annotation_names, annotations,
                and format-specific extra annotation columns such as ProbeCount, LPR/LRR, BAF, and caller scores.

                Extra columns are stored as searchable segment_annotations rows and also kept
                in the older pipe-delimited annotation fields for compatibility.
                Query examples:
                SELECT gs.*
                FROM genomic_segments gs
                JOIN segment_annotations sa ON sa.segment_id = gs.segment_id
                WHERE sa.annotation_name = 'Gene' AND sa.text_value = 'SHH'

                Genome build aliases are normalized before storage:
                hg19/Build 37 -> GRCh37, hg38/Build 38 -> GRCh38, T2T/CHM13 -> T2T-CHM13.

                If copy_number is missing, DEL/LOSS infer 1 and DUP/GAIN/AMP infer 3.
                Structural event rows with the same event_group_id create one event group
                and genomic link rows.
                """));
        JMenuItem changeLog = new JMenuItem("Change Log");
        changeLog.addActionListener(e -> showInfo("Change Log", """
                0.1.0
                - First runnable Swing GUI prototype.
                - H2 open/create database support.
                - Strict all-or-nothing CNV import.
                - Read-only SQL query runner.
                - Query presets and TSV export.
                """));
        JMenuItem about = new JMenuItem("About");
        about.addActionListener(e -> showInfo("About", "CNV Database GUI prototype for the MPG H2 CNV database."));
        help.add(expectedFormat);
        help.add(changeLog);
        help.add(about);

        bar.add(file);
        bar.add(database);
        bar.add(presets);
        bar.add(help);
        return bar;
    }

    private void addPreset(JMenu menu, String name, String sql) {
        JMenuItem item = new JMenuItem(name);
        item.addActionListener(e -> sqlEditor.setText(sql.strip()));
        menu.add(item);
    }

    private JPanel statusBand() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(195, 215, 235)),
                BorderFactory.createEmptyBorder(12, 14, 12, 14)));
        panel.setBackground(new Color(242, 248, 255));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 8, 2, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.weightx = 1;
        gbc.gridx = 0;
        panel.add(new JLabel("Database:"), gbc);
        gbc.gridx = 1;
        databaseLabel = new JLabel("(none)");
        panel.add(databaseLabel, gbc);
        gbc.gridx = 2;
        panel.add(new JLabel("Connection Status:"), gbc);
        gbc.gridx = 3;
        connectionStatusLabel = new JLabel("Disconnected");
        connectionStatusLabel.setForeground(Color.RED.darker());
        panel.add(connectionStatusLabel, gbc);
        gbc.gridx = 4;
        panel.add(new JLabel("Individuals:"), gbc);
        gbc.gridx = 5;
        individualsCountLabel = new JLabel("0");
        panel.add(individualsCountLabel, gbc);
        gbc.gridx = 6;
        panel.add(new JLabel("Samples:"), gbc);
        gbc.gridx = 7;
        samplesCountLabel = new JLabel("0");
        panel.add(samplesCountLabel, gbc);
        gbc.gridx = 8;
        panel.add(new JLabel("CNV/SV Segments:"), gbc);
        gbc.gridx = 9;
        cnvCallsCountLabel = new JLabel("0");
        panel.add(cnvCallsCountLabel, gbc);
        gbc.gridx = 10;
        panel.add(new JLabel("SNV/indels:"), gbc);
        gbc.gridx = 11;
        smallVariantsCountLabel = new JLabel("0");
        panel.add(smallVariantsCountLabel, gbc);
        return panel;
    }

    private JPanel importPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Import CNV/SV or SNV/indel VCF File"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 8, 6, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(new JLabel("Selected File:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        selectedFileField = new JTextField();
        selectedFileField.setEditable(false);
        panel.add(selectedFileField, gbc);
        gbc.gridx = 2;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        JButton importButton = new JButton("Import File...");
        importButton.addActionListener(e -> chooseAndImport());
        panel.add(importButton, gbc);
        gbc.gridx = 0;
        gbc.gridy = 1;
        panel.add(new JLabel("File summary:"), gbc);
        gbc.gridx = 1;
        gbc.gridwidth = 2;
        fileSummaryLabel = new JLabel("No import attempted.");
        panel.add(fileSummaryLabel, gbc);
        return panel;
    }

    private JSplitPane queryAndResults() {
        JPanel queryPanel = new JPanel(new BorderLayout(6, 6));
        queryPanel.setBorder(BorderFactory.createTitledBorder("SQL Query (read-only)"));
        sqlEditor = new JTextArea(defaultQuery(), 8, 80);
        sqlEditor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        queryPanel.add(new JScrollPane(sqlEditor), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 8));
        JButton run = new JButton("Run Query");
        run.addActionListener(e -> runQuery());
        JButton clear = new JButton("Clear Query");
        clear.addActionListener(e -> sqlEditor.setText(""));
        JButton export = new JButton("Export Results as TSV...");
        export.addActionListener(e -> exportResults());
        buttons.add(run);
        buttons.add(clear);
        buttons.add(export);
        queryPanel.add(buttons, BorderLayout.SOUTH);

        JPanel resultPanel = new JPanel(new BorderLayout(6, 6));
        JPanel resultSummary = new JPanel(new BorderLayout());
        querySummaryLabel = new JLabel("No query run.");
        queryTimeLabel = new JLabel(" ");
        resultSummary.add(querySummaryLabel, BorderLayout.WEST);
        resultSummary.add(queryTimeLabel, BorderLayout.EAST);
        resultPanel.add(resultSummary, BorderLayout.NORTH);
        tableModel = new DefaultTableModel();
        resultsTable = new JTable(tableModel);
        resultsTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        resultPanel.add(new JScrollPane(resultsTable), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, queryPanel, resultPanel);
        split.setResizeWeight(0.42);
        return split;
    }

    private JPanel messagePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Status"));
        messages = new JTextArea(4, 80);
        messages.setEditable(false);
        messages.setLineWrap(true);
        messages.setWrapStyleWord(true);
        panel.add(new JScrollPane(messages), BorderLayout.CENTER);
        return panel;
    }

    private void connectOnStartup() {
        Path remembered = readRememberedDatabase();
        Path defaultDb = remembered != null ? remembered : Path.of("output", "mpg_database_h2").toAbsolutePath().normalize();
        try {
            connect(defaultDb);
            setStatus("Connected to " + defaultDb);
        } catch (SQLException e) {
            setStatus("No database connected. Use File -> Open/Create Database... " + e.getMessage());
            connectionStatusLabel.setText("Disconnected");
            connectionStatusLabel.setForeground(Color.RED.darker());
        }
    }

    private void openDatabase() {
        JFileChooser chooser = new JFileChooser(Path.of(".").toAbsolutePath().normalize().toFile());
        chooser.setDialogTitle("Open or create H2 database file");
        chooser.setSelectedFile(Path.of("output", "mpg_database_h2").toFile());
        int result = chooser.showSaveDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            connect(stripH2Suffix(chooser.getSelectedFile().toPath()));
            setStatus("Connected to " + databasePath);
        } catch (SQLException e) {
            showError("Database connection failed", e.getMessage());
        }
    }

    private void connect(Path path) throws SQLException {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
        databasePath = stripH2Suffix(path.toAbsolutePath().normalize());
        connection = DriverManager.getConnection("jdbc:h2:file:" + databasePath);
        Database.initialize(connection);
        saveRememberedDatabase(databasePath);
        databaseLabel.setText(databasePath.toString());
        connectionStatusLabel.setText("Connected");
        connectionStatusLabel.setForeground(new Color(35, 130, 45));
        refreshStatus();
    }

    private void refreshStatus() {
        if (!isConnected()) {
            return;
        }
        try {
            individualsCountLabel.setText(format(count("individuals")));
            samplesCountLabel.setText(format(count("sample_accessions")));
            cnvCallsCountLabel.setText(format(count("genomic_segments")));
            smallVariantsCountLabel.setText(format(count("small_variants")));
        } catch (SQLException e) {
            setStatus("STATUS ERROR: " + e.getMessage());
        }
    }

    private void chooseAndImport() {
        if (!isConnected()) {
            showError("No database", "Open or create a database before importing.");
            return;
        }
        JFileChooser chooser = new JFileChooser(Path.of(".").toAbsolutePath().normalize().toFile());
        chooser.setDialogTitle("Import CNV/SV or VCF file");
        chooser.setFileFilter(new FileNameExtensionFilter("CNV/SV and VCF files", "cnv", "tsv", "txt", "vcf"));
        int result = chooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        selectedImportFile = chooser.getSelectedFile().toPath();
        selectedFileField.setText(selectedImportFile.toString());
        importFile(selectedImportFile);
    }

    private void importFile(Path path) {
        LocalDateTime attemptedAt = LocalDateTime.now();
        try {
            if (isVcfFile(path)) {
                importVcfFile(path, attemptedAt);
            } else if (isCnvLikeFile(path)) {
                importCnvFile(path, attemptedAt);
            } else {
                fileSummaryLabel.setText(fileSummary(path, 0, 1, "Import rejected", attemptedAt));
                setStatus("IMPORT REJECTED: unsupported file type. Use .vcf for SNV/indel or .cnv/.tsv/.txt for CNV/SV.");
                showError("Unsupported file", "Use .vcf for SNV/indel imports or .cnv/.tsv/.txt for CNV/SV imports.");
                return;
            }
            refreshStatus();
        } catch (Exception e) {
            fileSummaryLabel.setText(fileSummary(path, 0, 1, "Import failed", attemptedAt));
            setStatus("IMPORT FAILED: " + e.getMessage());
            showError("Import failed", e.getMessage());
        }
    }

    private void importVcfFile(Path path, LocalDateTime attemptedAt) {
        VcfImportResult result = new VcfImportService(connection).importFile(path, null);
        fileSummaryLabel.setText(fileSummary(path, result.recordsSeen(), result.issuesInserted(),
                result.success() ? "VCF import finished" : "VCF import failed", attemptedAt));
        if (result.success()) {
            setStatus("VCF IMPORT: records=" + format(result.recordsSeen())
                    + ", variants=" + format(result.variantsInsertedOrReused())
                    + ", sample calls=" + format(result.sampleCallsInserted())
                    + ", annotations=" + format(result.annotationsInserted())
                    + ", issues=" + format(result.issuesInserted()) + ".");
        } else {
            setStatus("VCF IMPORT FAILED: " + result.fileName() + ". Check validation_issues/source_files.");
        }
        sqlEditor.setText(smallVariantQuery());
        runQuery();
    }

    private void importCnvFile(Path path, LocalDateTime attemptedAt) {
        ImportResult result = new CnvImportService(connection, new CnvParserFactory()).importFile(path, null);
        fileSummaryLabel.setText(fileSummary(path, result.recordsSeen(), result.issuesInserted(),
                result.success() ? "CNV/SV import finished" : "CNV/SV import failed", attemptedAt));
        if (result.success()) {
            setStatus("CNV/SV IMPORT: records=" + format(result.recordsSeen())
                    + ", segments=" + format(result.segmentsInserted())
                    + ", issues=" + format(result.issuesInserted()) + ".");
        } else {
            setStatus("CNV/SV IMPORT FAILED: " + result.fileName() + ". Check validation_issues/source_files.");
        }
        sqlEditor.setText(defaultQuery());
        runQuery();
    }

    private boolean isVcfFile(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".vcf");
    }

    private boolean isCnvLikeFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".cnv") || fileName.endsWith(".tsv") || fileName.endsWith(".txt");
    }

    private ImportPlan validateImport(Path path) throws IOException {
        List<CnvRow> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                errors.add("File is empty or missing a header.");
                return new ImportPlan(List.of(), errors, 0);
            }
            List<String> header = normalizeHeader(headerLine);
            Map<String, Integer> indexes = indexes(header);
            if (isUnsupportedRawArrayEvidence(header)) {
                errors.add("Unsupported Array Evidence File: file appears to be raw array probe/manifest/evidence data, not a final called CNV interval file.");
                return new ImportPlan(List.of(), errors, 0);
            }
            for (String required : List.of("sample_accession_id", "chromosome", "start_pos", "stop_pos",
                    "event_type", "genome_build")) {
                if (!indexes.containsKey(required)) {
                    errors.add("Missing required column: " + required);
                }
            }
            if (!errors.isEmpty()) {
                return new ImportPlan(List.of(), errors, 0);
            }
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\\t", -1);
                if (parts.length != header.size()) {
                    errors.add("Line " + lineNumber + ": expected " + header.size() + " columns but found " + parts.length);
                    continue;
                }
                Map<String, String> values = values(header, parts);
                CnvRow row = parseRow(values, lineNumber, errors);
                if (row != null) {
                    rows.add(row);
                }
            }
            return new ImportPlan(rows, errors, lineNumber - 1);
        }
    }

    private CnvRow parseRow(Map<String, String> values, int lineNumber, List<String> errors) {
        String sample = value(values, "sample_accession_id");
        String eventGroupId = value(values, "event_group_id");
        String chromosome = normalizeChromosome(value(values, "chromosome"));
        String eventType = upper(value(values, "event_type"));
        String rawGenomeBuild = value(values, "genome_build");
        String genomeBuild = normalizeGenomeBuild(rawGenomeBuild);
        Long start = parseLong(value(values, "start_pos"));
        Long stop = parseLong(value(values, "stop_pos"));
        Integer copyNumber = resolveCopyNumber(value(values, "copy_number"), eventType);
        String confidence = value(values, "confidence");
        String rawIscn = value(values, "raw_iscn");
        AnnotationPair annotationPair = resolveAnnotationPair(values);
        String annotationNames = annotationPair.names();
        String annotations = annotationPair.values();

        List<String> rowErrors = new ArrayList<>();
        if (sample == null) {
            rowErrors.add("missing sample_accession_id");
        }
        if (chromosome == null) {
            rowErrors.add("missing chromosome");
        }
        if (start == null) {
            rowErrors.add("missing or invalid start_pos");
        }
        if (stop == null) {
            rowErrors.add("missing or invalid stop_pos");
        }
        if (start != null && stop != null && start > stop) {
            rowErrors.add("start_pos greater than stop_pos");
        }
        if (eventType == null || !SUPPORTED_EVENTS.contains(eventType)) {
            rowErrors.add("missing or unsupported event_type");
        }
        if (copyNumber == null) {
            rowErrors.add("missing copy_number and unable to infer copy_number from event_type");
        }
        if (rawGenomeBuild == null) {
            rowErrors.add("missing genome_build");
        } else if (genomeBuild == null) {
            rowErrors.add("invalid genome_build: " + rawGenomeBuild);
        }
        if (annotationNames != null && annotations != null && countParts(annotationNames) != countParts(annotations)) {
            rowErrors.add("annotation_names count does not match annotations count");
        }
        if (!rowErrors.isEmpty()) {
            errors.add("Line " + lineNumber + ": " + String.join("; ", rowErrors));
            return null;
        }
        return new CnvRow(lineNumber, sample, eventGroupId, chromosome, start, stop, eventType, copyNumber,
                genomeBuild, confidence, rawIscn, annotationNames, annotations);
    }

    private int insertRows(Path path, List<CnvRow> rows, LocalDateTime attemptedAt) throws SQLException {
        long pipelineId = getOrCreatePipeline();
        long protocolId = getOrCreateLabProtocol();
        long sourceFileId = createSourceFile(path, pipelineId, rows.size());
        int inserted = 0;
        Map<String, EventGroupState> eventGroups = new LinkedHashMap<>();
        Map<String, ResultContext> resultContexts = new LinkedHashMap<>();
        for (CnvRow row : rows) {
            long individualId = getOrCreateIndividual(row.sample());
            long accessionId = getOrCreateAccession(row.sample(), individualId);
            ResultContext resultContext = resultContext(
                    resultContexts,
                    accessionId,
                    protocolId,
                    pipelineId,
                    sourceFileId,
                    row);
            EventGroupState groupState = eventGroupState(eventGroups, resultContext.resultId(), row);
            String eventGroupId = groupState == null ? null : groupState.eventGroupLabel();
            long sourceSegmentId = createSegment(
                    eventGroupId,
                    resultContext.resultId(),
                    row.chromosome(),
                    row.start(),
                    row.stop(),
                    row);
            createSegmentAnnotations(sourceSegmentId, row.annotationNames(), row.annotations());
            inserted++;
            if (shouldLinkGroupedSegment(row, groupState)) {
                createGenomicLink(null, null, groupState.eventGroupLabel(), groupState.firstTransSegmentId(), sourceSegmentId,
                        groupState.eventGroupType(), linkEvidence(row), row.confidence());
            }
            if (groupState != null) {
                groupState.addSegment(sourceSegmentId, row.eventType());
            }
        }
        return inserted;
    }

    private EventGroupState eventGroupState(Map<String, EventGroupState> eventGroups,
                                            long resultId,
                                            CnvRow row) throws SQLException {
        if (row.eventGroupId() == null || row.eventGroupId().isBlank()) {
            if (!isBreakpointEvent(row.eventType())) {
                return null;
            }
        }
        String label = row.eventGroupId() == null || row.eventGroupId().isBlank()
                ? "AUTO-BREAKPOINT-" + row.lineNumber()
                : row.eventGroupId();
        String groupKey = resultId + "|" + label;
        EventGroupState existing = eventGroups.get(groupKey);
        if (existing != null) {
            return existing;
        }
        EventGroupState created = new EventGroupState(row.eventGroupId(), label, eventGroupType(row.eventType()));
        eventGroups.put(groupKey, created);
        return created;
    }

    private ResultContext resultContext(Map<String, ResultContext> resultContexts,
                                        long accessionId,
                                        long protocolId,
                                        long pipelineId,
                                        long sourceFileId,
                                        CnvRow row) throws SQLException {
        String key = resultKey(accessionId, protocolId, pipelineId, sourceFileId, row);
        ResultContext existing = resultContexts.get(key);
        if (existing != null) {
            return existing;
        }
        long sampleTestId = createSampleTest(accessionId, protocolId);
        long resultId = createSampleTestResult(sampleTestId, pipelineId, sourceFileId, row);
        ResultContext created = new ResultContext(resultId);
        resultContexts.put(key, created);
        return created;
    }

    private String resultKey(long accessionId, long protocolId, long pipelineId, long sourceFileId, CnvRow row) {
        return accessionId + "|"
                + protocolId + "|"
                + pipelineId + "|"
                + sourceFileId + "|"
                + row.genomeBuild() + "|"
                + nullToEmpty(row.rawIscn()) + "|"
                + nullToEmpty(row.annotationNames());
    }

    private void runQuery() {
        if (!isConnected()) {
            showError("No database", "Open or create a database before running queries.");
            return;
        }
        String sql = sqlEditor.getText();
        if (!isReadOnlySql(sql)) {
            setStatus("SQL ERROR: Only SELECT or WITH read-only queries are allowed.");
            showError("SQL blocked", "Only SELECT or WITH queries are allowed. Write/schema SQL is blocked.");
            return;
        }
        if (isShowDatabaseTablesQuery(sql)) {
            runDatabaseTablesQuery();
            return;
        }
        long started = System.nanoTime();
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            int rows = displayResultSet(rs);
            int columns = tableModel.getColumnCount();
            double elapsed = (System.nanoTime() - started) / 1_000_000_000.0;
            querySummaryLabel.setText(rows + " rows returned, " + columns + " columns");
            queryTimeLabel.setText("Query executed in " + String.format(Locale.ROOT, "%.3f", elapsed) + " sec");
            setStatus("Query executed successfully. " + rows + " rows returned.");
        } catch (SQLException e) {
            setStatus("SQL ERROR: " + e.getMessage());
            showError("SQL error", e.getMessage());
        }
    }

    private int displayResultSet(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columns = meta.getColumnCount();
        DefaultTableModel model = new DefaultTableModel();
        for (int i = 1; i <= columns; i++) {
            model.addColumn(meta.getColumnLabel(i));
        }
        int rows = 0;
        while (rs.next()) {
            Object[] row = new Object[columns];
            for (int i = 1; i <= columns; i++) {
                row[i - 1] = rs.getObject(i);
            }
            model.addRow(row);
            rows++;
        }
        tableModel = model;
        resultsTable.setModel(tableModel);
        return rows;
    }

    private void runDatabaseTablesQuery() {
        long started = System.nanoTime();
        try (ResultSet rs = connection.getMetaData().getTables(null, "PUBLIC", "%", new String[]{"TABLE"})) {
            DefaultTableModel model = new DefaultTableModel();
            model.addColumn("TABLE_NAME");
            int rows = 0;
            while (rs.next()) {
                model.addRow(new Object[]{rs.getString("TABLE_NAME")});
                rows++;
            }
            tableModel = model;
            resultsTable.setModel(tableModel);
            double elapsed = (System.nanoTime() - started) / 1_000_000_000.0;
            querySummaryLabel.setText(rows + " rows returned, 1 columns");
            queryTimeLabel.setText("Query executed in " + String.format(Locale.ROOT, "%.3f", elapsed) + " sec");
            setStatus("Query executed successfully. " + rows + " rows returned.");
        } catch (SQLException e) {
            setStatus("SQL ERROR: " + e.getMessage());
            showError("SQL error", e.getMessage());
        }
    }

    private boolean isShowDatabaseTablesQuery(String sql) {
        String compact = sql == null ? "" : sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        return compact.contains("from information_schema.tables")
                && compact.contains("table_schema")
                && compact.contains("public");
    }

    private void exportResults() {
        if (tableModel == null || tableModel.getColumnCount() == 0) {
            showError("Nothing to export", "Run a query before exporting results.");
            return;
        }
        JFileChooser chooser = new JFileChooser(Path.of(".").toAbsolutePath().normalize().toFile());
        chooser.setDialogTitle("Export results as TSV");
        chooser.setSelectedFile(Path.of("query_results_export.tsv").toFile());
        int result = chooser.showSaveDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            writeTableTsv(chooser.getSelectedFile().toPath());
            setStatus("EXPORT SUCCESS: Exported " + tableModel.getRowCount() + " rows and "
                    + tableModel.getColumnCount() + " columns.");
        } catch (IOException e) {
            setStatus("EXPORT ERROR: " + e.getMessage());
            showError("Export failed", e.getMessage());
        }
    }

    private void writeTableTsv(Path path) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            for (int col = 0; col < tableModel.getColumnCount(); col++) {
                if (col > 0) {
                    writer.write('\t');
                }
                writer.write(tableModel.getColumnName(col));
            }
            writer.newLine();
            for (int row = 0; row < tableModel.getRowCount(); row++) {
                for (int col = 0; col < tableModel.getColumnCount(); col++) {
                    if (col > 0) {
                        writer.write('\t');
                    }
                    Object value = tableModel.getValueAt(row, col);
                    writer.write(value == null ? "" : value.toString().replace('\t', ' '));
                }
                writer.newLine();
            }
        }
    }

    private boolean isReadOnlySql(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        String normalized = sql.stripLeading().toLowerCase(Locale.ROOT);
        if (!(normalized.startsWith("select") || normalized.startsWith("with"))) {
            return false;
        }
        String compact = normalized.replaceAll("'[^']*'", " ");
        return !compact.matches("(?s).*\\b(insert|update|delete|merge|drop|alter|create|truncate|grant|revoke|call)\\b.*");
    }

    private void createSchema(Connection conn) throws SQLException {
        for (String statement : schema().split(";")) {
            String trimmed = statement.trim();
            if (!trimmed.isEmpty()) {
                String normalized = trimmed.toLowerCase(Locale.ROOT);
                if (normalized.contains("idx_segments_event_group_label")
                        || normalized.contains("idx_links_event_group_label")) {
                    continue;
                }
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(trimmed);
                }
            }
        }
        addColumnIfMissing(conn, "GENOMIC_SEGMENTS", "EVENT_GROUP_ID", "VARCHAR(128)");
        addColumnIfMissing(conn, "GENOMIC_SEGMENTS", "GENOME_BUILD", "VARCHAR(64)");
        addColumnIfMissing(conn, "GENOMIC_SEGMENTS", "CONFIDENCE", "VARCHAR(64)");
        addColumnIfMissing(conn, "GENOMIC_SEGMENTS", "RAW_ISCN", "VARCHAR(4096)");
        addColumnIfMissing(conn, "GENOMIC_SEGMENTS", "RAW_SEGMENT_TEXT", "VARCHAR(2000)");
        addColumnIfMissing(conn, "GENOMIC_EVENT_GROUPS", "EVENT_GROUP_TYPE", "VARCHAR(64)");
        addColumnIfMissing(conn, "GENOMIC_LINKS", "GENOMIC_EVENT_GROUP_ID", "BIGINT");
        addColumnIfMissing(conn, "GENOMIC_LINKS", "EVENT_GROUP_ID", "VARCHAR(128)");
        addColumnIfMissing(conn, "GENOMIC_EVENTS", "EVENT_GROUP_ID", "VARCHAR(128)");
        backfillDirectEventGroupIds(conn);
        backfillSegmentResultFields(conn);
        migrateSegmentAssayColumnsToAnnotations(conn);
        migrateGenomicSegmentLegacyColumns(conn);
        backfillSegmentAnnotations(conn);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_segments_event_group_label
                        ON genomic_segments(event_group_id)
                    """);
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_links_event_group_label
                        ON genomic_links(event_group_id)
                    """);
        }
    }

    private void backfillSegmentAnnotations(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT gs.segment_id, str.annotation_names, gs.annotations
                FROM genomic_segments gs
                JOIN sample_test_results str ON str.sample_test_result_id = gs.sample_test_result_id
                WHERE COALESCE(str.annotation_names, '') <> ''
                  AND COALESCE(gs.annotations, '') <> ''
                  AND NOT EXISTS (
                      SELECT 1
                      FROM segment_annotations sa
                      WHERE sa.segment_id = gs.segment_id
                  )
                ORDER BY gs.segment_id
                """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                createSegmentAnnotations(
                        rs.getLong("segment_id"),
                        rs.getString("annotation_names"),
                        rs.getString("annotations"));
            }
        }
    }

    private void migrateGenomicSegmentLegacyColumns(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE GENOMIC_SEGMENTS DROP COLUMN IF EXISTS ARRAY_SCORE");
            stmt.execute("ALTER TABLE GENOMIC_SEGMENTS DROP COLUMN IF EXISTS NUMBER_OF_SITES");
            stmt.execute("ALTER TABLE GENOMIC_SEGMENTS DROP COLUMN IF EXISTS EVENT_ID");
            stmt.execute("ALTER TABLE GENOMIC_SEGMENTS DROP COLUMN IF EXISTS GENOMIC_EVENT_GROUP_ID");
            stmt.execute("ALTER TABLE GENOMIC_SEGMENTS DROP COLUMN IF EXISTS CYTOBAND_START");
            stmt.execute("ALTER TABLE GENOMIC_SEGMENTS DROP COLUMN IF EXISTS CYTOBAND_END");
        }
    }

    private void migrateSegmentAssayColumnsToAnnotations(Connection conn) throws SQLException {
        Map<String, String> legacyColumns = new LinkedHashMap<>();
        legacyColumns.put("ARRAY_SCORE", "ArrayScore");
        legacyColumns.put("NUMBER_OF_SITES", "NumberOfSites");

        List<String> existingColumns = new ArrayList<>();
        for (String column : legacyColumns.keySet()) {
            if (columnExists(conn, "GENOMIC_SEGMENTS", column)) {
                existingColumns.add(column);
            }
        }
        if (existingColumns.isEmpty()) {
            return;
        }

        StringBuilder sql = new StringBuilder("""
                SELECT gs.segment_id, gs.sample_test_result_id, str.annotation_names, gs.annotations
                """);
        for (String column : existingColumns) {
            sql.append(", gs.").append(column).append(" AS ").append(column).append('\n');
        }
        sql.append("""
                FROM genomic_segments gs
                JOIN sample_test_results str ON str.sample_test_result_id = gs.sample_test_result_id
                ORDER BY gs.segment_id
                """);

        Map<Long, String> resultAnnotationNames = new LinkedHashMap<>();
        try (PreparedStatement read = conn.prepareStatement(sql.toString());
             ResultSet rs = read.executeQuery()) {
            while (rs.next()) {
                long resultId = rs.getLong("sample_test_result_id");
                String annotationNames = resultAnnotationNames.getOrDefault(resultId, rs.getString("annotation_names"));
                String annotations = rs.getString("annotations");
                for (String column : existingColumns) {
                    String label = legacyColumns.get(column);
                    if (!containsAnnotationName(annotationNames, label)) {
                        annotationNames = appendPart(annotationNames, label);
                    }
                    annotations = appendPart(annotations, nullToEmpty(rs.getString(column)));
                }
                resultAnnotationNames.put(resultId, annotationNames);
                updateSegmentAnnotations(conn, rs.getLong("segment_id"), annotations);
            }
        }
        for (Map.Entry<Long, String> entry : resultAnnotationNames.entrySet()) {
            updateResultAnnotationNames(conn, entry.getKey(), entry.getValue());
        }
    }

    private void backfillSegmentResultFields(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    UPDATE genomic_segments gs
                    SET genome_build = (
                        SELECT str.genome_build
                        FROM sample_test_results str
                        WHERE str.sample_test_result_id = gs.sample_test_result_id
                    )
                    WHERE (gs.genome_build IS NULL OR gs.genome_build = '')
                      AND EXISTS (
                          SELECT 1
                          FROM sample_test_results str
                          WHERE str.sample_test_result_id = gs.sample_test_result_id
                            AND str.genome_build IS NOT NULL
                      )
                    """);
            stmt.execute("""
                    UPDATE genomic_segments gs
                    SET raw_iscn = (
                        SELECT str.raw_iscn
                        FROM sample_test_results str
                        WHERE str.sample_test_result_id = gs.sample_test_result_id
                    )
                    WHERE (gs.raw_iscn IS NULL OR gs.raw_iscn = '')
                      AND EXISTS (
                          SELECT 1
                          FROM sample_test_results str
                          WHERE str.sample_test_result_id = gs.sample_test_result_id
                            AND str.raw_iscn IS NOT NULL
                            AND str.raw_iscn <> ''
                      )
                    """);
        }
    }

    private void updateSegmentAnnotations(Connection conn, long segmentId, String annotations) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE genomic_segments
                SET annotations = ?
                WHERE segment_id = ?
                """)) {
            ps.setString(1, annotations);
            ps.setLong(2, segmentId);
            ps.executeUpdate();
        }
    }

    private void updateResultAnnotationNames(Connection conn, long resultId, String annotationNames) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE sample_test_results
                SET annotation_names = ?
                WHERE sample_test_result_id = ?
                """)) {
            ps.setString(1, annotationNames);
            ps.setLong(2, resultId);
            ps.executeUpdate();
        }
    }

    private boolean containsAnnotationName(String annotationNames, String label) {
        if (annotationNames == null || annotationNames.isBlank()) {
            return false;
        }
        for (String name : annotationNames.split(";")) {
            if (name.trim().equalsIgnoreCase(label)) {
                return true;
            }
        }
        return false;
    }

    private String appendPart(String existing, String value) {
        if (existing == null || existing.isEmpty()) {
            return value == null ? "" : value;
        }
        return existing + "|" + (value == null ? "" : value);
    }

    private void backfillDirectEventGroupIds(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            if (columnExists(conn, "GENOMIC_SEGMENTS", "EVENT_ID")) {
                stmt.execute("""
                        UPDATE genomic_segments gs
                        SET event_group_id = (
                            SELECT ge.event_group_id
                            FROM genomic_events ge
                            WHERE ge.event_id = gs.event_id
                        )
                        WHERE (gs.event_group_id IS NULL OR gs.event_group_id = '')
                          AND gs.event_id IS NOT NULL
                          AND EXISTS (
                              SELECT 1
                              FROM genomic_events ge
                              WHERE ge.event_id = gs.event_id
                                AND ge.event_group_id IS NOT NULL
                                AND ge.event_group_id <> ''
                          )
                        """);
            }
            if (columnExists(conn, "GENOMIC_SEGMENTS", "GENOMIC_EVENT_GROUP_ID")) {
                stmt.execute("""
                        UPDATE genomic_segments gs
                        SET event_group_id = (
                            SELECT geg.event_group_label
                            FROM genomic_event_groups geg
                            WHERE geg.genomic_event_group_id = gs.genomic_event_group_id
                        )
                        WHERE (gs.event_group_id IS NULL OR gs.event_group_id = '')
                          AND gs.genomic_event_group_id IS NOT NULL
                          AND EXISTS (
                              SELECT 1
                              FROM genomic_event_groups geg
                              WHERE geg.genomic_event_group_id = gs.genomic_event_group_id
                                AND geg.event_group_label IS NOT NULL
                                AND geg.event_group_label <> ''
                          )
                        """);
            }
            stmt.execute("""
                    UPDATE genomic_links gl
                    SET event_group_id = (
                        SELECT geg.event_group_label
                        FROM genomic_event_groups geg
                        WHERE geg.genomic_event_group_id = gl.genomic_event_group_id
                    )
                    WHERE (gl.event_group_id IS NULL OR gl.event_group_id = '')
                      AND gl.genomic_event_group_id IS NOT NULL
                      AND EXISTS (
                          SELECT 1
                          FROM genomic_event_groups geg
                          WHERE geg.genomic_event_group_id = gl.genomic_event_group_id
                            AND geg.event_group_label IS NOT NULL
                            AND geg.event_group_label <> ''
                      )
                    """);
            stmt.execute("""
                    UPDATE genomic_links gl
                    SET event_group_id = (
                        SELECT gs.event_group_id
                        FROM genomic_segments gs
                        WHERE gs.segment_id = gl.source_segment_id
                    )
                    WHERE (gl.event_group_id IS NULL OR gl.event_group_id = '')
                      AND EXISTS (
                          SELECT 1
                          FROM genomic_segments gs
                          WHERE gs.segment_id = gl.source_segment_id
                            AND gs.event_group_id IS NOT NULL
                            AND gs.event_group_id <> ''
                      )
                    """);
            stmt.execute("""
                    UPDATE genomic_segments
                    SET event_group_id = NULL
                    WHERE event_group_id LIKE 'AUTO-BACKFILL-%'
                    """);
            stmt.execute("""
                    UPDATE genomic_links
                    SET event_group_id = NULL
                    WHERE event_group_id LIKE 'AUTO-BACKFILL-%'
                    """);
        }
    }

    private void addColumnIfMissing(Connection conn, String table, String column, String type)
            throws SQLException {
        if (columnExists(conn, table, column)) {
            return;
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
    }

    private boolean columnExists(Connection conn, String table, String column) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = ? AND COLUMN_NAME = ?
                """)) {
            ps.setString(1, table.toUpperCase(Locale.ROOT));
            ps.setString(2, column.toUpperCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getLong(1) > 0;
            }
        }
    }

    private String schema() {
        return """
                CREATE TABLE IF NOT EXISTS individuals (
                    individual_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    mrn VARCHAR(128) UNIQUE,
                    external_identifier VARCHAR(128) UNIQUE
                );
                CREATE TABLE IF NOT EXISTS sample_accessions (
                    sample_accession_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    accession_identifier VARCHAR(128) NOT NULL UNIQUE,
                    individual_id BIGINT NOT NULL,
                    dna_source VARCHAR(128),
                    FOREIGN KEY (individual_id) REFERENCES individuals(individual_id)
                );
                CREATE TABLE IF NOT EXISTS lab_protocols (
                    lab_protocol_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    technology VARCHAR(128) NOT NULL,
                    manufacturer VARCHAR(128),
                    miscellaneous VARCHAR(512),
                    UNIQUE (technology, manufacturer)
                );
                CREATE TABLE IF NOT EXISTS sample_tests (
                    sample_test_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    sample_accession_id BIGINT NOT NULL,
                    lab_protocol_id BIGINT NOT NULL,
                    test_type VARCHAR(64) NOT NULL,
                    FOREIGN KEY (sample_accession_id) REFERENCES sample_accessions(sample_accession_id),
                    FOREIGN KEY (lab_protocol_id) REFERENCES lab_protocols(lab_protocol_id)
                );
                CREATE TABLE IF NOT EXISTS pipelines (
                    pipeline_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    software_name VARCHAR(128) NOT NULL,
                    software_version VARCHAR(64),
                    settings_used VARCHAR(1024),
                    UNIQUE (software_name, software_version)
                );
                CREATE TABLE IF NOT EXISTS source_files (
                    source_file_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    file_name VARCHAR(255) NOT NULL,
                    file_path VARCHAR(1000),
                    pipeline_id BIGINT NOT NULL,
                    imported_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    import_status VARCHAR(50) NOT NULL,
                    row_count INTEGER NOT NULL,
                    notes VARCHAR(1000),
                    FOREIGN KEY (pipeline_id) REFERENCES pipelines(pipeline_id)
                );
                CREATE TABLE IF NOT EXISTS sample_test_results (
                    sample_test_result_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    sample_test_id BIGINT NOT NULL,
                    pipeline_id BIGINT NOT NULL,
                    source_file_id BIGINT,
                    genome_build VARCHAR(64) NOT NULL,
                    calling_method VARCHAR(128),
                    raw_iscn VARCHAR(4096),
                    annotation_names VARCHAR(8192),
                    line_number INTEGER,
                    FOREIGN KEY (sample_test_id) REFERENCES sample_tests(sample_test_id),
                    FOREIGN KEY (pipeline_id) REFERENCES pipelines(pipeline_id),
                    FOREIGN KEY (source_file_id) REFERENCES source_files(source_file_id)
                );
                CREATE TABLE IF NOT EXISTS karyotypes (
                    karyotype_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    sample_test_result_id BIGINT NOT NULL,
                    karyotype_text VARCHAR(4096) NOT NULL,
                    clone_number INTEGER,
                    cell_count INTEGER,
                    abnormalities VARCHAR(4096),
                    FOREIGN KEY (sample_test_result_id) REFERENCES sample_test_results(sample_test_result_id)
                );
                CREATE TABLE IF NOT EXISTS genomic_events (
                    event_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    sample_test_result_id BIGINT NOT NULL,
                    source_file_id BIGINT,
                    event_group_id VARCHAR(128),
                    event_type VARCHAR(64) NOT NULL,
                    genome_build VARCHAR(64) NOT NULL,
                    calling_method VARCHAR(128),
                    raw_event_text VARCHAR(4096),
                    line_number INTEGER,
                    event_status VARCHAR(64) NOT NULL DEFAULT 'IMPORTED',
                    confidence VARCHAR(64),
                    annotations VARCHAR(8192),
                    FOREIGN KEY (sample_test_result_id) REFERENCES sample_test_results(sample_test_result_id),
                    FOREIGN KEY (source_file_id) REFERENCES source_files(source_file_id)
                );
                CREATE TABLE IF NOT EXISTS genomic_event_groups (
                    genomic_event_group_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    sample_test_result_id BIGINT NOT NULL,
                    event_group_label VARCHAR(100) NOT NULL,
                    event_group_type VARCHAR(64),
                    raw_event_text VARCHAR(2000),
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (sample_test_result_id) REFERENCES sample_test_results(sample_test_result_id),
                    UNIQUE (sample_test_result_id, event_group_label)
                );
                CREATE TABLE IF NOT EXISTS genomic_segments (
                    segment_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    event_group_id VARCHAR(128),
                    sample_test_result_id BIGINT NOT NULL,
                    karyotype_id BIGINT,
                    chromosome VARCHAR(32) NOT NULL,
                    start_pos BIGINT NOT NULL,
                    stop_pos BIGINT NOT NULL,
                    event_type VARCHAR(64) NOT NULL,
                    copy_number INTEGER NOT NULL,
                    genome_build VARCHAR(64),
                    confidence VARCHAR(64),
                    raw_iscn VARCHAR(4096),
                    raw_segment_text VARCHAR(2000),
                    annotations VARCHAR(8192),
                    FOREIGN KEY (sample_test_result_id) REFERENCES sample_test_results(sample_test_result_id),
                    CHECK (start_pos <= stop_pos)
                );
                CREATE INDEX IF NOT EXISTS idx_segments_region
                    ON genomic_segments(chromosome, start_pos, stop_pos);
                CREATE INDEX IF NOT EXISTS idx_segments_result
                    ON genomic_segments(sample_test_result_id);
                CREATE INDEX IF NOT EXISTS idx_segments_event_type
                    ON genomic_segments(event_type);
                CREATE INDEX IF NOT EXISTS idx_segments_copy_number
                    ON genomic_segments(copy_number);
                CREATE INDEX IF NOT EXISTS idx_segments_genome_build
                    ON genomic_segments(genome_build);
                CREATE TABLE IF NOT EXISTS segment_annotations (
                    annotation_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    segment_id BIGINT NOT NULL,
                    annotation_name VARCHAR(256) NOT NULL,
                    text_value VARCHAR(4096),
                    numeric_value DOUBLE PRECISION,
                    boolean_value BOOLEAN,
                    value_type VARCHAR(32) NOT NULL,
                    source_column VARCHAR(256) NOT NULL,
                    ordinal_position INTEGER NOT NULL,
                    FOREIGN KEY (segment_id) REFERENCES genomic_segments(segment_id),
                    CHECK (value_type IN ('TEXT', 'NUMBER', 'BOOLEAN'))
                );
                CREATE INDEX IF NOT EXISTS idx_segment_annotations_segment
                    ON segment_annotations(segment_id);
                CREATE INDEX IF NOT EXISTS idx_segment_annotations_lookup
                    ON segment_annotations(annotation_name, text_value);
                CREATE INDEX IF NOT EXISTS idx_segment_annotations_numeric
                    ON segment_annotations(annotation_name, numeric_value);
                CREATE TABLE IF NOT EXISTS genomic_links (
                    link_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    genomic_event_group_id BIGINT,
                    event_id BIGINT,
                    event_group_id VARCHAR(128),
                    source_segment_id BIGINT NOT NULL,
                    target_segment_id BIGINT NOT NULL,
                    link_type VARCHAR(64) NOT NULL,
                    orientation VARCHAR(64),
                    evidence VARCHAR(1024),
                    confidence VARCHAR(64),
                    FOREIGN KEY (genomic_event_group_id) REFERENCES genomic_event_groups(genomic_event_group_id),
                    FOREIGN KEY (event_id) REFERENCES genomic_events(event_id),
                    FOREIGN KEY (source_segment_id) REFERENCES genomic_segments(segment_id),
                    FOREIGN KEY (target_segment_id) REFERENCES genomic_segments(segment_id)
                );
                CREATE TABLE IF NOT EXISTS validation_issues (
                    validation_issue_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    segment_id BIGINT,
                    source_file_id BIGINT,
                    line_number INTEGER,
                    sample_accession_id VARCHAR(128),
                    issue_type VARCHAR(128) NOT NULL,
                    issue_message VARCHAR(2048) NOT NULL,
                    severity VARCHAR(32) NOT NULL
                );
                CREATE TABLE IF NOT EXISTS import_history (
                    import_history_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    file_name VARCHAR(255) NOT NULL,
                    file_path VARCHAR(1000),
                    import_status VARCHAR(50) NOT NULL,
                    num_rows_read INTEGER NOT NULL,
                    num_rows_with_errors INTEGER NOT NULL,
                    num_rows_inserted INTEGER NOT NULL,
                    attempted_at TIMESTAMP NOT NULL
                );
                """;
    }

    private long getOrCreatePipeline() throws SQLException {
        return getOrCreate("""
                SELECT pipeline_id FROM pipelines
                WHERE software_name = ? AND software_version = ?
                """, """
                INSERT INTO pipelines (software_name, software_version, settings_used)
                VALUES (?, ?, ?)
                """, "GUI CNV Importer", "0.1.0", "strict_all_or_nothing=true");
    }

    private long getOrCreateLabProtocol() throws SQLException {
        return getOrCreate("""
                SELECT lab_protocol_id FROM lab_protocols
                WHERE technology = ? AND manufacturer = ?
                """, """
                INSERT INTO lab_protocols (technology, manufacturer, miscellaneous)
                VALUES (?, ?, ?)
                """, "GUI Import", "Unknown", "CNV GUI prototype");
    }

    private long getOrCreateIndividual(String accession) throws SQLException {
        String externalId = "GUI-" + accession;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT individual_id FROM individuals WHERE external_identifier = ?")) {
            ps.setString(1, externalId);
            Long existing = scalarId(ps);
            if (existing != null) {
                return existing;
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO individuals (external_identifier) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, externalId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    private long getOrCreateAccession(String accession, long individualId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT sample_accession_id FROM sample_accessions WHERE accession_identifier = ?")) {
            ps.setString(1, accession);
            Long existing = scalarId(ps);
            if (existing != null) {
                return existing;
            }
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO sample_accessions (accession_identifier, individual_id, dna_source)
                VALUES (?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, accession);
            ps.setLong(2, individualId);
            ps.setString(3, "unknown");
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    private long createSampleTest(long accessionId, long protocolId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO sample_tests (sample_accession_id, lab_protocol_id, test_type)
                VALUES (?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, accessionId);
            ps.setLong(2, protocolId);
            ps.setString(3, "CNV");
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    private long createSourceFile(Path path, long pipelineId, int rowCount) throws SQLException {
        boolean duplicate = duplicateSourceFilePath(path);
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO source_files (file_name, file_path, pipeline_id, import_status, row_count, notes)
                VALUES (?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, path.getFileName().toString());
            ps.setString(2, path.toAbsolutePath().normalize().toString());
            ps.setLong(3, pipelineId);
            ps.setString(4, "SUCCESS");
            ps.setInt(5, rowCount);
            ps.setString(6, duplicate
                    ? "Imported by Swing GUI; WARNING: this file path was imported before"
                    : "Imported by Swing GUI");
            ps.executeUpdate();
            long sourceFileId = generatedId(ps);
            if (duplicate) {
                createValidationIssue(
                        null,
                        sourceFileId,
                        null,
                        null,
                        "Duplicate Source File",
                        "This file path was imported before: " + path.toAbsolutePath().normalize(),
                        "WARNING");
            }
            return sourceFileId;
        }
    }

    private boolean duplicateSourceFilePath(Path path) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM source_files
                WHERE file_path = ?
                """)) {
            ps.setString(1, path.toAbsolutePath().normalize().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getLong(1) > 0;
            }
        }
    }

    private void createValidationIssue(Long segmentId, Long sourceFileId, Integer lineNumber,
                                       String sampleAccessionId, String issueType,
                                       String issueMessage, String severity) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO validation_issues
                    (segment_id, source_file_id, line_number, sample_accession_id,
                     issue_type, issue_message, severity)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            if (segmentId == null) {
                ps.setNull(1, Types.BIGINT);
            } else {
                ps.setLong(1, segmentId);
            }
            if (sourceFileId == null) {
                ps.setNull(2, Types.BIGINT);
            } else {
                ps.setLong(2, sourceFileId);
            }
            if (lineNumber == null) {
                ps.setNull(3, Types.INTEGER);
            } else {
                ps.setInt(3, lineNumber);
            }
            ps.setString(4, sampleAccessionId);
            ps.setString(5, issueType);
            ps.setString(6, issueMessage);
            ps.setString(7, severity);
            ps.executeUpdate();
        }
    }

    private long createSampleTestResult(long sampleTestId, long pipelineId, long sourceFileId, CnvRow row)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO sample_test_results
                    (sample_test_id, pipeline_id, source_file_id, genome_build, calling_method,
                     raw_iscn, annotation_names, line_number)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, sampleTestId);
            ps.setLong(2, pipelineId);
            ps.setLong(3, sourceFileId);
            ps.setString(4, row.genomeBuild());
            ps.setString(5, "GUI-imported CNV");
            ps.setString(6, row.rawIscn());
            ps.setString(7, row.annotationNames());
            ps.setInt(8, row.lineNumber());
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    private long createSegment(String eventGroupId, long resultId,
                               String chromosome, long start, long stop, CnvRow row)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO genomic_segments
                    (event_group_id, sample_test_result_id, chromosome, start_pos, stop_pos, event_type, copy_number,
                     genome_build, confidence, raw_iscn, raw_segment_text, annotations)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, eventGroupId);
            ps.setLong(2, resultId);
            ps.setString(3, chromosome);
            ps.setLong(4, start);
            ps.setLong(5, stop);
            ps.setString(6, normalizedEventType(row.eventType()));
            ps.setInt(7, row.copyNumber());
            ps.setString(8, row.genomeBuild());
            ps.setString(9, row.confidence());
            ps.setString(10, row.rawIscn());
            ps.setString(11, rawEventText(row));
            ps.setString(12, row.annotations());
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    private void createSegmentAnnotations(long segmentId, String annotationNames, String annotations) throws SQLException {
        String[] names = annotationParts(annotationNames);
        String[] values = annotationParts(annotations);
        int rows = Math.min(names.length, values.length);
        for (int i = 0; i < rows; i++) {
            String sourceColumn = names[i].trim();
            String value = values[i].trim();
            if (sourceColumn.isBlank() || value.isBlank()) {
                continue;
            }
            insertSegmentAnnotation(segmentId, sourceColumn, value, i + 1);
        }
    }

    private void insertSegmentAnnotation(long segmentId, String sourceColumn, String value, int ordinalPosition)
            throws SQLException {
        Double numericValue = parseDoubleValue(value);
        Boolean booleanValue = parseBooleanValue(value);
        String valueType;
        String textValue = null;
        if (booleanValue != null && looksBooleanLike(sourceColumn, value)) {
            valueType = "BOOLEAN";
        } else if (numericValue != null) {
            valueType = "NUMBER";
        } else {
            valueType = "TEXT";
            textValue = value;
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO segment_annotations
                    (segment_id, annotation_name, text_value, numeric_value, boolean_value,
                     value_type, source_column, ordinal_position)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setLong(1, segmentId);
            ps.setString(2, normalizeAnnotationName(sourceColumn));
            ps.setString(3, textValue);
            if (numericValue == null) {
                ps.setNull(4, Types.DOUBLE);
            } else {
                ps.setDouble(4, numericValue);
            }
            if (booleanValue == null) {
                ps.setNull(5, Types.BOOLEAN);
            } else {
                ps.setBoolean(5, booleanValue);
            }
            ps.setString(6, valueType);
            ps.setString(7, sourceColumn);
            ps.setInt(8, ordinalPosition);
            ps.executeUpdate();
        }
    }

    private void createGenomicLink(Long genomicEventGroupId, Long eventId, String eventGroupId,
                                   long sourceSegmentId, long targetSegmentId,
                                   String linkType, String evidence, String confidence) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO genomic_links
                    (genomic_event_group_id, event_id, event_group_id, source_segment_id, target_segment_id, link_type, orientation, evidence, confidence)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            if (genomicEventGroupId == null) {
                ps.setNull(1, java.sql.Types.BIGINT);
            } else {
                ps.setLong(1, genomicEventGroupId);
            }
            if (eventId == null) {
                ps.setNull(2, java.sql.Types.BIGINT);
            } else {
                ps.setLong(2, eventId);
            }
            ps.setString(3, eventGroupId);
            ps.setLong(4, sourceSegmentId);
            ps.setLong(5, targetSegmentId);
            ps.setString(6, linkType);
            ps.setString(7, null);
            ps.setString(8, evidence);
            ps.setString(9, confidence);
            ps.executeUpdate();
        }
    }

    private void logImport(Path path, String status, int rowsRead, int rowsWithErrors, int rowsInserted,
                           LocalDateTime attemptedAt) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO import_history
                    (file_name, file_path, import_status, num_rows_read, num_rows_with_errors,
                     num_rows_inserted, attempted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, path.getFileName().toString());
            ps.setString(2, path.toAbsolutePath().normalize().toString());
            ps.setString(3, status);
            ps.setInt(4, rowsRead);
            ps.setInt(5, rowsWithErrors);
            ps.setInt(6, rowsInserted);
            ps.setObject(7, attemptedAt);
            ps.executeUpdate();
        }
    }

    private long getOrCreate(String selectSql, String insertSql, String first, String second, String third)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(selectSql)) {
            ps.setString(1, first);
            ps.setString(2, second);
            Long existing = scalarId(ps);
            if (existing != null) {
                return existing;
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, first);
            ps.setString(2, second);
            ps.setString(3, third);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    private Long scalarId(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : null;
        }
    }

    private long generatedId(PreparedStatement ps) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("No generated key returned");
            }
            return keys.getLong(1);
        }
    }

    private long count(String table) throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private List<String> normalizeHeader(String line) {
        String[] parts = line.split("\\t", -1);
        List<String> header = new ArrayList<>();
        for (String part : parts) {
            header.add(normalizeColumn(part));
        }
        return header;
    }

    private String normalizeColumn(String column) {
        String value = column.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\-]+", "_")
                .replaceAll("_+", "_");
        return switch (value) {
            case "sample", "sampleid", "sample_id", "accession", "accession_id" -> "sample_accession_id";
            case "group_id", "event_id", "variant_id", "pair_id", "link_id", "breakend_id" -> "event_group_id";
            case "chr" -> "chromosome";
            case "start", "bp1", "start_position" -> "start_pos";
            case "end", "end_pos", "stop", "bp2", "stop_position", "end_position" -> "stop_pos";
            case "sv_type", "cnv_type", "aberration", "eventtype" -> "event_type";
            case "cn", "copy", "copynumber", "copy_number" -> "copy_number";
            case "arrayscore", "array_score", "score", "cnvscore" -> "array_score";
            case "probe_count", "probecount", "numprobes", "num_probes", "numberofprobes", "number_of_probes",
                    "numberofsites", "number_of_sites" -> "number_of_sites";
            case "confidencescore", "callconfidence" -> "confidence";
            case "iscn" -> "raw_iscn";
            case "hg_version", "genomebuild", "genome_build", "build", "assembly", "grid_genomicbuild" -> "genome_build";
            default -> value;
        };
    }

    private boolean isUnsupportedRawArrayEvidence(List<String> header) {
        return containsAny(header,
                "probename",
                "systematicname",
                "pvaluelogratio",
                "gprocessedsignal",
                "rprocessedsignal",
                "allelea",
                "alleleb",
                "baf",
                "lrr",
                "mapinfo")
                && !(header.contains("sample_accession_id")
                && header.contains("chromosome")
                && header.contains("start_pos")
                && header.contains("stop_pos")
                && header.contains("event_type"));
    }

    private boolean containsAny(List<String> values, String... candidates) {
        for (String candidate : candidates) {
            if (values.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Integer> indexes(List<String> header) {
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            indexes.put(header.get(i), i);
        }
        return indexes;
    }

    private Map<String, String> values(List<String> header, String[] parts) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            values.put(header.get(i), clean(parts[i]));
        }
        return values;
    }

    private String value(Map<String, String> values, String key) {
        return values.get(key);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeChromosome(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.toLowerCase(Locale.ROOT).startsWith("chr") ? value : "chr" + value;
    }

    private String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private Long parseLong(String value) {
        try {
            return value == null ? null : Long.parseLong(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseInt(String value) {
        try {
            return value == null ? null : Integer.parseInt(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double parseDouble(String value) {
        try {
            return value == null ? null : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double parseDoubleValue(String value) {
        try {
            return value == null ? null : Double.parseDouble(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean parseBooleanValue(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "y", "present", "positive", "pos", "1" -> true;
            case "false", "no", "n", "absent", "negative", "neg", "0" -> false;
            default -> null;
        };
    }

    private boolean looksBooleanLike(String sourceColumn, String value) {
        String normalizedValue = value.trim().toLowerCase(Locale.ROOT);
        if (!List.of("true", "false", "yes", "no", "y", "n", "present", "absent",
                "positive", "negative", "pos", "neg", "0", "1").contains(normalizedValue)) {
            return false;
        }
        String normalizedName = sourceColumn.trim().toLowerCase(Locale.ROOT);
        return normalizedName.startsWith("is_")
                || normalizedName.startsWith("has_")
                || normalizedName.endsWith("_flag")
                || normalizedName.endsWith("_status")
                || List.of("stitched", "is_valid").contains(normalizedName);
    }

    private String normalizeAnnotationName(String sourceColumn) {
        String compact = sourceColumn.trim()
                .replaceAll("[\\s\\-]+", "_")
                .replaceAll("_+", "_");
        String lower = compact.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "gene", "genes" -> "Gene";
            case "lumpy" -> "Lumpy";
            case "cnvnator" -> "CNVNATOR";
            case "gnomad", "gnomad_count", "gnomad_sv_count" -> "gnomAD_count";
            case "dgv_pop_percent", "dgv_percent", "dgv_population_percent" -> "DGV_pop_percent";
            case "probecount", "probe_count", "numprobes", "num_probes",
                    "numberofprobes", "number_of_probes", "numberofsites", "number_of_sites" -> "probe_count";
            case "lrr", "meanlrr", "mean_lrr", "lrr_value" -> "LRR";
            case "baf", "meanbaf", "mean_baf", "baf_pattern" -> "BAF";
            default -> compact;
        };
    }

    private Integer resolveCopyNumber(String explicitCopyNumber, String eventType) {
        Integer parsed = parseInt(explicitCopyNumber);
        if (parsed != null) {
            return parsed;
        }
        return switch (eventType == null ? "" : eventType) {
            case "DUP", "GAIN", "AMP", "TRISOMY" -> 3;
            case "DEL", "LOSS", "MONOSOMY" -> 1;
            case "NEUTRAL", "INV", "INS", "TRANS", "T", "DER", "DIC", "R", "RING", "COMPLEX", "ROH", "UPD" -> 2;
            default -> null;
        };
    }

    private String normalizeGenomeBuild(String build) {
        if (build == null || build.isBlank()) {
            return null;
        }
        String buildValue = normalizeGenomeBuildMetadataValue(build);
        String normalized = buildValue.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[_\\-]+", " ")
                .replaceAll("\\s+", " ");
        return switch (normalized) {
            case "grch37", "hg19", "build 37", "b37", "genome build 37", "human genome build 37" -> "GRCh37";
            case "grch38", "hg38", "build 38", "b38", "genome build 38", "human genome build 38" -> "GRCh38";
            case "t2t", "chm13", "t2t chm13", "chm13v2", "chm13v2.0" -> "T2T-CHM13";
            case "ncbi36", "hg18", "build 36", "b36" -> "NCBI36";
            default -> null;
        };
    }

    private String normalizeGenomeBuildMetadataValue(String value) {
        String[] parts = value.split("[:;]");
        for (String part : parts) {
            String normalized = part.trim()
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("[_\\-]+", " ")
                    .replaceAll("\\s+", " ");
            if (Set.of(
                    "grch37", "hg19", "build 37", "b37", "genome build 37", "human genome build 37",
                    "grch38", "hg38", "build 38", "b38", "genome build 38", "human genome build 38",
                    "t2t", "chm13", "t2t chm13", "chm13v2", "chm13v2.0",
                    "ncbi36", "hg18", "build 36", "b36").contains(normalized)) {
                return part.trim();
            }
        }
        return value;
    }

    private boolean isBreakpointEvent(String eventType) {
        return switch (eventType == null ? "" : eventType) {
            case "TRANS", "T", "INV", "INS", "DER", "DIC", "R", "RING", "COMPLEX" -> true;
            default -> false;
        };
    }

    private boolean shouldLinkGroupedSegment(CnvRow row, EventGroupState groupState) {
        return groupState != null
                && groupState.firstTransSegmentId() != null;
    }

    private String eventGroupType(String eventType) {
        return switch (eventType == null ? "" : eventType) {
            case "TRANS", "T" -> "TRANSLOCATION";
            case "INV" -> "INVERSION";
            case "INS" -> "INSERTION";
            case "DER" -> "DERIVATIVE";
            case "DIC" -> "DICENTRIC";
            case "R", "RING" -> "RING";
            case "COMPLEX" -> "COMPLEX";
            default -> "GROUPED_EVENT";
        };
    }

    private String normalizedEventType(String eventType) {
        return "T".equals(eventType) ? "TRANS" : eventType;
    }

    private String linkEvidence(CnvRow row) {
        if (row.rawIscn() != null && !row.rawIscn().isBlank()) {
            return row.rawIscn();
        }
        return row.eventGroupId();
    }

    private String rawEventText(CnvRow row) {
        String rawText;
        if (row.rawIscn() != null && !row.rawIscn().isBlank()) {
            rawText = row.rawIscn();
        } else {
            rawText = row.sample() + " " + row.chromosome() + ":" + row.start() + "-" + row.stop()
                    + " " + row.eventType();
        }
        return rawText.length() <= 2000 ? rawText : rawText.substring(0, 2000);
    }

    private AnnotationPair resolveAnnotationPair(Map<String, String> values) {
        String explicit = value(values, "annotation_names");
        String explicitValues = value(values, "annotations");
        if (explicit != null && explicitValues != null) {
            return explicitAnnotationPair(explicit, explicitValues);
        }
        return new AnnotationPair(resolveAnnotationNames(values), resolveAnnotations(values));
    }

    private AnnotationPair explicitAnnotationPair(String rawNames, String rawValues) {
        String[] rawNameParts = annotationParts(rawNames);
        String[] rawValueParts = annotationParts(rawValues);
        List<String> keptNames = new ArrayList<>();
        List<String> keptValues = new ArrayList<>();
        for (int i = 0; i < rawNameParts.length; i++) {
            String name = rawNameParts[i].trim();
            String value = i < rawValueParts.length ? rawValueParts[i].trim() : "";
            if (name.isBlank() || CORE_COLUMNS.contains(normalizeColumn(name))) {
                continue;
            }
            keptNames.add(name);
            keptValues.add(value);
        }
        return new AnnotationPair(
                keptNames.isEmpty() ? null : String.join("|", keptNames),
                keptValues.isEmpty() ? null : String.join("|", keptValues));
    }

    private String resolveAnnotationNames(Map<String, String> values) {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!CORE_COLUMNS.contains(entry.getKey())) {
                names.add(entry.getKey());
            }
        }
        return names.isEmpty() ? null : String.join("|", names);
    }

    private String resolveAnnotations(Map<String, String> values) {
        String explicit = value(values, "annotations");
        if (explicit != null) {
            return normalizeAnnotationList(explicit);
        }
        List<String> annotationValues = new ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!CORE_COLUMNS.contains(entry.getKey())) {
                annotationValues.add(entry.getValue() == null ? "" : entry.getValue());
            }
        }
        return annotationValues.isEmpty() ? null : String.join("|", annotationValues);
    }

    private int countParts(String value) {
        return value == null || value.isEmpty() ? 0 : annotationParts(value).length;
    }

    private String normalizeAnnotationList(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return String.join("|", annotationParts(value));
    }

    private String[] annotationParts(String value) {
        return value.split("[|;]", -1);
    }

    private String fileSummary(Path file, int rows, int errors, String status, LocalDateTime attemptedAt) {
        return "File: " + file.getFileName()
                + " | Number of rows: " + format(rows)
                + " | Number of errors: " + format(errors)
                + " | " + status
                + " | Attempted: " + attemptedAt.format(DISPLAY_TIME);
    }

    private Path readRememberedDatabase() {
        if (!Files.isRegularFile(configPath)) {
            return null;
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(configPath)) {
            props.load(in);
            String path = props.getProperty("database.path");
            return path == null || path.isBlank() ? null : Path.of(path);
        } catch (IOException e) {
            return null;
        }
    }

    private void saveRememberedDatabase(Path path) {
        try {
            Files.createDirectories(configPath.getParent());
            Properties props = new Properties();
            props.setProperty("database.path", path.toString());
            try (var out = Files.newOutputStream(configPath)) {
                props.store(out, "CNV Database GUI settings");
            }
        } catch (IOException e) {
            setStatus("CONFIG WARNING: could not save db_gui.properties: " + e.getMessage());
        }
    }

    private Path appDirectory() {
        try {
            Path location = Path.of(CnvDatabaseGui.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return Files.isRegularFile(location) ? location.getParent() : location;
        } catch (Exception e) {
            return Path.of(".").toAbsolutePath().normalize();
        }
    }

    private Path stripH2Suffix(Path path) {
        String text = path.toString();
        if (text.endsWith(".mv.db")) {
            return Path.of(text.substring(0, text.length() - ".mv.db".length()));
        }
        return path;
    }

    private boolean isConnected() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    private String universalQuery() {
        return universalQueryWithoutLimit() + "\nLIMIT 100";
    }

    private String universalQueryWithoutLimit() {
        return """
                SELECT
                    'CNV/SV' AS result_type,
                    gs.segment_id AS result_id,
                    sa.accession_identifier AS sample_id,
                    gs.chromosome,
                    gs.start_pos,
                    gs.stop_pos,
                    gs.event_type AS event_or_variant_type,
                    CAST(gs.copy_number AS VARCHAR) AS copy_or_genotype,
                    (
                        SELECT LISTAGG(san.annotation_name || '=' ||
                            COALESCE(san.text_value,
                                     CASE
                                         WHEN san.numeric_value IS NULL THEN NULL
                                         WHEN san.numeric_value = FLOOR(san.numeric_value) THEN CAST(CAST(san.numeric_value AS BIGINT) AS VARCHAR)
                                         ELSE CAST(san.numeric_value AS VARCHAR)
                                     END,
                                     CAST(san.boolean_value AS VARCHAR),
                                     ''), '; ')
                               WITHIN GROUP (ORDER BY san.ordinal_position, san.annotation_id)
                        FROM segment_annotations san
                        WHERE san.segment_id = gs.segment_id
                    ) AS annotations_or_genes,
                    gs.genome_build,
                    sf.file_name AS source_file,
                    gs.raw_segment_text AS summary
                FROM genomic_segments gs
                JOIN sample_test_results str ON str.sample_test_result_id = gs.sample_test_result_id
                JOIN sample_tests st ON st.sample_test_id = str.sample_test_id
                JOIN sample_accessions sa ON sa.sample_accession_id = st.sample_accession_id
                LEFT JOIN source_files sf ON sf.source_file_id = str.source_file_id
                UNION ALL
                SELECT
                    'SNV/indel' AS result_type,
                    sv.small_variant_id AS result_id,
                    sa.accession_identifier AS sample_id,
                    sv.chromosome,
                    sv.position AS start_pos,
                    sv.position AS stop_pos,
                    sv.variant_type AS event_or_variant_type,
                    svc.genotype AS copy_or_genotype,
                    (
                        SELECT LISTAGG(COALESCE(sva.gene, '') || ':' ||
                                       COALESCE(sva.consequence, '') || ':' ||
                                       COALESCE(sva.impact, ''),
                                       '; ')
                               WITHIN GROUP (ORDER BY sva.small_variant_annotation_id)
                        FROM small_variant_annotations sva
                        WHERE sva.small_variant_id = sv.small_variant_id
                    ) AS annotations_or_genes,
                    sv.genome_build,
                    sf.file_name AS source_file,
                    sv.ref_allele || '>' || sv.alt_allele || ' ' || COALESCE(sv.variant_id, '') AS summary
                FROM small_variants sv
                JOIN small_variant_sample_calls svc ON svc.small_variant_id = sv.small_variant_id
                JOIN sample_test_results str ON str.sample_test_result_id = svc.sample_test_result_id
                JOIN sample_tests st ON st.sample_test_id = str.sample_test_id
                JOIN sample_accessions sa ON sa.sample_accession_id = st.sample_accession_id
                LEFT JOIN source_files sf ON sf.source_file_id = str.source_file_id
                ORDER BY chromosome, start_pos, stop_pos, result_type
                """.strip();
    }

    private String smallVariantQuery() {
        return smallVariantQueryWithoutLimit() + "\nLIMIT 100";
    }

    private String smallVariantQueryWithoutLimit() {
        return """
                SELECT
                    sv.small_variant_id,
                    sa.accession_identifier AS sample_id,
                    sv.chromosome,
                    sv.position,
                    sv.variant_id,
                    sv.ref_allele,
                    sv.alt_allele,
                    sv.variant_type,
                    sv.genome_build,
                    svc.qual,
                    svc.filter_status,
                    svc.genotype,
                    svc.phased,
                    svc.ref_depth,
                    svc.alt_depth,
                    svc.total_depth,
                    svc.genotype_quality,
                    svc.allele_balance,
                    (
                        SELECT LISTAGG(DISTINCT sva.gene, '; ')
                        FROM small_variant_annotations sva
                        WHERE sva.small_variant_id = sv.small_variant_id
                          AND sva.gene IS NOT NULL
                    ) AS genes,
                    (
                        SELECT LISTAGG(sva.consequence || ':' || sva.impact || ':' ||
                                       COALESCE(sva.transcript, '') || ':' ||
                                       COALESCE(sva.hgvs_c, '') || ':' ||
                                       COALESCE(sva.hgvs_p, ''),
                                       '; ')
                               WITHIN GROUP (ORDER BY sva.small_variant_annotation_id)
                        FROM small_variant_annotations sva
                        WHERE sva.small_variant_id = sv.small_variant_id
                    ) AS variant_annotations,
                    sf.file_name AS source_file
                FROM small_variants sv
                JOIN small_variant_sample_calls svc ON svc.small_variant_id = sv.small_variant_id
                JOIN sample_test_results str ON str.sample_test_result_id = svc.sample_test_result_id
                JOIN sample_tests st ON st.sample_test_id = str.sample_test_id
                JOIN sample_accessions sa ON sa.sample_accession_id = st.sample_accession_id
                LEFT JOIN source_files sf ON sf.source_file_id = str.source_file_id
                ORDER BY sv.small_variant_id DESC
                """.strip();
    }

    private String defaultQuery() {
        return defaultQueryWithoutLimit() + "\nLIMIT 100";
    }

    private String defaultQueryWithoutLimit() {
        return """
                SELECT
                    gs.event_group_id,
                    sa.accession_identifier AS sample_id,
                    gs.chromosome,
                    gs.start_pos,
                    gs.stop_pos,
                    gs.event_type,
                    gs.copy_number,
                    gs.genome_build,
                    gs.confidence,
                    gs.raw_iscn,
                    (
                        SELECT LISTAGG(sa.annotation_name || '=' ||
                            COALESCE(sa.text_value,
                                     CASE
                                         WHEN sa.numeric_value IS NULL THEN NULL
                                         WHEN sa.numeric_value = FLOOR(sa.numeric_value) THEN CAST(CAST(sa.numeric_value AS BIGINT) AS VARCHAR)
                                         ELSE CAST(sa.numeric_value AS VARCHAR)
                                     END,
                                     CAST(sa.boolean_value AS VARCHAR),
                                     ''), '; ')
                               WITHIN GROUP (ORDER BY sa.ordinal_position, sa.annotation_id)
                        FROM segment_annotations sa
                        WHERE sa.segment_id = gs.segment_id
                    ) AS segment_annotations,
                    (gs.stop_pos - gs.start_pos + 1) AS length_bp,
                    str.calling_method,
                    sf.file_name AS source_file
                FROM genomic_segments gs
                JOIN sample_test_results str ON str.sample_test_result_id = gs.sample_test_result_id
                JOIN sample_tests st ON st.sample_test_id = str.sample_test_id
                JOIN sample_accessions sa ON sa.sample_accession_id = st.sample_accession_id
                LEFT JOIN source_files sf ON sf.source_file_id = str.source_file_id
                ORDER BY gs.segment_id DESC
                """.strip();
    }

    private String format(long value) {
        return String.format(Locale.US, "%,d", value);
    }

    private void setStatus(String text) {
        messages.setText("[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + text);
    }

    private void showError(String title, String message) {
        JOptionPane.showMessageDialog(this, message, title, JOptionPane.ERROR_MESSAGE);
    }

    private void showInfo(String title, String message) {
        JOptionPane.showMessageDialog(this, message, title, JOptionPane.INFORMATION_MESSAGE);
    }

    private record ImportPlan(List<CnvRow> rows, List<String> errors, int rowsRead) {
    }

    private record CnvRow(
            int lineNumber,
            String sample,
            String eventGroupId,
            String chromosome,
            long start,
            long stop,
            String eventType,
            int copyNumber,
            String genomeBuild,
            String confidence,
            String rawIscn,
            String annotationNames,
            String annotations
    ) {
    }

    private record AnnotationPair(String names, String values) {
    }

    private static final class EventGroupState {
        private final String eventGroupId;
        private final String eventGroupLabel;
        private final String eventGroupType;
        private Long firstTransSegmentId;

        private EventGroupState(String eventGroupId, String eventGroupLabel, String eventGroupType) {
            this.eventGroupId = eventGroupId;
            this.eventGroupLabel = eventGroupLabel;
            this.eventGroupType = eventGroupType;
        }

        private String eventGroupId() {
            return eventGroupId;
        }

        private String eventGroupLabel() {
            return eventGroupLabel;
        }

        private String eventGroupType() {
            return eventGroupType;
        }

        private Long firstTransSegmentId() {
            return firstTransSegmentId;
        }

        private void addSegment(long segmentId, String eventType) {
            if (firstTransSegmentId == null) {
                firstTransSegmentId = segmentId;
            }
        }
    }

    private record ResultContext(long resultId) {
    }
}
