package org.tsahr.gui;

import org.tsahr.cli.Main;
import org.tsahr.core.DataTable;
import org.tsahr.core.Fmt;
import org.tsahr.core.MetaAnalysis;
import org.tsahr.core.TsaHr;
import org.tsahr.core.TsaHrResult;
import org.tsahr.core.TsaHrSettings;
import org.tsahr.io.DataReader;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Content of the desktop application for non-R users: load a study table (.xlsx / .csv), set the tsa_hr()
 * arguments, run the analysis, inspect the report, the cumulative table and the TSA chart, and export results.
 * (A plain JPanel so that it can also be laid out and painted without a window.)
 */
public final class AppPanel extends JPanel {
    private DataTable table;
    private File dataFile;
    private TsaHrResult result;

    private final DataModel dataModel = new DataModel();          // "Data" tab: the file as read
    private final DataModel previewModel = new DataModel();       // "Studies (in analysis order)"
    private final JTable dataTable = new JTable(dataModel);
    private final JTable studiesTable = new JTable(previewModel);
    private boolean loading = false;
    private final JLabel fileLabel = new JLabel("No data loaded");

    private final JTextField targetHR = new JTextField("0.80", 6);
    private final JCheckBox observedTarget = new JCheckBox("use observed pooled HR (circular)");
    private final JTextField alpha = new JTextField("0.05", 6);
    private final JTextField power = new JTextField("0.80", 6);
    private final JComboBox<String> allocation = new JComboBox<>(new String[]{"data", "manual"});
    private final JTextField allocationP = new JTextField("0.5", 6);
    private final JComboBox<String> method = new JComboBox<>(MetaAnalysis.METHODS);
    private final JComboBox<String> reInference = new JComboBox<>(new String[]{"standard", "hksj", "hksj_adhoc"});
    private final JComboBox<String> orderBy = new JComboBox<>();
    private final JComboBox<String> route = new JComboBox<>(new String[]{"design", "analysis"});
    private final JComboBox<String> projStat = new JComboBox<>(new String[]{"median", "mean"});
    private final JComboBox<String> ipe = new JComboBox<>(new String[]{"per_study", "pooled"});
    private final JCheckBox fallback = new JCheckBox("If the analysis route fails, fall back to the design route", true);

    // chart options
    private static final String[] PLACEMENT_LABELS = {"Automatic (lower if Z-curve positive)", "Upper part", "Lower part"};
    private static final String[] PLACEMENT_VALUES = {"auto", "upper", "lower"};
    private final JComboBox<String> labelPlacement = new JComboBox<>(PLACEMENT_LABELS);
    private final JCheckBox legend = new JCheckBox("Legend", true);
    private final JCheckBox caption = new JCheckBox("Methods caption", true);
    private final JCheckBox showDaris = new JCheckBox("Theoretical DARIS line", true);
    private final JCheckBox showHist = new JCheckBox("Historical-rate line", true);
    private static final String DEF_XMAX = "1.15", DEF_LABEL = "3.2", DEF_CAPTION = "8.0";
    private final JTextField xmaxMult = new JTextField(DEF_XMAX, 6);
    private final JTextField labelSize = new JTextField(DEF_LABEL, 6);
    private final JTextField captionSize = new JTextField(DEF_CAPTION, 6);

    private final JTextArea report = mono();
    private final JTextArea summary = mono();
    private final JTextArea warnings = mono();
    private final JTable cumTable = new JTable();
    private final JTabbedPane tabs = new JTabbedPane();
    private final ChartPanel chart = new ChartPanel();
    private final JButton run = new JButton("Run analysis");
    private final JButton saveChartBtn = new JButton("Save chart (PNG)\u2026");
    private final JButton chartOptionsBtn = new JButton("Chart options\u2026");
    private final JLabel status = new JLabel(" ");

    private static JTextArea mono() {
        JTextArea a = new JTextArea();
        a.setEditable(false);
        a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        a.setLineWrap(true);          // everything stays visible: no scrolling to the right
        a.setWrapStyleWord(true);
        return a;
    }

    /** Scroll pane for a table, with its column header always attached. */
    private static JScrollPane tableScroll(JTable t) {
        JScrollPane sp = new JScrollPane(t);
        sp.setColumnHeaderView(t.getTableHeader());
        return sp;
    }

    /** Scroll pane that only scrolls vertically (the text wraps to the window width). */
    private static JScrollPane wrapped(JTextArea a) {
        return new JScrollPane(a, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    }

    public AppPanel() {
        super(new BorderLayout());

        JPanel left = new JPanel(new BorderLayout(6, 6));
        left.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 4));
        JScrollPane settingsScroll = new JScrollPane(settingsPanel(), JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        settingsScroll.setBorder(null);
        JSplitPane leftSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, true, dataPanel(), settingsScroll);
        leftSplit.setResizeWeight(0.0);
        leftSplit.setDividerLocation(265);
        leftSplit.setBorder(null);
        left.add(leftSplit, BorderLayout.CENTER);
        run.setFont(run.getFont().deriveFont(Font.BOLD, 14f));
        run.addActionListener(this::runAnalysis);
        left.add(run, BorderLayout.SOUTH);
        left.setPreferredSize(new Dimension(440, 100));

        tabs.addTab("TSA chart", chart);
        tabs.addTab("Summary", wrapped(summary));
        tabs.addTab("Cumulative results", tableScroll(cumTable));
        tabs.addTab("Full log", wrapped(report));
        tabs.addTab("Warnings", wrapped(warnings));
        tabs.addTab("Data", tableScroll(dataTable));
        JPanel plotBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        chartOptionsBtn.addActionListener(e -> chartOptionsDialog());
        plotBar.add(chartOptionsBtn);
        saveChartBtn.addActionListener(e -> saveChart());
        plotBar.add(saveChartBtn);
        JPanel right = new JPanel(new BorderLayout());
        right.add(plotBar, BorderLayout.NORTH);
        right.add(tabs, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerLocation(440);
        add(split, BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        add(status, BorderLayout.SOUTH);

        orderBy.addItem("(row order)");
        orderBy.addActionListener(e -> { if (!loading) refreshPreview(); });
        observedTarget.addActionListener(e -> targetHR.setEnabled(!observedTarget.isSelected()));
    }

    // ------------------------------------------------------------------ layout
    JMenuBar menu() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");
        file.add(item("Open data file (.xlsx / .csv)\u2026", e -> openDialog()));
        file.add(item("Load example data (20 studies)", e -> loadExample("HR_meta.xlsx")));
        file.add(item("Load example data (40 studies)", e -> loadExample("HR_meta_2.xlsx")));
        file.addSeparator();
        file.add(item("Save chart as PNG\u2026 (150\u20131200 dpi)", e -> saveChart()));
        file.add(item("Export cumulative table (TSV)\u2026", e -> saveText(result == null ? null : result.cumulativeTsv(), "cumulative.tsv")));
        file.add(item("Export boundaries (TSV)\u2026", e -> saveText(result == null ? null : result.boundaryTsv(), "boundaries.tsv")));
        file.add(item("Export report (text)\u2026", e -> saveText(result == null ? null
                : result.log + "\n" + result.printText() + "\n" + result.summaryText(), "tsa_report.txt")));
        file.addSeparator();
        file.add(item("Quit", e -> System.exit(0)));
        JMenu help = new JMenu("Help");
        help.add(item("Data format", e -> JOptionPane.showMessageDialog(this, FORMAT_HELP, "Data format", JOptionPane.INFORMATION_MESSAGE)));
        help.add(item("About / licence", e -> JOptionPane.showMessageDialog(this,
                "tsahr-java " + Main.VERSION + "\nJava edition of the R package tsahr (parity with tsahr "
                        + TsaHr.TSAHR_PARITY_VERSION + ").\n\nThe boundary engine derives from RTSA (Soerensen, Olsen, Lange, Gluud).\n"
                        + "Licence: GPL (>= 2).\nSource: https://github.com/tarak-dhaouadi/tsahr-java",
                "About", JOptionPane.INFORMATION_MESSAGE)));
        bar.add(file);
        bar.add(help);
        return bar;
    }

    static final String FORMAT_HELP = "One row per study, in chronological order (or pick a column to sort by):\n\n"
            + "Study, log_HR, Std_Error, Events_Treatment, N_treatment, Events_controls, N_controls\n\n"
            + "Header spaces are replaced by underscores. Extra columns (e.g. Year) are kept and can be used for sorting.";

    private static JMenuItem item(String t, java.awt.event.ActionListener l) {
        JMenuItem i = new JMenuItem(t);
        i.addActionListener(l);
        return i;
    }

    private JPanel dataPanel() {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(BorderFactory.createTitledBorder("Data"));
        JPanel head = new JPanel(new GridLayout(0, 1, 4, 4));
        JPanel b = new JPanel(new GridLayout(1, 2, 4, 0));
        JButton open = new JButton("Open file\u2026");
        open.addActionListener(e -> openDialog());
        JButton example = new JButton("Example data \u25be");
        JPopupMenu examples = new JPopupMenu();
        examples.add(item("20 studies (HR_meta)", e -> loadExample("HR_meta.xlsx")));
        examples.add(item("40 studies (HR_meta_2)", e -> loadExample("HR_meta_2.xlsx")));
        example.addActionListener(e -> examples.show(example, 0, example.getHeight()));
        b.add(open);
        b.add(example);
        head.add(b);
        head.add(fileLabel);                                  // full path in the tooltip
        p.add(head, BorderLayout.NORTH);
        studiesTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        studiesTable.setFillsViewportHeight(true);
        studiesTable.getTableHeader().setReorderingAllowed(false);
        JScrollPane sp = tableScroll(studiesTable);
        sp.setBorder(BorderFactory.createTitledBorder("Studies (in analysis order)"));
        sp.setPreferredSize(new Dimension(300, 190));
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    /** Shows the studies in the order the analysis will use (sorted by the chosen "Order studies by" column). */
    private void refreshPreview() {
        if (table == null) return;
        String ob = (String) orderBy.getSelectedItem();
        previewModel.set(ob == null || ob.startsWith("(") ? table : sortedBy(table, ob));
        for (int c = 0; c < studiesTable.getColumnCount(); c++) {
            int w = studiesTable.getTableHeader().getDefaultRenderer()
                    .getTableCellRendererComponent(studiesTable, studiesTable.getColumnName(c), false, false, -1, c).getPreferredSize().width;
            for (int r = 0; r < Math.min(studiesTable.getRowCount(), 300); r++)
                w = Math.max(w, studiesTable.getCellRenderer(r, c)
                        .getTableCellRendererComponent(studiesTable, studiesTable.getValueAt(r, c), false, false, r, c).getPreferredSize().width);
            studiesTable.getColumnModel().getColumn(c).setPreferredWidth(Math.min(w + 16, 230));
        }
    }

    /** Copy of the table sorted ascending by a column, the way tsa_hr() sorts (stable; missing values last). */
    private static DataTable sortedBy(DataTable t, String column) {
        DataTable out = t.copy();
        int oc = -1;
        for (int i = 0; i < t.columns.size(); i++)
            if (t.columns.get(i).trim().replace(' ', '_').equals(column)) { oc = i; break; }
        if (oc < 0) return out;
        final int col = oc;
        boolean numeric = true;
        for (Object[] r : out.rows) if (r[col] != null && !(r[col] instanceof Double)) numeric = false;
        final boolean num = numeric;
        out.rows.sort((a, b) -> {
            Object x = a[col], y = b[col];
            if (x == null && y == null) return 0;
            if (x == null) return 1;
            if (y == null) return -1;
            return num ? Double.compare((Double) x, (Double) y) : x.toString().compareTo(y.toString());
        });
        return out;
    }

    private JPanel settingsPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder("Settings"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;
        row = add(p, c, row, "Target HR", targetHR, "Anticipated hazard ratio used to size the required information (target_hr)");
        c.gridx = 0; c.gridy = row++; c.gridwidth = 2; c.weightx = 1;
        observedTarget.setToolTipText("Use the observed pooled HR as the target (circular; exploratory only)");
        p.add(observedTarget, c);
        row = add(p, c, row, "Alpha (two-sided)", alpha, "Two-sided type I error, e.g. 0.05");
        row = add(p, c, row, "Power", power, "e.g. 0.80");
        row = add(p, c, row, "Allocation", allocation, "data: allocation ratio from the arm sizes in the file; manual: use 'Allocation p'");
        row = add(p, c, row, "Allocation p (manual)", allocationP, "Proportion allocated to treatment when Allocation = manual");
        row = add(p, c, row, "\u03c4\u00b2 estimator", method, "Between-study variance estimator");
        row = add(p, c, row, "RE inference", reInference, "standard Wald z, or Hartung-Knapp-Sidik-Jonkman");
        row = add(p, c, row, "Order studies by", orderBy, "Sort ascending by this column (TSA is order-dependent)");
        row = add(p, c, row, "Boundary route", route, "RTSA design route (DARIS = t 1) or analysis route (retrospective endpoint)");
        row = add(p, c, row, "Projection statistic", projStat, "Typical study used for the 'additional studies' projection");
        row = add(p, c, row, "Information per event", ipe, "Historical information-per-event rate");
        fallback.setToolTipText("legacy_fallback: when the analysis route cannot be computed, use the design-route result instead of stopping with an error");
        c.gridx = 0; c.gridy = row; c.gridwidth = 2; c.weightx = 1;
        p.add(fallback, c);
        row++;
        c.gridx = 0; c.gridy = row; c.gridwidth = 2; c.weighty = 1;
        p.add(Box.createVerticalGlue(), c);
        return p;
    }

    private int add(JPanel p, GridBagConstraints c, int row, String label, JComponent comp, String tip) {
        c.gridx = 0; c.gridy = row; c.gridwidth = 1; c.weightx = 0;
        JLabel l = new JLabel(label);
        l.setToolTipText(tip);
        p.add(l, c);
        c.gridx = 1; c.weightx = 1;
        comp.setToolTipText(tip);
        p.add(comp, c);
        return row + 1;
    }

    /** Content of the "Chart options" dialog. */
    JPanel chartOptionsPanel() {
        JPanel p = new JPanel(new GridLayout(0, 2, 6, 6));
        p.add(new JLabel("DARIS label placement")); p.add(labelPlacement);
        p.add(legend); p.add(caption);
        p.add(showDaris); p.add(showHist);
        p.add(new JLabel("x-axis margin multiplier")); p.add(xmaxMult);
        p.add(new JLabel("Label font size")); p.add(labelSize);
        p.add(new JLabel("Caption font size")); p.add(captionSize);
        showHist.setToolTipText("Shown only when the route's target has not been reached");
        xmaxMult.setToolTipText("Free space to the right of the largest element; 1.15 = 15 % (xmax_mult in plot.tsa_hr)");
        labelSize.setToolTipText("Font size of the DARIS / events labels, in ggplot units (default 3.2)");
        captionSize.setToolTipText("Font size of the methods caption, in points (default 8)");
        return p;
    }

    private void chartOptionsDialog() {
        // remember the current values so that Cancel restores them
        int pl = labelPlacement.getSelectedIndex();
        boolean lg = legend.isSelected(), cp = caption.isSelected(), sd = showDaris.isSelected(), sh = showHist.isSelected();
        String xm = xmaxMult.getText(), ls = labelSize.getText(), cs = captionSize.getText();
        int ok = JOptionPane.showConfirmDialog(this, chartOptionsPanel(), "Chart options",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok == JOptionPane.OK_OPTION) {
            redrawChart();                       // options() validates the three numeric fields
        } else {
            labelPlacement.setSelectedIndex(pl);
            legend.setSelected(lg); caption.setSelected(cp); showDaris.setSelected(sd); showHist.setSelected(sh);
            xmaxMult.setText(xm); labelSize.setText(ls); captionSize.setText(cs);
        }
    }

    // ------------------------------------------------------------------ data
    private void openDialog() {
        JFileChooser fc = new JFileChooser(dataFile == null ? new File(".") : dataFile.getParentFile());
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Study tables (xlsx, csv, tsv, txt)",
                "xlsx", "xlsm", "csv", "tsv", "txt"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) loadFile(fc.getSelectedFile(), null);
    }

    private void loadExample(String name) {
        try (java.io.InputStream in = AppPanel.class.getResourceAsStream("/examples/" + name)) {
            if (in == null) throw new java.io.IOException("Bundled example not found: " + name);
            File tmp = File.createTempFile("tsahr_example_", ".xlsx");
            tmp.deleteOnExit();
            Files.copy(in, tmp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            loadFile(tmp, name);
        } catch (Exception e) {
            error(e.getMessage());
        }
    }

    /** @param display name shown instead of the file name (used for the bundled examples), or null */
    void loadFile(File f, String display) {
        String shown = display == null ? f.getName() : display;
        try {
            DataTable read = DataReader.read(f);
            loading = true;
            table = read;
            dataFile = f;
            dataModel.set(table);
            fileLabel.setText(shown + " (" + table.nrow() + " rows)");
            fileLabel.setToolTipText(display == null ? f.getAbsolutePath() : shown);
            orderBy.removeAllItems();
            orderBy.addItem("(row order)");
            for (String c : table.columns) orderBy.addItem(c.trim().replace(' ', '_'));
            loading = false;
            refreshPreview();
            status.setText("Loaded " + shown);
        } catch (Exception e) {
            loading = false;
            error("Could not load " + shown + ":\n" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ run
    private TsaHrSettings readSettings() {
        final TsaHrSettings s = new TsaHrSettings();
        try {
            s.targetHR = observedTarget.isSelected() ? Double.NaN : Double.parseDouble(targetHR.getText().trim().replace(',', '.'));
            s.alphaTwoSided = Double.parseDouble(alpha.getText().trim().replace(',', '.'));
            s.power = Double.parseDouble(power.getText().trim().replace(',', '.'));
            s.allocationP = Double.parseDouble(allocationP.getText().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            error("A numeric field could not be read: " + e.getMessage());
            return null;
        }
        s.allocationSource = (String) allocation.getSelectedItem();
        s.method = (String) method.getSelectedItem();
        s.reInference = (String) reInference.getSelectedItem();
        String ob = (String) orderBy.getSelectedItem();
        s.orderBy = (ob == null || ob.startsWith("(")) ? null : ob;
        s.boundaryRoute = (String) route.getSelectedItem();
        s.projectionStat = (String) projStat.getSelectedItem();
        s.infoPerEventBasis = (String) ipe.getSelectedItem();
        s.legacyFallback = fallback.isSelected();
        s.verbose = true;
        return s;
    }

    private void runAnalysis(ActionEvent ev) {
        if (table == null) {
            JOptionPane.showMessageDialog(this, "Load a data file first (File > Open, or the example data).", "No data", JOptionPane.WARNING_MESSAGE);
            return;
        }
        final TsaHrSettings s = readSettings();
        if (s == null) return;
        run.setEnabled(false);
        status.setText("Computing boundaries\u2026");
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        final DataTable snapshot = table;
        new SwingWorker<TsaHrResult, Void>() {
            @Override protected TsaHrResult doInBackground() { return TsaHr.run(snapshot, s); }
            @Override protected void done() {
                run.setEnabled(true);
                setCursor(Cursor.getDefaultCursor());
                try {
                    finish(get());
                } catch (Exception e) {
                    Throwable t = e.getCause() != null ? e.getCause() : e;
                    status.setText("Analysis failed.");
                    error(t.getMessage());
                }
            }
        }.execute();
    }

    /** Synchronous run (used by tests and scripts). */
    void runBlocking() {
        TsaHrSettings s = readSettings();
        if (s != null) finish(TsaHr.run(table, s));
    }

    private void finish(TsaHrResult r) {
        result = r;
        show(r);
        status.setText("Done \u2013 " + r.study.length + " studies, route: " + r.routeUsed
                + (r.fallbackUsed ? " (fell back from analysis route)" : "") + ".");
    }

    private void show(TsaHrResult r) {
        report.setText(r.log + "\n" + r.printText());
        report.setCaretPosition(0);
        summary.setText(r.printText() + "\n" + r.summaryText());
        summary.setCaretPosition(0);
        if (r.warnings.isEmpty()) warnings.setText("No warnings.");
        else {
            StringBuilder sb = new StringBuilder();
            int i = 1;
            for (String w : r.warnings) sb.append(i++).append(". ").append(w).append("\n\n");
            warnings.setText(sb.toString());
        }
        warnings.setCaretPosition(0);
        cumTable.setModel(new CumModel(r));
        redrawChart();
        tabs.setTitleAt(4, r.warnings.isEmpty() ? "Warnings" : "Warnings (" + r.warnings.size() + ")");
        tabs.setSelectedIndex(0);
    }

    // ------------------------------------------------------------------ chart options
    /** Reads a positive number from a field; an unreadable or non-positive entry is reset to the default. */
    private double positive(JTextField f, String def, String what) {
        try {
            double v = Double.parseDouble(f.getText().trim().replace(',', '.'));
            if (Double.isFinite(v) && v > 0) return v;
        } catch (NumberFormatException ignored) { }
        f.setText(def);
        status.setText(what + " must be a positive number; reset to " + def + ".");
        return Double.parseDouble(def);
    }

    ChartRenderer.Options options() {
        ChartRenderer.Options o = new ChartRenderer.Options();
        o.legend = legend.isSelected();
        o.caption = caption.isSelected();
        o.showTheoreticalDaris = showDaris.isSelected();
        o.showHistoricalDaris = showHist.isSelected();
        int ix = Math.max(0, labelPlacement.getSelectedIndex());
        o.labelPlacement = PLACEMENT_VALUES[ix];
        o.xmaxMult = positive(xmaxMult, DEF_XMAX, "The x-axis margin multiplier");
        o.labelFontSize = positive(labelSize, DEF_LABEL, "The label font size");
        o.captionFontSize = positive(captionSize, DEF_CAPTION, "The caption font size");
        return o;
    }

    private void redrawChart() {
        chart.set(result, options());
    }

    // ------------------------------------------------------------------ export
    /** "Save chart": asks for the resolution (dpi) of the 11 x 7.5 inch chart, then for the file, then writes the PNG. */
    private void saveChart() {
        if (result == null) { needResult(); return; }
        Integer[] dpis = new Integer[ChartExport.DPIS.length];
        for (int i = 0; i < dpis.length; i++) dpis[i] = ChartExport.DPIS[i];
        Object pick = JOptionPane.showInputDialog(this, ChartExport.dialogMessage(), "Save chart",
                JOptionPane.QUESTION_MESSAGE, null, dpis, Integer.valueOf(300));
        if (pick == null) return;                       // cancelled
        final int dpi = (Integer) pick;

        JFileChooser fc = new JFileChooser(dataFile == null ? null : dataFile.getParentFile());
        fc.setSelectedFile(new File("tsa_chart_" + dpi + "dpi.png"));
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("PNG image", "png"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File chosen = fc.getSelectedFile();
        final File out = chosen.getName().toLowerCase().endsWith(".png") ? chosen : new File(chosen.getPath() + ".png");
        if (out.exists() && JOptionPane.showConfirmDialog(this, out.getName() + " already exists. Replace it?",
                "Save chart", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;

        final ChartRenderer.Options opts = options();
        final TsaHrResult res = result;
        saveChartBtn.setEnabled(false);
        status.setText("Saving chart at " + dpi + " dpi (" + ChartExport.sizeText(dpi) + ")\u2026");
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                ChartExport.savePng(res, opts, out, dpi);
                return null;
            }
            @Override protected void done() {
                saveChartBtn.setEnabled(true);
                try {
                    get();
                    status.setText("Chart saved to " + out + " (" + dpi + " dpi, " + ChartExport.sizeText(dpi) + ").");
                } catch (Exception e) {
                    Throwable t = e.getCause() != null ? e.getCause() : e;
                    status.setText("Saving the chart failed.");
                    error(t instanceof OutOfMemoryError ? "Not enough memory for this resolution; choose a lower dpi."
                            : String.valueOf(t.getMessage()));
                }
            }
        }.execute();
    }

    private void needResult() {
        JOptionPane.showMessageDialog(this, "Run the analysis first.", "Nothing to save", JOptionPane.INFORMATION_MESSAGE);
    }

    private void saveText(String text, String suggested) {
        if (text == null) { needResult(); return; }
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File(suggested));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Files.write(fc.getSelectedFile().toPath(), text.getBytes(StandardCharsets.UTF_8));
            status.setText("Saved " + fc.getSelectedFile());
        } catch (Exception e) { error(e.getMessage()); }
    }

    private void error(String msg) {
        JOptionPane.showMessageDialog(this, msg, "tsahr-java", JOptionPane.ERROR_MESSAGE);
    }

    // ------------------------------------------------------------------ models
    private static final class DataModel extends AbstractTableModel {
        private DataTable t = new DataTable();
        void set(DataTable nt) { t = nt; fireTableStructureChanged(); }
        @Override public int getRowCount() { return t.nrow(); }
        @Override public int getColumnCount() { return t.columns.size(); }
        @Override public String getColumnName(int c) { return t.columns.get(c); }
        @Override public Object getValueAt(int r, int c) {
            Object o = t.rows.get(r)[c];
            return o == null ? "" : (o instanceof Double && (Double) o == Math.rint((Double) o) ? (Object) (long) (double) (Double) o : o);
        }
    }

    private static final class CumModel extends AbstractTableModel {
        private final String[] cols = {"Study", "cum_events", "estimate", "se", "Z", "p", "info_fraction", "TSA_boundary", "futility"};
        private final TsaHrResult r;
        CumModel(TsaHrResult r) { this.r = r; }
        @Override public int getRowCount() { return r.study.length; }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Object getValueAt(int i, int c) {
            TsaHrResult.Cumulative C = r.cumulative;
            switch (c) {
                case 0: return C.study[i];
                case 1: return (long) C.cumEvents[i];
                case 2: return f(C.estimate[i], 4);
                case 3: return f(C.se[i], 4);
                case 4: return f(C.z[i], 3);
                case 5: return f(C.pval[i], 4);
                case 6: return f(C.infoFraction[i], 4);
                case 7: return f(C.boundaryUpper[i], 3);
                default: return f(C.futilityUpper[i], 3);
            }
        }
        private static String f(double v, int d) { return Double.isNaN(v) ? "NA" : Fmt.f(v, d); }
    }

    /** Panel drawing the chart at the panel's current size. */
    private static final class ChartPanel extends JPanel {
        private TsaHrResult res;
        private ChartRenderer.Options opt;
        ChartPanel() { setBackground(Color.WHITE); }
        void set(TsaHrResult r, ChartRenderer.Options o) { res = r; opt = o; repaint(); }
        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            if (res == null) {
                g0.setColor(Color.GRAY);
                g0.setFont(getFont().deriveFont(15f));
                String msg = "Load a data file (or the example data) and press \u201cRun analysis\u201d.";
                g0.drawString(msg, (getWidth() - g0.getFontMetrics().stringWidth(msg)) / 2, getHeight() / 2);
                return;
            }
            Graphics2D g = (Graphics2D) g0.create();
            ChartRenderer.paint(g, res, opt, getWidth(), getHeight());
            g.dispose();
        }
    }
}
