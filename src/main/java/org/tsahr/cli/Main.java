package org.tsahr.cli;

import org.tsahr.core.DataTable;
import org.tsahr.core.TsaHr;
import org.tsahr.core.TsaHrResult;
import org.tsahr.core.TsaHrSettings;
import org.tsahr.gui.ChartExport;
import org.tsahr.gui.ChartRenderer;
import org.tsahr.gui.MainWindow;
import org.tsahr.io.DataReader;

import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Entry point. No arguments (or --gui): opens the desktop application. Otherwise runs one analysis from the
 * command line, with the same arguments as R's tsa_hr().
 */
public final class Main {
    private Main() {}

    public static final String VERSION = "0.1.1";

    static final String USAGE = String.join("\n",
            "tsahr-java " + VERSION + " -- Trial Sequential Analysis for hazard ratios",
            "Java edition of the R package tsahr (parity with tsahr " + TsaHr.TSAHR_PARITY_VERSION + ").",
            "",
            "Usage:",
            "  java -jar tsahr-java.jar                      open the desktop application",
            "  java -jar tsahr-java.jar --data FILE [options] run one analysis (FILE: .xlsx, .csv, .tsv)",
            "",
            "Options (names follow tsa_hr()):",
            "  --target-hr X            anticipated HR for the required information size (default: observed pooled HR; exploratory)",
            "  --alpha X                two-sided alpha (default 0.05)",
            "  --power X                power (default 0.80)",
            "  --allocation data|manual (default data)      --allocation-p X (used with manual)",
            "  --method M               DL (default), HE, HS, HSk, SJ, ML, REML, EB, PM, PMM (CO/VC = HE)",
            "  --re-inference I         standard (default), hksj (= knha), hksj_adhoc (= knha_adhoc)",
            "  --order-by COLUMN        sort studies ascending by this column",
            "  --route design|analysis  boundary route (default design)",
            "  --no-fallback            fail instead of falling back from the analysis to the design route",
            "  --projection-stat median|mean              --info-per-event per_study|pooled",
            "  --quiet                  print only the summary table",
            "  --plot FILE.png          save the TSA chart       --plot-size WxH (default 1300x800)",
            "  --dpi N                  save the chart as an 11 x 7.5 inch page at N dpi (150, 300, 600, 1200; overrides --plot-size)",
            "  --no-legend --no-caption --no-theoretical-daris --no-historical-daris --xmax-mult X",
            "  --label-placement auto|upper|lower   DARIS labels (auto: low when the Z-curve is positive)",
            "  --label-font-size X      DARIS / events label size, ggplot units (default 3.2)",
            "  --caption-font-size X    methods caption size in points (default 8)",
            "  --cumulative-tsv FILE    save the cumulative table    --boundaries-tsv FILE   save the boundary timeline",
            "  --gui                    open the desktop application",
            "  -h, --help               this text");

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || (args.length == 1 && args[0].equals("--gui"))) {
            MainWindow.launch(null);
            return;
        }
        PrintStream out = new PrintStream(System.out, true, "UTF-8");
        PrintStream err = new PrintStream(System.err, true, "UTF-8");
        TsaHrSettings s = new TsaHrSettings();
        ChartRenderer.Options co = new ChartRenderer.Options();
        String data = null, plot = null, cumTsv = null, bndTsv = null;
        int pw = 1300, ph = 800, dpi = 0;
        boolean quiet = false, gui = false;
        try {
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "-h": case "--help": out.println(USAGE); return;
                    case "--gui": gui = true; break;
                    case "--data": data = need(args, ++i, a); break;
                    case "--target-hr": s.targetHR = num(need(args, ++i, a), a); break;
                    case "--alpha": s.alphaTwoSided = num(need(args, ++i, a), a); break;
                    case "--power": s.power = num(need(args, ++i, a), a); break;
                    case "--allocation": s.allocationSource = need(args, ++i, a); break;
                    case "--allocation-p": s.allocationP = num(need(args, ++i, a), a); break;
                    case "--method": s.method = need(args, ++i, a); break;
                    case "--re-inference": s.reInference = need(args, ++i, a); break;
                    case "--order-by": s.orderBy = need(args, ++i, a); break;
                    case "--route": s.boundaryRoute = need(args, ++i, a); break;
                    case "--no-fallback": s.legacyFallback = false; break;
                    case "--projection-stat": s.projectionStat = need(args, ++i, a); break;
                    case "--info-per-event": s.infoPerEventBasis = need(args, ++i, a); break;
                    case "--quiet": quiet = true; break;
                    case "--plot": plot = need(args, ++i, a); break;
                    case "--plot-size": {
                        String[] wh = need(args, ++i, a).toLowerCase().split("x");
                        pw = Integer.parseInt(wh[0]); ph = Integer.parseInt(wh[1]);
                        break;
                    }
                    case "--dpi": dpi = (int) num(need(args, ++i, a), a); break;
                    case "--no-legend": co.legend = false; break;
                    case "--no-caption": co.caption = false; break;
                    case "--no-theoretical-daris": co.showTheoreticalDaris = false; break;
                    case "--no-historical-daris": co.showHistoricalDaris = false; break;
                    case "--xmax-mult": co.xmaxMult = num(need(args, ++i, a), a); break;
                    case "--label-placement": co.labelPlacement = need(args, ++i, a); break;
                    case "--label-font-size": co.labelFontSize = num(need(args, ++i, a), a); break;
                    case "--caption-font-size": co.captionFontSize = num(need(args, ++i, a), a); break;
                    case "--cumulative-tsv": cumTsv = need(args, ++i, a); break;
                    case "--boundaries-tsv": bndTsv = need(args, ++i, a); break;
                    default: throw new IllegalArgumentException("Unknown option: " + a + " (see --help)");
                }
            }
            if (gui) { MainWindow.launch(data == null ? null : new File(data)); return; }
            if (data == null) throw new IllegalArgumentException("--data FILE is required (see --help)");
            s.verbose = !quiet;
            DataTable t = DataReader.read(new File(data));
            TsaHrResult r = TsaHr.run(t, s);
            if (!quiet) out.println(r.log);
            for (String w : r.warnings) err.println("Warning: " + w);
            out.println(r.printText());
            out.println(r.summaryText());
            if (plot != null) {
                if (dpi > 0) ChartExport.savePng(r, co, new File(plot), dpi);
                else ChartRenderer.savePng(r, co, new File(plot), pw, ph);
                out.println("Chart written to " + plot + (dpi > 0 ? " (" + dpi + " dpi, " + ChartExport.sizeText(dpi) + ")" : ""));
            }
            if (cumTsv != null) Files.write(new File(cumTsv).toPath(), r.cumulativeTsv().getBytes(StandardCharsets.UTF_8));
            if (bndTsv != null) Files.write(new File(bndTsv).toPath(), r.boundaryTsv().getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException | IllegalStateException e) {
            err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    private static String need(String[] a, int i, String opt) {
        if (i >= a.length) throw new IllegalArgumentException("Option " + opt + " needs a value");
        return a[i];
    }

    private static double num(String v, String opt) {
        try { return Double.parseDouble(v); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Option " + opt + " needs a number, got '" + v + "'"); }
    }
}
