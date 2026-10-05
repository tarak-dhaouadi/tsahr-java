package org.tsahr;

import org.tsahr.core.Rmath;
import org.tsahr.core.RtsaBounds;
import org.tsahr.core.RtsaEngine;

/**
 * Compares the Java engine with the numbers printed by the LIVE RTSA 0.2.2 package
 * (frozen in tsahr's inst/extdata/rtsa_0.2.2_reference.R, same tolerances as tsahr's
 * test-rtsa-live-reference.R). No test framework is needed: exit code != 0 on failure.
 */
public class EngineParityTest {
    static int fails = 0, checks = 0;

    static void near(String what, double got, double want, double tol) {
        checks++;
        boolean ok = (Double.isNaN(got) && Double.isNaN(want))
                || Math.abs(got - want) <= tol * Math.max(1.0, Math.abs(want));
        if (!ok) { fails++; System.out.printf("FAIL %-40s got %.16g want %.16g%n", what, got, want); }
    }

    static void near(String what, double[] got, double[] want, double tol) {
        if (got.length != want.length) { fails++; checks++; System.out.println("FAIL " + what + " length " + got.length + " vs " + want.length); return; }
        for (int i = 0; i < got.length; i++) near(what + "[" + (i + 1) + "]", got[i], want[i], tol);
    }

    public static void main(String[] a) {
        double nan = Double.NaN;
        double[] t = {0.25, 0.50, 0.75, 1.00};
        double alpha = 0.05, beta = 0.20;

        // distribution sanity (values from R)
        near("qnorm(0.975)", Rmath.qnorm(0.975), 1.959963984540054, 1e-15);
        near("qnorm(1e-10)", Rmath.qnorm(1e-10), -6.361340902404056, 1e-14);
        near("pnorm(1.96)", Rmath.pnorm(1.96), 0.9750021048517795, 1e-15);
        near("pt(2.5,4,upper)", Rmath.pt(2.5, 4, false), 0.03333, 1e-3);
        near("qt(0.975,9)", Rmath.qt(0.975, 9), 2.262157162798205, 1e-12);
        near("qt(0.975,1)", Rmath.qt(0.975, 1), 12.70620473617471, 1e-12);
        near("pchisq(3.84,1,upper)", Rmath.pchisq(3.841458820694124, 1, false), 0.05, 1e-12);
        near("qchisq(0.5,5)", Rmath.qchisq(0.5, 5), 4.351460191095526, 1e-12);

        // design route
        RtsaBounds.Bounds des = RtsaBounds.designBounds(t, alpha, beta);
        near("design root", des.root, 1.133241903483384, 1e-7);
        near("design alpha", des.alphaUbound, new double[]{4.332633646049564, 2.963130728293607,
                2.359044407368292, 2.014090377368289}, 1e-10);
        near("design beta", des.betaUbound, new double[]{nan, 0.6325313124459123, 1.4041617881408750,
                2.0140903773682739}, 1e-7);
        near("design bs_cum", des.betaSpent, new double[]{0, 0.06992632672050636, 0.13892441763937158,
                0.19999999999999996}, 1e-9);
        near("design bs_incr", des.betaSpentDelta, new double[]{0, 0.06992632672050636, 0.06899809091886522,
                0.06107558236062838}, 1e-9);
        near("design rm_bs", des.rmBs, 1, 0);

        // alpha engine, tight
        RtsaEngine.AlphaOut ad = RtsaEngine.alphaBoundary(t, 2, alpha, nan, 1e-9, 18, new RtsaEngine.Diagnostics());
        near("alpha design (tight)", ad.zb, new double[]{4.332633646049564, 2.963130728293607,
                2.359044407368292, 2.014090377368289}, 1e-12);
        double R = 1.133241903483384;
        RtsaEngine.AlphaOut aa = RtsaEngine.alphaBoundary(t, 2, alpha, R, 1e-9, 18, new RtsaEngine.Diagnostics());
        near("alpha analysis (tight)", aa.zb, new double[]{4.332633646049564, 2.963130674316038,
                2.359044357300598, 2.014090359906189}, 1e-12);

        // analysis route in RTSA's own (unextended) call shape: 5 beta elements
        RtsaBounds.Bounds ana = RtsaBounds.analysisBounds(t, R, alpha, beta);
        near("analysis alpha", ana.alphaUbound, new double[]{4.332633646049564, 2.963130674316038,
                2.359044357300598, 2.014090359906189}, 1e-12);
        near("analysis beta", ana.betaUbound, new double[]{nan, 0.3709071738839083, 1.1440542785920043,
                1.7200465809397056, 2.1228442649443582}, 1e-9);
        near("analysis bs_cum", ana.betaSpent, new double[]{0, 0.05368666786513043, 0.11518429065555469,
                0.17248550115092032, 0.19999999999999996}, 1e-9);

        System.out.printf("%d checks, %d failures%n", checks, fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
