package org.tsahr.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

/**
 * Port of R/rtsa_engine.R of tsahr 0.2.8.18: reproduces RTSA::boundaries()'s orchestration
 * (which root searches are run, on which information / spending scales) over {@link RtsaEngine}.
 *
 * GPL (>= 2). See RtsaEngine for provenance.
 */
public final class RtsaBounds {
    private RtsaBounds() {}

    /** Result of a design or analysis boundary computation. */
    public static final class Bounds {
        public double[] timing;
        public double[] alphaUbound;
        public double[] za;
        /** futility upper bound; NaN where RTSA suppresses it (sentinel +-20). */
        public double[] betaUbound;
        public double root;
        public int rmBs;
        public double delta;
        public double[] betaSpent, betaSpentDelta;
        public boolean[] sentinel;
        public boolean finalBeyondWall;
        public List<String> warnings = new ArrayList<>();
    }

    public static final class Retrospective {
        public Bounds design, analysis;
        public double designR;
    }

    // ------------------------------------------------------------------ warnings
    static void warnDiagnostics(int gridReversed, int gridCollapses, int slowSearches, String where, List<String> sink) {
        if (gridReversed > 0) {
            sink.add(String.format("*** RTSA-ported integration interval was REVERSED (lower wall above the upper wall) "
                    + "%d time(s) while computing %s. The interval was widened to a minimal non-zero window so the "
                    + "calculation could continue, but a reversed configuration is NOT a valid RTSA computation: the "
                    + "boundaries from the look where this happened onward must not be trusted or reported as "
                    + "RTSA-equivalent. Check the design (information fractions, alpha, power). ***", gridReversed, where));
        }
        if (gridCollapses > 0) {
            sink.add(String.format("RTSA-ported integration grid collapsed to a degenerate (fewer-than-two-node or "
                    + "zero-width) interval %d time(s) while computing %s; the calculation continued. RTSA's own R code "
                    + "would have errored at the first one instead -- treat this boundary sequence with extra caution "
                    + "at the look(s) where it happened.", gridCollapses, where));
        }
        if (slowSearches > 0) {
            sink.add(String.format("RTSA-ported boundary search needed the slow (iteration-capped) path %d time(s) "
                    + "while computing %s; the result still converged within a loose tolerance and was accepted.",
                    slowSearches, where));
        }
    }

    static RtsaEngine.AlphaOut alphaCpp(double[] infFrac, int side, double alpha, double designR,
                                        String where, List<String> sink) {
        if (infFrac.length == 0) throw new IllegalArgumentException("information fractions must be finite and strictly positive");
        for (double v : infFrac)
            if (!Double.isFinite(v) || v <= 0)
                throw new IllegalArgumentException("information fractions must be finite and strictly positive");
        RtsaEngine.Diagnostics d = new RtsaEngine.Diagnostics();
        RtsaEngine.AlphaOut res = RtsaEngine.alphaBoundary(infFrac, side, alpha, designR, 1e-9, 18, d);
        warnDiagnostics(res.gridReversed, res.gridCollapses, res.slowSearches, where, sink);
        return res;
    }

    static RtsaEngine.BetaOut betaCpp(double[] infFrac, double[] alphaBound, double beta, double delta, int rmBs,
                                      double designR, double warpRoot, String where, boolean warn, List<String> sink) {
        if (infFrac.length == 0) throw new IllegalArgumentException("information fractions must be finite");
        for (double v : infFrac)
            if (!Double.isFinite(v)) throw new IllegalArgumentException("information fractions must be finite");
        RtsaEngine.Diagnostics d = new RtsaEngine.Diagnostics();
        RtsaEngine.BetaOut res = RtsaEngine.betaBoundary(infFrac, alphaBound, beta, 1, delta, rmBs, designR,
                warpRoot, -20.0, 1e-15, 18, d);
        if (warn) warnDiagnostics(res.gridReversed, res.gridCollapses, res.slowSearches, where, sink);
        return res;
    }

    /** RTSA's "slide a narrow bracket upward until uniroot() succeeds" loop. */
    static double slideRoot(DoubleUnaryOperator f, double start, double step) {
        final int maxIter = 50;
        double upper = start;
        String lastErr = null;
        for (int n = 0; n < maxIter; n++) {
            try {
                return Uniroot.root(f, upper - step, upper, 1e-9);
            } catch (Uniroot.NoSignChange e) {
                // keep sliding
            } catch (RuntimeException e) {
                lastErr = e.getMessage();
            }
            upper += step;
        }
        throw new IllegalStateException("RTSA-style information-scale root search did not converge (no sign change found in ["
                + (start - step) + ", " + (start + step * (maxIter - 1)) + "])"
                + (lastErr != null ? "; last engine error: " + lastErr : "") + ".");
    }

    // ------------------------------------------------------------------ design
    public static Bounds designBounds(double[] tIn, double alpha, double beta) {
        double[] t = tIn.clone();
        if (t.length == 0) throw new IllegalArgumentException("information fractions must be finite and strictly positive");
        for (double v : t)
            if (!Double.isFinite(v) || v <= 0)
                throw new IllegalArgumentException("information fractions must be finite and strictly positive");
        for (int i = 1; i < t.length; i++)
            if (!(t[i] > t[i - 1])) throw new IllegalArgumentException("information fractions must be strictly increasing");
        if (max(t) < 1) { t = Arrays.copyOf(t, t.length + 1); t[t.length - 1] = 1; }
        final double[] tt = t;
        final int nt = tt.length;
        Bounds out = new Bounds();
        checkLookSpacing(tt, "the design-route look schedule", out.warnings);

        RtsaEngine.AlphaOut ab = alphaCpp(tt, 2, alpha, Double.NaN,
                "the design-route alpha (efficacy) boundary", out.warnings);
        final double[] ub = ab.zb;
        final double delta = Math.abs(Rmath.qnorm(alpha / 2) + Rmath.qnorm(beta));

        if (nt == 1) {
            out.timing = tt; out.alphaUbound = ub; out.za = ub.clone(); out.betaUbound = ub.clone();
            out.root = 1; out.rmBs = 0; out.delta = delta;
            out.betaSpent = new double[]{beta}; out.betaSpentDelta = new double[]{beta};
            out.sentinel = new boolean[]{false};
            return out;
        }

        java.util.function.BiFunction<Double, Integer, Double> gap = (x, rm) -> {
            RtsaEngine.BetaOut lb = betaCpp(tt, ub, beta, delta, rm, Double.NaN, x,
                    "the design-route beta (futility) boundary (root search)", false, new ArrayList<>());
            return ub[nt - 1] - lb.za[nt - 1];
        };
        double root1 = slideRoot(x -> gap.apply(x, 0), 0.95, 0.02);
        RtsaEngine.BetaOut lb1 = betaCpp(tt, ub, beta, delta, 0, Double.NaN, root1,
                "the design-route beta (futility) boundary (pass 1)", true, out.warnings);
        checkConvergedPass(lb1, ub[nt - 1], "the design-route calibration (pass 1)");
        int rmBs = 0;
        for (double v : lb1.za) if (v < 0) rmBs++;
        if (rmBs >= nt)
            throw new IllegalStateException("every look has a negative futility bound on the first pass; "
                    + "the non-binding design cannot be computed.");
        final int rmFinal = rmBs;
        double root2 = slideRoot(x -> gap.apply(x, rmFinal), 0.95, 0.05);
        RtsaEngine.BetaOut lb = betaCpp(tt, ub, beta, delta, rmFinal, Double.NaN, root2,
                "the design-route beta (futility) boundary (pass 2)", true, out.warnings);
        checkConvergedPass(lb, ub[nt - 1], "the design-route calibration (pass 2)");

        double[] za = lb.za;
        boolean[] sentinel = new boolean[za.length];
        double[] bu = za.clone();
        for (int i = 0; i < za.length; i++) {
            sentinel[i] = Math.abs(za[i]) == 20;
            if (sentinel[i]) bu[i] = Double.NaN;
        }
        out.timing = tt; out.alphaUbound = ub; out.za = za; out.betaUbound = bu;
        out.root = root2; out.rmBs = rmFinal; out.delta = delta;
        out.betaSpent = lb.asCum; out.betaSpentDelta = lb.asIncr; out.sentinel = sentinel;
        return out;
    }

    // ------------------------------------------------------------------ analysis
    public static Bounds analysisBounds(double[] tExt, double designR, double alpha, double beta) {
        if (tExt.length == 0) throw new IllegalArgumentException("information fractions must be finite and strictly positive");
        for (double v : tExt)
            if (!Double.isFinite(v) || v <= 0)
                throw new IllegalArgumentException("information fractions must be finite and strictly positive");
        if (!Double.isFinite(designR) || designR <= 0)
            throw new IllegalArgumentException("design_R must be a finite, strictly positive scalar");
        if (tExt.length < 2 && !(max(tExt) < designR))
            throw new IllegalArgumentException("the analysis route needs at least one observed look plus design_R");
        Bounds out = new Bounds();
        checkLookSpacing(tExt, "the analysis-route look schedule", out.warnings);
        RtsaEngine.AlphaOut ab = alphaCpp(tExt, 2, alpha, designR,
                "the analysis-route alpha (efficacy) boundary", out.warnings);
        double[] ub = ab.zb;
        double delta = Math.abs(Rmath.qnorm(alpha / 2) + Rmath.qnorm(beta));

        int rmBs = 0;
        RtsaEngine.BetaOut lb = null;
        int unr = 0;
        for (int pass = 1; pass <= 3; pass++) {
            lb = betaCpp(tExt, ub, beta, delta, rmBs, designR, Double.NaN,
                    "the analysis-route beta (futility) boundary (pass " + pass + ")", true, out.warnings);
            unr = lb.unreachableLook;
            int nLooks = lb.za.length;
            if (unr > 0 && unr < nLooks)
                throw new IllegalStateException(String.format("the futility bound reaches the efficacy wall at look "
                        + "%d of %d in the analysis-route beta calculation (pass %d); the analysis route cannot be "
                        + "computed for this schedule.", unr, nLooks, pass));
            rmBs = 0;
            for (double v : lb.za) if (v < 0) rmBs++;
        }
        boolean finalBeyondWall = unr > 0;
        double[] za = lb.za;
        boolean[] sentinel = new boolean[za.length];
        double[] bu = za.clone();
        for (int i = 0; i < za.length; i++) {
            sentinel[i] = Math.abs(za[i]) == 20;
            if (sentinel[i]) bu[i] = Double.NaN;
        }
        int nl = ub.length;
        if (!Double.isNaN(bu[nl - 1]) && bu[nl - 1] > ub[nl - 1]) bu[nl - 1] = ub[nl - 1];
        out.timing = tExt; out.alphaUbound = ub; out.za = za; out.betaUbound = bu;
        out.root = designR; out.rmBs = rmBs; out.delta = delta;
        out.betaSpent = lb.asCum; out.betaSpentDelta = lb.asIncr; out.sentinel = sentinel;
        out.finalBeyondWall = finalBeyondWall;
        return out;
    }

    // ------------------------------------------------------------------ retrospective
    public static Retrospective retrospective(double[] tObs, double alpha, double beta) {
        List<Double> td = new ArrayList<>();
        for (double v : tObs) if (v <= 1) td.add(v);
        if (td.isEmpty()) throw new IllegalArgumentException("the required information size is already reached at the first look");
        Bounds des = designBounds(toArray(td), alpha, beta);
        double R = des.root;
        double[] tExt;
        double mx = max(tObs);
        if (mx < R) {
            tExt = Arrays.copyOf(tObs, tObs.length + 1);
            tExt[tExt.length - 1] = R;
        } else if (mx > R) {
            List<Double> l = new ArrayList<>();
            for (double v : tObs) if (v < R) l.add(v);
            l.add(R);
            tExt = toArray(l);
        } else {
            tExt = tObs.clone();
        }
        Bounds ana = analysisBounds(tExt, R, alpha, beta);
        Retrospective r = new Retrospective();
        r.design = des; r.analysis = ana; r.designR = R;
        return r;
    }

    // ------------------------------------------------------------------ checks
    static void checkConvergedPass(RtsaEngine.BetaOut lb, double finalWall, String where) {
        if (lb.unreachableLook > 0)
            throw new IllegalStateException(where + " reached an unreachable futility target at look " + lb.unreachableLook
                    + ": the information-scale root search converged onto the infeasible region, not onto a root.");
        double resGap = finalWall - lb.za[lb.za.length - 1];
        if (!Double.isFinite(resGap) || Math.abs(resGap) > 1e-6)
            throw new IllegalStateException(String.format("%s: the final futility bound does not meet the final efficacy "
                    + "bound at the accepted root (residual gap %.3g).", where, resGap));
    }

    static void checkLookSpacing(double[] t, String where, List<String> sink) {
        if (t.length < 2) return;
        final double threshold = 0.0025;
        int small = 0;
        double minDt = Double.POSITIVE_INFINITY;
        int minIdx = 0;
        for (int i = 1; i < t.length; i++) {
            double dt = t[i] - t[i - 1];
            if (dt < threshold) small++;
            if (dt < minDt) { minDt = dt; minIdx = i + 1; }
        }
        if (small > 0) {
            sink.add(String.format("%s has %d look(s) that add less than %.2f%% of the required information (smallest "
                    + "increment %.4g of the required information, at look %d of %d). Below this spacing the recursive "
                    + "integration at the default grid resolution can become numerically unreliable, so the boundaries "
                    + "should not be trusted. (The %.2f%% level is an empirical warning threshold for this "
                    + "implementation's default integration grid, not an RTSA rule.) Consider merging "
                    + "near-simultaneous studies.", where, small, threshold * 100, minDt, minIdx, t.length,
                    threshold * 100));
        }
    }

    /**
     * Estimated cumulative events at which the observed information first reaches {@code target}
     * (an information fraction), by linear interpolation; NaN if never reached.
     */
    public static double eventsAtFraction(double[] infoFraction, double[] cumEvents, double target) {
        int idx = -1;
        for (int i = 0; i < infoFraction.length; i++) if (infoFraction[i] >= target) { idx = i; break; }
        if (idx < 0) return Double.NaN;
        if (idx == 0) return cumEvents[0];
        double f0 = infoFraction[idx - 1], f1 = infoFraction[idx];
        double e0 = cumEvents[idx - 1], e1 = cumEvents[idx];
        double w = f1 > f0 ? (target - f0) / (f1 - f0) : 0;
        return e0 + w * (e1 - e0);
    }

    private static double max(double[] a) { return Arrays.stream(a).max().getAsDouble(); }

    private static double[] toArray(List<Double> l) {
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }
}
