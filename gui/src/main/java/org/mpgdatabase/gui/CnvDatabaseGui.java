package org.mpgdatabase.gui;

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
            "ADD", "DER", "IDIC", "DIC", "I", "R", "NEUTRAL", "UNKNOWN"
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
            "array_score",
            "number_of_sites",
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
        JMenuItem importFile = new JMenuItem("Import CNV File...");
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
        addPreset(presets, "Show all CNV calls", defaultQuery());
        addPreset(presets, "Show recent imports", """
                SELECT import_history_id, file_name, import_status, num_rows_read,
                       num_rows_with_errors, num_rows_inserted, attempted_at
                FROM import_history
                ORDER BY import_history_id DESC
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
                event_group_id, copy_number, confidence, array_score, number_of_sites,
                raw_iscn, annotation_names, annotations, and format-specific extra annotation columns.

                If copy_number is missing, DEL/LOSS infer 1 and DUP/GAIN/AMP infer 3.
                TRANS/T can infer copy_number 2. If multiple TRANS/T rows share the same
                sample_accession_id and event_group_id, the GUI creates one genomic_links row.
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
        panel.add(new JLabel("CNV Calls:"), gbc);
        gbc.gridx = 9;
        cnvCallsCountLabel = new JLabel("0");
        panel.add(cnvCallsCountLabel, gbc);
        return panel;
    }

    private JPanel importPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Import CNV File"));
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
        createSchema(connection);
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
        chooser.setDialogTitle("Import CNV TSV file");
        chooser.setFileFilter(new FileNameExtensionFilter("CNV/TSV/TXT files", "cnv", "tsv", "txt"));
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
            ImportPlan plan = validateImport(path);
            if (!plan.errors().isEmpty()) {
                logImport(path, "Failed", plan.rowsRead(), plan.errors().size(), 0, attemptedAt);
                fileSummaryLabel.setText(fileSummary(path, plan.rowsRead(), plan.errors().size(), "Import failed", attemptedAt));
                setStatus("IMPORT FAILED: " + plan.errors().size() + " rows had validation errors. No CNV rows inserted.");
                showError("Import failed", String.join("\n", plan.errors().stream().limit(10).toList()));
                return;
            }
            int inserted = insertRows(path, plan.rows(), attemptedAt);
            logImport(path, "Success", plan.rowsRead(), 0, inserted, attemptedAt);
            fileSummaryLabel.setText(fileSummary(path, plan.rowsRead(), 0, "Import succeeded", attemptedAt));
            setStatus("IMPORT SUCCESS: " + inserted + " rows imported.");
            refreshStatus();
        } catch (Exception e) {
            try {
                logImport(path, "Failed", 0, 1, 0, attemptedAt);
            } catch (SQLException ignored) {
            }
            fileSummaryLabel.setText(fileSummary(path, 0, 1, "Import failed", attemptedAt));
            setStatus("IMPORT FAILED: " + e.getMessage());
            showError("Import failed", e.getMessage());
        }
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
        String genomeBuild = value(values, "genome_build");
        Long start = parseLong(value(values, "start_pos"));
        Long stop = parseLong(value(values, "stop_pos"));
        Integer copyNumber = resolveCopyNumber(value(values, "copy_number"), eventType);
        Double arrayScore = parseDouble(value(values, "array_score"));
        Integer sites = parseInt(value(values, "number_of_sites"));
        String confidence = value(values, "confidence");
        String rawIscn = value(values, "raw_iscn");
        String annotationNames = resolveAnnotationNames(values);
        String annotations = resolveAnnotations(values);

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
        if (genomeBuild == null || "unknown".equalsIgnoreCase(genomeBuild)) {
            rowErrors.add("missing genome_build");
        }
        if (annotationNames != null && annotations != null && countParts(annotationNames) != countParts(annotations)) {
            rowErrors.add("annotation_names count does not match annotations count");
        }
        if (!rowErrors.isEmpty()) {
            errors.add("Line " + lineNumber + ": " + String.join("; ", rowErrors));
            return null;
        }
        return new CnvRow(lineNumber, sample, eventGroupId, chromosome, start, stop, eventType, copyNumber,
                genomeBuild, confidence, arrayScore, sites, rawIscn, annotationNames, annotations);
    }

    private int insertRows(Path path, List<CnvRow> rows, LocalDateTime attemptedAt) throws SQLException {
        long pipelineId = getOrCreatePipeline();
        long protocolId = getOrCreateLabProtocol();
        long sourceFileId = createSourceFile(path, pipelineId, rows.size());
        int inserted = 0;
        Map<String, EventGroupState> eventGroups = new LinkedHashMap<>();
        for (CnvRow row : rows) {
            long individualId = getOrCreateIndividual(row.sample());
            long accessionId = getOrCreateAccession(row.sample(), individualId);
            long sampleTestId = createSampleTest(accessionId, protocolId);
            long resultId = createSampleTestResult(sampleTestId, pipelineId, sourceFileId, row);
            EventGroupState groupState = eventGroupState(eventGroups, resultId, sourceFileId, row);
            long eventId = groupState.eventId();
            long sourceSegmentId = createSegment(eventId, resultId, row.chromosome(), row.start(), row.stop(), row);
            inserted++;
            if (shouldLinkGroupedSegment(row, groupState)) {
                createGenomicLink(eventId, groupState.firstTransSegmentId(), sourceSegmentId,
                        "TRANSLOCATION", linkEvidence(row), row.confidence());
            }
            groupState.addSegment(sourceSegmentId, row.eventType());
        }
        return inserted;
    }

    private EventGroupState eventGroupState(Map<String, EventGroupState> eventGroups,
                                            long resultId,
                                            long sourceFileId,
                                            CnvRow row) throws SQLException {
        String groupKey = eventGroupKey(sourceFileId, row);
        if (groupKey == null) {
            return new EventGroupState(createGenomicEvent(resultId, sourceFileId, row), null);
        }
        EventGroupState existing = eventGroups.get(groupKey);
        if (existing != null) {
            return existing;
        }
        EventGroupState created = new EventGroupState(createGenomicEvent(resultId, sourceFileId, row), row.eventGroupId());
        eventGroups.put(groupKey, created);
        return created;
    }

    private String eventGroupKey(long sourceFileId, CnvRow row) {
        if (row.eventGroupId() == null || row.eventGroupId().isBlank()) {
            return null;
        }
        return sourceFileId + "|" + row.sample() + "|" + row.eventGroupId();
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
        long started = System.nanoTime();
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
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
            double elapsed = (System.nanoTime() - started) / 1_000_000_000.0;
            querySummaryLabel.setText(rows + " rows returned, " + columns + " columns");
            queryTimeLabel.setText("Query executed in " + String.format(Locale.ROOT, "%.3f", elapsed) + " sec");
            setStatus("Query executed successfully. " + rows + " rows returned.");
        } catch (SQLException e) {
            setStatus("SQL ERROR: " + e.getMessage());
            showError("SQL error", e.getMessage());
        }
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
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(trimmed);
                }
            }
        }
        addColumnIfMissing(conn, "GENOMIC_SEGMENTS", "EVENT_ID", "BIGINT");
        addColumnIfMissing(conn, "GENOMIC_EVENTS", "EVENT_GROUP_ID", "VARCHAR(128)");
    }

    private void addColumnIfMissing(Connection conn, String table, String column, String type)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = ? AND COLUMN_NAME = ?
                """)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getLong(1) > 0) {
                    return;
                }
            }
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
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
                CREATE TABLE IF NOT EXISTS genomic_segments (
                    segment_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    event_id BIGINT,
                    sample_test_result_id BIGINT NOT NULL,
                    karyotype_id BIGINT,
                    chromosome VARCHAR(32) NOT NULL,
                    start_pos BIGINT NOT NULL,
                    stop_pos BIGINT NOT NULL,
                    cytoband_start VARCHAR(64),
                    cytoband_end VARCHAR(64),
                    event_type VARCHAR(64) NOT NULL,
                    copy_number INTEGER NOT NULL,
                    array_score DOUBLE PRECISION,
                    confidence VARCHAR(64),
                    number_of_sites INTEGER,
                    annotations VARCHAR(8192),
                    FOREIGN KEY (event_id) REFERENCES genomic_events(event_id),
                    FOREIGN KEY (sample_test_result_id) REFERENCES sample_test_results(sample_test_result_id),
                    CHECK (start_pos <= stop_pos)
                );
                CREATE INDEX IF NOT EXISTS idx_segments_region
                    ON genomic_segments(chromosome, start_pos, stop_pos);
                CREATE INDEX IF NOT EXISTS idx_segments_event
                    ON genomic_segments(event_id);
                CREATE TABLE IF NOT EXISTS genomic_links (
                    link_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    event_id BIGINT NOT NULL,
                    source_segment_id BIGINT NOT NULL,
                    target_segment_id BIGINT NOT NULL,
                    link_type VARCHAR(64) NOT NULL,
                    orientation VARCHAR(64),
                    evidence VARCHAR(1024),
                    confidence VARCHAR(64),
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
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO source_files (file_name, file_path, pipeline_id, import_status, row_count, notes)
                VALUES (?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, path.getFileName().toString());
            ps.setString(2, path.toAbsolutePath().normalize().toString());
            ps.setLong(3, pipelineId);
            ps.setString(4, "SUCCESS");
            ps.setInt(5, rowCount);
            ps.setString(6, "Imported by Swing GUI");
            ps.executeUpdate();
            return generatedId(ps);
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

    private long createGenomicEvent(long resultId, long sourceFileId, CnvRow row) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO genomic_events
                    (sample_test_result_id, source_file_id, event_group_id, event_type, genome_build, calling_method,
                     raw_event_text, line_number, event_status, confidence, annotations)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, resultId);
            ps.setLong(2, sourceFileId);
            ps.setString(3, row.eventGroupId());
            ps.setString(4, row.eventType());
            ps.setString(5, row.genomeBuild());
            ps.setString(6, "GUI-imported CNV");
            ps.setString(7, row.rawIscn());
            ps.setInt(8, row.lineNumber());
            ps.setString(9, "IMPORTED");
            ps.setString(10, row.confidence());
            ps.setString(11, row.annotations());
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    private long createSegment(long eventId, long resultId, String chromosome, long start, long stop, CnvRow row)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO genomic_segments
                    (event_id, sample_test_result_id, chromosome, start_pos, stop_pos, event_type, copy_number,
                     array_score, confidence, number_of_sites, annotations)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, eventId);
            ps.setLong(2, resultId);
            ps.setString(3, chromosome);
            ps.setLong(4, start);
            ps.setLong(5, stop);
            ps.setString(6, normalizedEventType(row.eventType()));
            ps.setInt(7, row.copyNumber());
            if (row.arrayScore() == null) {
                ps.setObject(8, null);
            } else {
                ps.setDouble(8, row.arrayScore());
            }
            ps.setString(9, row.confidence());
            if (row.numberOfSites() == null) {
                ps.setObject(10, null);
            } else {
                ps.setInt(10, row.numberOfSites());
            }
            ps.setString(11, row.annotations());
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    private void createGenomicLink(long eventId, long sourceSegmentId, long targetSegmentId,
                                   String linkType, String evidence, String confidence) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO genomic_links
                    (event_id, source_segment_id, target_segment_id, link_type, orientation, evidence, confidence)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setLong(1, eventId);
            ps.setLong(2, sourceSegmentId);
            ps.setLong(3, targetSegmentId);
            ps.setString(4, linkType);
            ps.setString(5, null);
            ps.setString(6, evidence);
            ps.setString(7, confidence);
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
        String value = column.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "sample", "sample_id", "accession", "accession_id" -> "sample_accession_id";
            case "group_id", "event_id", "variant_id", "pair_id", "link_id", "breakend_id" -> "event_group_id";
            case "chr" -> "chromosome";
            case "start", "start_position" -> "start_pos";
            case "end", "end_pos", "stop", "stop_position" -> "stop_pos";
            case "sv_type", "cnv_type" -> "event_type";
            case "cn" -> "copy_number";
            case "arrayscore" -> "array_score";
            case "probe_count", "probecount" -> "number_of_sites";
            case "iscn" -> "raw_iscn";
            case "hg_version" -> "genome_build";
            default -> value;
        };
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

    private Integer resolveCopyNumber(String explicitCopyNumber, String eventType) {
        Integer parsed = parseInt(explicitCopyNumber);
        if (parsed != null) {
            return parsed;
        }
        return switch (eventType == null ? "" : eventType) {
            case "DEL", "LOSS" -> 1;
            case "DUP", "GAIN", "AMP" -> 3;
            case "NEUTRAL", "INV", "INS", "TRANS", "T", "DER" -> 2;
            default -> null;
        };
    }

    private boolean isTranslocation(String eventType) {
        return "TRANS".equals(eventType) || "T".equals(eventType);
    }

    private boolean shouldLinkGroupedSegment(CnvRow row, EventGroupState groupState) {
        return groupState.eventGroupId() != null
                && isTranslocation(row.eventType())
                && groupState.firstTransSegmentId() != null;
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

    private String resolveAnnotationNames(Map<String, String> values) {
        String explicit = value(values, "annotation_names");
        if (explicit != null) {
            return explicit;
        }
        List<String> names = new ArrayList<>();
        for (String key : values.keySet()) {
            if (!CORE_COLUMNS.contains(key)) {
                names.add(key);
            }
        }
        return names.isEmpty() ? null : String.join(";", names);
    }

    private String resolveAnnotations(Map<String, String> values) {
        String explicit = value(values, "annotations");
        if (explicit != null) {
            return explicit;
        }
        List<String> annotationValues = new ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!CORE_COLUMNS.contains(entry.getKey())) {
                annotationValues.add(entry.getValue() == null ? "" : entry.getValue());
            }
        }
        return annotationValues.isEmpty() ? null : String.join(";", annotationValues);
    }

    private int countParts(String value) {
        return value == null || value.isEmpty() ? 0 : value.split(";", -1).length;
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

    private String defaultQuery() {
        return defaultQueryWithoutLimit() + "\nLIMIT 100";
    }

    private String defaultQueryWithoutLimit() {
        return """
                SELECT
                    gs.event_id,
                    ge.event_group_id,
                    sa.accession_identifier AS sample_id,
                    gs.chromosome,
                    gs.start_pos,
                    gs.stop_pos,
                    gs.event_type,
                    gs.copy_number,
                    (gs.stop_pos - gs.start_pos + 1) AS length_bp,
                    str.genome_build,
                    str.calling_method,
                    gs.confidence,
                    sf.file_name AS source_file
                FROM genomic_segments gs
                LEFT JOIN genomic_events ge ON ge.event_id = gs.event_id
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
            Double arrayScore,
            Integer numberOfSites,
            String rawIscn,
            String annotationNames,
            String annotations
    ) {
    }

    private static final class EventGroupState {
        private final long eventId;
        private final String eventGroupId;
        private Long firstTransSegmentId;

        private EventGroupState(long eventId, String eventGroupId) {
            this.eventId = eventId;
            this.eventGroupId = eventGroupId;
        }

        private long eventId() {
            return eventId;
        }

        private String eventGroupId() {
            return eventGroupId;
        }

        private Long firstTransSegmentId() {
            return firstTransSegmentId;
        }

        private void addSegment(long segmentId, String eventType) {
            if (firstTransSegmentId == null && ("TRANS".equals(eventType) || "T".equals(eventType))) {
                firstTransSegmentId = segmentId;
            }
        }
    }
}
