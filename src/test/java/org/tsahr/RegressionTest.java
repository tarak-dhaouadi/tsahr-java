package org.tsahr;

import org.tsahr.core.*;
import org.tsahr.io.DataReader;

import java.io.File;

/**
 * End-to-end checks on the bundled example data. Expected values were obtained from an independent
 * implementation (numpy/scipy) of the same formulas, because R is not available on the build machine; they
 * should be re-confirmed against R's tsa_hr() output (see README, "Verification status").
 */
public class RegressionTest {
    static int fails = 0, checks = 0;

    static void near(String what, double got, double want, double tol) {
        checks++;
        if (!(Math.abs(got - want) <= tol * Math.max(1.0, Math.abs(want)))) {
            fails++;
            System.out.printf("FAIL %-36s got %.12g want %.12g%n", what, got, want);
        }
    }

    static void is(String what, boolean cond) {
        checks++;
        if (!cond) { fails++; System.out.println("FAIL " + what); }
    }

    public static void main(String[] a) throws Exception {
        DataTable t = DataReader.read(new File("src/test/resources/HR_meta.xlsx"));
        is("20 studies read", t.nrow() == 20);
        TsaHrSettings s = new TsaHrSettings();
        s.targetHR = 0.80;
        s.verbose = false;
        TsaHrResult r = TsaHr.run(t, s);
        // values from scipy/numpy (DerSimonian-Laird, Schoenfeld, Wetterslev D2)
        near("pooled HR", Math.exp(r.resRe.b), 0.5087686853436305, 1e-12);
        near("tau2", r.tau2, 0.013552817172141548, 1e-10);
        near("I2", r.I2, 73.32561591217103, 1e-9);
        near("D2", r.D2, 0.7381641076749177, 1e-10);
        near("AF", r.AF, 3.8191860982849906, 1e-10);
        near("info required", r.infoRequired, 157.63004279511372, 1e-12);
        near("DARIS info", r.DARISInfo, 602.0184681151665, 1e-10);
        near("DARIS events", r.DARISEvents, 2408.073872460666, 1e-10);
        near("cum Z look 2", r.cumulative.z[1], -13.50321072483295, 1e-9);
        near("cum Z look 20", r.cumulative.z[19], -22.0668090717596, 1e-9);
        near("info fraction look 3", r.cumulative.infoFraction[2], 0.9286332573010561, 1e-10);
        is("design route used", "design".equals(r.routeUsed));
        is("DARIS reached", r.darisReached);
        is("crossed TSA", r.crossedTsa);
        is("final look = 4", r.finalTsaLook == 4);
        near("final efficacy wall = last alpha bound", r.boundaryTimeline.boundaryUpper[r.boundaryTimeline.boundaryUpper.length - 1],
                r.boundaryTimeline.futilityUpper[r.boundaryTimeline.futilityUpper.length - 1], 1e-7);

        // ML / REML against scipy
        double[] y = new double[20], se = new double[20];
        for (int i = 0; i < 20; i++) { y[i] = r.logHR[i]; se[i] = r.stdError[i]; }
        near("ML tau2", MetaAnalysis.fit(y, se, "ML").tau2, 0.013549209386551849, 1e-9);
        near("REML tau2", MetaAnalysis.fit(y, se, "REML").tau2, 0.014602686384791808, 1e-9);

        // both routes on a 6-study subset, both inference types: must run and keep the bounds ordered
        for (String route : new String[]{"design", "analysis"})
            for (String inf : new String[]{"standard", "hksj", "hksj_adhoc"}) {
                DataTable sub = DataReader.read(new File("src/test/resources/HR_meta_2.xlsx"));
                while (sub.rows.size() > 6) sub.rows.remove(sub.rows.size() - 1);
                TsaHrSettings s2 = new TsaHrSettings();
                s2.targetHR = 0.8; s2.verbose = false; s2.boundaryRoute = route; s2.reInference = inf;
                TsaHrResult r2 = TsaHr.run(sub, s2);
                is(route + "/" + inf + " ran", r2.boundaryTimeline.infoFraction.length >= 2);
                if (!"standard".equals(inf)) is(inf + ": Z undefined at first look", Double.isNaN(r2.cumulative.z[0]));
            }

        // the chart renders headlessly for both signs of the Z-curve
        for (boolean flip : new boolean[]{false, true}) {
            DataTable d = DataReader.read(new File("src/test/resources/HR_meta.xlsx"));
            while (d.rows.size() > 4) d.rows.remove(d.rows.size() - 1);
            if (flip) { int c = d.col("log_HR"); for (Object[] row : d.rows) row[c] = -(Double) row[c]; }
            TsaHrSettings s3 = new TsaHrSettings();
            s3.targetHR = flip ? 1.25 : 0.8; s3.verbose = false;
            TsaHrResult r3 = TsaHr.run(d, s3);
            is("chart renders (flip=" + flip + ")", org.tsahr.gui.ChartRenderer.render(r3,
                    new org.tsahr.gui.ChartRenderer.Options(), 900, 600).getWidth() == 900);
        }
        // high-resolution export: exact pixel sizes of the 11 x 7.5 inch page and the stored dpi
        {
            DataTable d = DataReader.read(new File("src/test/resources/HR_meta.xlsx"));
            TsaHrSettings s4 = new TsaHrSettings();
            s4.targetHR = 0.8; s4.verbose = false;
            TsaHrResult r4 = TsaHr.run(d, s4);
            for (int dpi : new int[]{150, 300}) {
                File tmp = File.createTempFile("tsa_dpi_", ".png");
                tmp.deleteOnExit();
                org.tsahr.gui.ChartExport.savePng(r4, new org.tsahr.gui.ChartRenderer.Options(), tmp, dpi);
                java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(tmp);
                is(dpi + " dpi width", bi.getWidth() == (dpi == 150 ? 1650 : 3300));
                is(dpi + " dpi height", bi.getHeight() == (dpi == 150 ? 1125 : 2250));
                is(dpi + " dpi not blank", bi.getRGB(bi.getWidth() / 2, bi.getHeight() / 2) != 0);
            }
            is("export sizes", org.tsahr.gui.ChartExport.widthPx(1200) == 13200 && org.tsahr.gui.ChartExport.heightPx(1200) == 9000);
        }
        System.out.printf("%d checks, %d failures%n", checks, fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
