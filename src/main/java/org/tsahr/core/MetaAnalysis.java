package org.tsahr.core;

import java.util.Arrays;
import java.util.function.DoubleUnaryOperator;

/**
 * Intercept-only meta-analysis equivalent to what tsahr obtains from metafor::rma() and
 * metafor::cumul(): equal-effects ("FE") and random-effects fits, the tau^2 estimators accepted
 * by tsa_hr() (DL, HE, HS, HSk, SJ, ML, REML, EB, PM, PMM), the cumulative fits, and the
 * Hartung-Knapp-Sidik-Jonkman (HKSJ) inference of tsahr >= 0.2.8.11.
 *
 * Differences from metafor worth knowing: the iterative estimators (ML, REML, EB, PM, PMM)
 * are solved to a tight tolerance with Brent's method instead of metafor's Fisher-scoring /
 * uniroot loops with threshold 1e-5, so they agree with metafor to about that threshold.
 *
 * GPL (>= 2).
 */
public final class MetaAnalysis {
    private MetaAnalysis() {}

    public static final String[] METHODS = {"DL", "HE", "HS", "HSk", "SJ", "ML", "REML", "EB", "PM", "PMM"};

    /** An intercept-only fit. */
    public static final class Fit {
        public int k;
        public String method;
        public double tau2;
        public double b;          // pooled log-effect
        public double vb;         // variance of b under the model's own test (scaled for knha)
        public double se;
        public double zval;       // z or t statistic
        public double pval;
        public double ciLb, ciUb;
        public double df = Double.POSITIVE_INFINITY;
        public double qe, qep, i2;
        public double scale = 1.0;     // HKSJ variance multiplier actually applied
        public String test = "z";
    }

    public static boolean validMethod(String m) { return Arrays.asList(METHODS).contains(m); }

    // ---------------------------------------------------------------- helpers
    private static double sum(double[] a) { double s = 0; for (double v : a) s += v; return s; }

    private static double[] weights(double[] vi, double tau2) {
        double[] w = new double[vi.length];
        for (int i = 0; i < w.length; i++) w[i] = 1.0 / (vi[i] + tau2);
        return w;
    }

    private static double wmean(double[] y, double[] w) {
        double sw = 0, swy = 0;
        for (int i = 0; i < y.length; i++) { sw += w[i]; swy += w[i] * y[i]; }
        return swy / sw;
    }

    /** weighted residual sum of squares around the weighted mean */
    private static double wrss(double[] y, double[] w) {
        double mu = wmean(y, w), s = 0;
        for (int i = 0; i < y.length; i++) s += w[i] * (y[i] - mu) * (y[i] - mu);
        return s;
    }

    /** Paule-Mandel generalised Q at tau2. */
    private static double qGen(double[] y, double[] vi, double tau2) { return wrss(y, weights(vi, tau2)); }

    /** Variance estimate of the HKSJ scale: q = sum(w (y-mu)^2)/(k-1) at the given tau2. NaN for k<2. */
    public static double hksjQ(double[] yi, double[] sei, double tau2) {
        int k = yi.length;
        if (k < 2 || !Double.isFinite(tau2)) return Double.NaN;
        double[] vi = new double[k];
        for (int i = 0; i < k; i++) vi[i] = sei[i] * sei[i];
        return wrss(yi, weights(vi, tau2)) / (k - 1);
    }

    private static double rootIncreasingBracket(DoubleUnaryOperator f) {
        double hi = 1.0;
        int n = 0;
        while (f.applyAsDouble(hi) > 0 && n++ < 80) hi *= 2;
        if (f.applyAsDouble(hi) > 0)
            throw new IllegalStateException("tau^2 estimation did not converge (no sign change found up to " + hi + ")");
        return Uniroot.root(f, 0.0, hi, 1e-13);
    }

    // ---------------------------------------------------------------- tau^2
    public static double tau2(double[] yi, double[] vi, String method) {
        int k = yi.length;
        if (k < 2) return 0.0;
        double[] w = weights(vi, 0.0);
        double sw = sum(w);
        double qe = wrss(yi, w);
        double t2;
        switch (method) {
            case "DL": {
                double sw2 = 0;
                for (double v : w) sw2 += v * v;
                t2 = (qe - (k - 1)) / (sw - sw2 / sw);
                break;
            }
            case "HE": {
                double mean = 0;
                for (double v : yi) mean += v;
                mean /= k;
                double rss = 0;
                for (double v : yi) rss += (v - mean) * (v - mean);
                double sv = sum(vi);
                double trPV = sv - sv / k;
                t2 = (rss - trPV) / (k - 1);
                break;
            }
            case "HS":
                t2 = (qe - k) / sw;
                break;
            case "HSk":
                t2 = (qe * k / (k - 1.0) - k) / sw;
                break;
            case "SJ": {
                double mean = 0;
                for (double v : yi) mean += v;
                mean /= k;
                double var = 0;
                for (double v : yi) var += (v - mean) * (v - mean);
                var /= (k - 1);
                double tau0 = var * (k - 1) / k;
                double[] w0 = weights(vi, tau0);
                double rss = wrss(yi, w0);
                t2 = tau0 * rss / (k - 1);
                break;
            }
            case "ML": {
                final double[] y = yi;
                DoubleUnaryOperator score = t -> {
                    double[] ww = weights(vi, t);
                    double mu = wmean(y, ww), s = 0, sw1 = 0;
                    for (int i = 0; i < y.length; i++) { s += ww[i] * ww[i] * (y[i] - mu) * (y[i] - mu); sw1 += ww[i]; }
                    return s - sw1;   // score (up to a positive factor): positive below the ML solution
                };
                t2 = score.applyAsDouble(0.0) <= 0 ? 0.0 : rootIncreasingBracket(score);
                break;
            }
            case "REML": {
                final double[] y = yi;
                DoubleUnaryOperator score = t -> {
                    double[] ww = weights(vi, t);
                    double mu = wmean(y, ww), s = 0, sw1 = 0, sw2 = 0;
                    for (int i = 0; i < y.length; i++) {
                        s += ww[i] * ww[i] * (y[i] - mu) * (y[i] - mu);
                        sw1 += ww[i]; sw2 += ww[i] * ww[i];
                    }
                    double trP = sw1 - sw2 / sw1;
                    return s - trP;   // REML score (up to a positive factor)
                };
                t2 = score.applyAsDouble(0.0) <= 0 ? 0.0 : rootIncreasingBracket(score);
                break;
            }
            case "EB":
            case "PM": {
                final double target = k - 1;
                DoubleUnaryOperator f = t -> qGen(yi, vi, t) - target;
                t2 = f.applyAsDouble(0.0) <= 0 ? 0.0 : rootIncreasingBracket(f);
                break;
            }
            case "PMM": {
                final double target = Rmath.qchisq(0.5, k - 1);
                DoubleUnaryOperator f = t -> qGen(yi, vi, t) - target;
                t2 = f.applyAsDouble(0.0) <= 0 ? 0.0 : rootIncreasingBracket(f);
                break;
            }
            default:
                throw new IllegalArgumentException("unsupported tau^2 method: " + method);
        }
        return Math.max(0.0, t2);
    }

    // ---------------------------------------------------------------- fits
    /** Equal-effects ("FE") fit. */
    public static Fit fitFE(double[] yi, double[] sei) {
        return fit(yi, sei, "FE", "z");
    }

    /** Random-effects fit with standard z-based inference. */
    public static Fit fit(double[] yi, double[] sei, String method) {
        return fit(yi, sei, method, "z");
    }

    /**
     * @param test "z" (default), "t" (t reference distribution, k-1 df, unscaled variance) or
     *             "knha" (HKSJ: variance multiplied by q, t reference distribution)
     */
    public static Fit fit(double[] yi, double[] sei, String method, String test) {
        int k = yi.length;
        double[] vi = new double[k];
        for (int i = 0; i < k; i++) vi[i] = sei[i] * sei[i];
        Fit f = new Fit();
        f.k = k;
        f.method = method;
        double[] w0 = weights(vi, 0.0);
        f.qe = wrss(yi, w0);
        f.qep = k > 1 ? Rmath.pchisq(f.qe, k - 1, false) : Double.NaN;
        f.tau2 = "FE".equals(method) ? 0.0 : tau2(yi, vi, method);

        double sw0 = sum(w0), sw02 = 0;
        for (double v : w0) sw02 += v * v;
        if (k > 1) {
            double vt = (k - 1) / (sw0 - sw02 / sw0);
            f.i2 = Math.max(0.0, 100.0 * f.tau2 / (vt + f.tau2));
        } else {
            f.i2 = 0.0;
        }

        double[] w = weights(vi, f.tau2);
        double sw = sum(w);
        f.b = wmean(yi, w);
        double vb = 1.0 / sw;
        f.test = test;
        double crit;
        if ("knha".equals(test) && k > 1) {
            double q = wrss(yi, w) / (k - 1);
            f.scale = q;
            vb = vb * q;
            f.df = k - 1;
        } else if ("t".equals(test) && k > 1) {
            f.df = k - 1;
        }
        f.vb = vb;
        f.se = Math.sqrt(vb);
        f.zval = f.b / f.se;
        if (Double.isInfinite(f.df)) {
            f.pval = 2 * Rmath.pnorm(-Math.abs(f.zval), 0, 1, true);
            crit = Rmath.qnorm(0.975);
        } else {
            f.pval = Math.min(1.0, 2 * Math.exp(Rmath.logPtUpper(f.zval, f.df)));
            crit = Rmath.qt(0.975, f.df);
        }
        f.ciLb = f.b - crit * f.se;
        f.ciUb = f.b + crit * f.se;
        return f;
    }

    // ---------------------------------------------------------------- cumulative
    /** Cumulative (sequential) fit table: element i is the standard fit on studies 1..i+1. */
    public static Fit[] cumulative(double[] yi, double[] sei, String method) {
        int k = yi.length;
        Fit[] out = new Fit[k];
        for (int i = 1; i <= k; i++) {
            out[i - 1] = fit(Arrays.copyOf(yi, i), Arrays.copyOf(sei, i), method, "z");
        }
        return out;
    }

    /** Cumulative HKSJ columns (see tsahr:::.tsahr_cumulative_re_inference). */
    public static final class CumHksj {
        public double[] se, stat, p, lb, ub, scale, df, z;
    }

    public static CumHksj cumulativeHksj(Fit[] cum, double[] yi, double[] sei, boolean adhoc) {
        int k = cum.length;
        CumHksj r = new CumHksj();
        r.se = new double[k]; r.stat = new double[k]; r.p = new double[k]; r.lb = new double[k];
        r.ub = new double[k]; r.scale = new double[k]; r.df = new double[k]; r.z = new double[k];
        for (int i = 0; i < k; i++) {
            r.se[i] = cum[i].se;
            r.stat[i] = cum[i].b / cum[i].se;
            r.p[i] = cum[i].pval;
            r.lb[i] = cum[i].ciLb;
            r.ub[i] = cum[i].ciUb;
            r.scale[i] = 1.0;
            r.df[i] = Double.POSITIVE_INFINITY;
            r.z[i] = r.stat[i];
        }
        for (int i = 2; i <= k; i++) {
            int ix = i - 1;
            double tau2 = cum[ix].tau2;
            double[] y = Arrays.copyOf(yi, i), s = Arrays.copyOf(sei, i);
            double q = hksjQ(y, s, tau2);
            if (!Double.isFinite(q) || q <= 0) continue;
            double scaleI = adhoc ? Math.max(1.0, q) : q;
            double sw = 0;
            for (int j = 0; j < i; j++) sw += 1.0 / (s[j] * s[j] + tau2);
            double est = cum[ix].b;
            double seI = Math.sqrt(scaleI / sw);
            double dfI = i - 1;
            double tI = est / seI;
            double lp1 = Rmath.logPtUpper(tI, dfI);
            double crit = Rmath.qt(0.975, dfI);
            r.scale[ix] = scaleI;
            r.df[ix] = dfI;
            r.se[ix] = seI;
            r.stat[ix] = tI;
            r.p[ix] = Math.min(1.0, 2 * Math.exp(lp1));
            r.lb[ix] = est - crit * seI;
            r.ub[ix] = est + crit * seI;
            r.z[ix] = Math.signum(est) * Rmath.qnormFromLogUpper(lp1);
        }
        r.z[0] = Double.NaN;
        return r;
    }

    // ---------------------------------------------------------------- stats helpers
    public static double median(double[] a) {
        if (a.length == 0) return Double.NaN;
        double[] s = a.clone();
        Arrays.sort(s);
        int n = s.length;
        return n % 2 == 1 ? s[n / 2] : 0.5 * (s[n / 2 - 1] + s[n / 2]);
    }

    public static double mean(double[] a) {
        if (a.length == 0) return Double.NaN;
        double s = 0;
        for (double v : a) s += v;
        return s / a.length;
    }
}
