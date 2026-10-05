package org.tsahr.core;

/**
 * Distribution functions needed by the TSA engine.
 *
 * pnorm / qnorm are ports of R's Rmath routines (Cody 1969 / Wichura AS241), so the
 * recursive-integration engine sees the same values as the R package. The t and
 * chi-square distributions are built on the regularised incomplete beta / gamma
 * functions (Lentz continued fractions / series) and are accurate to ~1e-14.
 *
 * Licence: GPL (>= 2), see LICENSE.md.
 */
public final class Rmath {
    private Rmath() {}

    public static final double M_SQRT_32 = 5.656854249492380195206754896838;
    public static final double M_1_SQRT_2PI = 0.398942280401432677939946059934;
    public static final double DBL_EPSILON = 2.220446049250313e-16;

    // ------------------------------------------------------------------ dnorm
    public static double dnorm(double x, double mu, double sigma) {
        double z = (x - mu) / sigma;
        return M_1_SQRT_2PI * Math.exp(-0.5 * z * z) / sigma;
    }

    // ------------------------------------------------------------------ pnorm (Cody)
    private static final double[] A = {2.2352520354606839287, 161.02823106855587881,
            1067.6894854603709582, 18154.981253343561249, 0.065682337918207449113};
    private static final double[] B = {47.20258190468824187, 976.09855173777669322,
            10260.932208618978205, 45507.789335026729956};
    private static final double[] C = {0.39894151208813466764, 8.8831497943883759412,
            93.506656132177855979, 597.27027639480026226, 2494.5375852903726711,
            6848.1904505362823326, 11602.651437647350124, 9842.7148383839780218,
            1.0765576773720192317e-8};
    private static final double[] D = {22.266688044328115691, 235.38790178262499861,
            1519.377599407554805, 6485.558298266760755, 18615.571640885098091,
            34900.952721145977266, 38912.003286093271411, 19685.429676859990727};
    private static final double[] P = {0.21589853405795699, 0.1274011611602473639,
            0.022235277870649807, 0.001421619193227893466, 2.9112874951168792e-5,
            0.02307344176494017303};
    private static final double[] Q = {1.28426009614491121, 0.468238212480865118,
            0.0659881378689285515, 0.00378239633202758244, 7.29751555083966205e-5};

    /** returns {lower, upper} tails of the standard normal at x. */
    private static double[] pnormBoth(double x) {
        double xden, xnum, temp, del, xsq, y;
        double cum = 0, ccum = 0;
        y = Math.abs(x);
        final double eps = DBL_EPSILON * 0.5;
        if (y <= 0.67448975) {
            if (y > eps) {
                xsq = x * x;
                xnum = A[4] * xsq;
                xden = xsq;
                for (int i = 0; i < 3; ++i) {
                    xnum = (xnum + A[i]) * xsq;
                    xden = (xden + B[i]) * xsq;
                }
            } else {
                xnum = xden = 0.0;
            }
            temp = x * (xnum + A[3]) / (xden + B[3]);
            cum = 0.5 + temp;
            ccum = 0.5 - temp;
        } else if (y <= M_SQRT_32) {
            xnum = C[8] * y;
            xden = y;
            for (int i = 0; i < 7; ++i) {
                xnum = (xnum + C[i]) * y;
                xden = (xden + D[i]) * y;
            }
            temp = (xnum + C[7]) / (xden + D[7]);
            xsq = Math.floor(y * 16) / 16;
            del = (y - xsq) * (y + xsq);
            cum = Math.exp(-xsq * xsq * 0.5) * Math.exp(-del * 0.5) * temp;
            ccum = 1.0 - cum;
            if (x > 0.) { temp = cum; cum = ccum; ccum = temp; }
        } else if (y < 50) {
            xsq = 1.0 / (x * x);
            xnum = P[5] * xsq;
            xden = xsq;
            for (int i = 0; i < 4; ++i) {
                xnum = (xnum + P[i]) * xsq;
                xden = (xden + Q[i]) * xsq;
            }
            temp = xsq * (xnum + P[4]) / (xden + Q[4]);
            temp = (M_1_SQRT_2PI - temp) / y;
            xsq = Math.floor(x * 16) / 16;
            del = (x - xsq) * (x + xsq);
            cum = Math.exp(-xsq * xsq * 0.5) * Math.exp(-del * 0.5) * temp;
            ccum = 1.0 - cum;
            if (x > 0.) { temp = cum; cum = ccum; ccum = temp; }
        } else {
            if (x > 0) { cum = 1.0; ccum = 0.0; } else { cum = 0.0; ccum = 1.0; }
        }
        return new double[]{cum, ccum};
    }

    public static double pnorm(double x, double mu, double sigma, boolean lower) {
        if (Double.isNaN(x) || Double.isNaN(mu) || Double.isNaN(sigma)) return Double.NaN;
        double p = (x - mu) / sigma;
        if (Double.isInfinite(p)) return (x < mu) == lower ? 0.0 : 1.0;
        double[] r = pnormBoth(p);
        return lower ? r[0] : r[1];
    }

    public static double pnorm(double x) { return pnorm(x, 0, 1, true); }

    /** log of the upper-tail probability P(Z > x), accurate far into the tail. */
    public static double logUpperNorm(double x) {
        if (x < 5) return Math.log(pnorm(x, 0, 1, false));
        double x2 = x * x;
        double s = 1 - 1 / x2 + 3 / (x2 * x2) - 15 / (x2 * x2 * x2) + 105 / (x2 * x2 * x2 * x2);
        return -0.5 * x2 - Math.log(x) - 0.5 * Math.log(2 * Math.PI) + Math.log(s);
    }

    // ------------------------------------------------------------------ qnorm (AS241)
    public static double qnorm(double p, double mu, double sigma, boolean lower) {
        if (Double.isNaN(p) || Double.isNaN(mu) || Double.isNaN(sigma)) return Double.NaN;
        if (p < 0 || p > 1) return Double.NaN;
        if (p == 0) return lower ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        if (p == 1) return lower ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        double p_ = lower ? p : (0.5 - p + 0.5);
        double q = p_ - 0.5, r, val;
        if (Math.abs(q) <= 0.425) {
            r = .180625 - q * q;
            val = q * (((((((r * 2509.0809287301226727 + 33430.575583588128105) * r
                    + 67265.770927008700853) * r + 45921.953931549871457) * r
                    + 13731.693765509461125) * r + 1971.5909503065514427) * r
                    + 133.14166789178437745) * r + 3.387132872796366608)
                    / (((((((r * 5226.495278852545925 + 28729.085735721942674) * r
                    + 39307.89580009271061) * r + 21213.794301586595867) * r
                    + 5394.1960214247511077) * r + 687.1870074920579083) * r
                    + 42.313330701600911252) * r + 1.);
        } else {
            double lp;
            if (q < 0) lp = lower ? p : (0.5 - p + 0.5);
            else lp = lower ? (0.5 - p + 0.5) : p;
            r = Math.sqrt(-Math.log(lp));
            if (r <= 5.) {
                r += -1.6;
                val = (((((((r * 7.7454501427834140764e-4 + .0227238449892691845833) * r
                        + .24178072517745061177) * r + 1.27045825245236838258) * r
                        + 3.64784832476320460504) * r + 5.7694972214606914055) * r
                        + 4.6303378461565452959) * r + 1.42343711074968357734)
                        / (((((((r * 1.05075007164441684324e-9 + 5.475938084995344946e-4) * r
                        + .0151986665636164571966) * r + .14810397642748007459) * r
                        + .68976733498510000455) * r + 1.6763848301838038494) * r
                        + 2.05319162663775882187) * r + 1.);
            } else {
                r += -5.;
                val = (((((((r * 2.01033439929228813265e-7 + 2.71155556874348757815e-5) * r
                        + .0012426609473880784386) * r + .026532189526576123093) * r
                        + .29656057182850489123) * r + 1.7848265399172913358) * r
                        + 5.4637849111641143699) * r + 6.6579046435011037772)
                        / (((((((r * 2.04426310338993978564e-15 + 1.4215117583164458887e-7) * r
                        + 1.8463183175100546818e-5) * r + 7.868691311456132591e-4) * r
                        + .0148753612908506148525) * r + .13692988092273580531) * r
                        + .59983220655588793769) * r + 1.);
            }
            if (q < 0.0) val = -val;
        }
        return mu + sigma * val;
    }

    public static double qnorm(double p) { return qnorm(p, 0, 1, true); }

    /** z such that P(Z > z) = p. */
    public static double qnormUpper(double p) { return qnorm(p, 0, 1, false); }

    /** z (>0 side) such that log P(Z > z) = logP, accurate for very small probabilities. */
    public static double qnormFromLogUpper(double logP) {
        if (logP > -30) return qnormUpper(Math.exp(logP));
        double z = Math.sqrt(-2 * logP);
        for (int i = 0; i < 100; i++) {
            double lu = logUpperNorm(z);
            double f = lu - logP;
            double ldens = -0.5 * z * z - 0.5 * Math.log(2 * Math.PI);
            double deriv = -Math.exp(ldens - lu);
            double step = f / deriv;
            z -= step;
            if (Math.abs(step) < 1e-13 * Math.max(1, Math.abs(z))) break;
        }
        return z;
    }

    // ------------------------------------------------------------------ gamma / beta
    private static final double[] LANCZOS = {0.99999999999980993, 676.5203681218851, -1259.1392167224028,
            771.32342877765313, -176.61502916214059, 12.507343278686905,
            -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7};

    public static double lgamma(double x) {
        if (x < 0.5) return Math.log(Math.PI / Math.abs(Math.sin(Math.PI * x))) - lgamma(1 - x);
        x -= 1;
        double a = LANCZOS[0];
        double t = x + 7.5;
        for (int i = 1; i < 9; i++) a += LANCZOS[i] / (x + i);
        return 0.5 * Math.log(2 * Math.PI) + (x + 0.5) * Math.log(t) - t + Math.log(a);
    }

    /** Regularised incomplete beta I_x(a,b). */
    public static double pbetaReg(double x, double a, double b) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        double lbeta = lgamma(a + b) - lgamma(a) - lgamma(b);
        double front = Math.exp(lbeta + a * Math.log(x) + b * Math.log1p(-x));
        if (x < (a + 1) / (a + b + 2)) return front * betacf(x, a, b) / a;
        return 1 - front * betacf(1 - x, b, a) / b;
    }

    private static double betacf(double x, double a, double b) {
        final double TINY = 1e-300;
        double qab = a + b, qap = a + 1, qam = a - 1;
        double c = 1, d = 1 - qab * x / qap;
        if (Math.abs(d) < TINY) d = TINY;
        d = 1 / d;
        double h = d;
        for (int m = 1; m <= 1000; m++) {
            int m2 = 2 * m;
            double aa = m * (b - m) * x / ((qam + m2) * (a + m2));
            d = 1 + aa * d; if (Math.abs(d) < TINY) d = TINY;
            c = 1 + aa / c; if (Math.abs(c) < TINY) c = TINY;
            d = 1 / d; h *= d * c;
            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
            d = 1 + aa * d; if (Math.abs(d) < TINY) d = TINY;
            c = 1 + aa / c; if (Math.abs(c) < TINY) c = TINY;
            d = 1 / d;
            double del = d * c;
            h *= del;
            if (Math.abs(del - 1) < 1e-16) break;
        }
        return h;
    }

    /** Regularised lower incomplete gamma P(a, x). */
    public static double pgammaReg(double a, double x) {
        if (x <= 0) return 0;
        if (x < a + 1) {
            double ap = a, sum = 1 / a, del = sum;
            for (int n = 0; n < 10000; n++) {
                ap += 1; del *= x / ap; sum += del;
                if (Math.abs(del) < Math.abs(sum) * 1e-16) break;
            }
            return sum * Math.exp(-x + a * Math.log(x) - lgamma(a));
        }
        final double TINY = 1e-300;
        double b = x + 1 - a, c = 1 / TINY, d = 1 / b, h = d;
        for (int i = 1; i < 10000; i++) {
            double an = -i * (i - a);
            b += 2;
            d = an * d + b; if (Math.abs(d) < TINY) d = TINY;
            c = b + an / c; if (Math.abs(c) < TINY) c = TINY;
            d = 1 / d;
            double del = d * c;
            h *= del;
            if (Math.abs(del - 1) < 1e-16) break;
        }
        return 1 - Math.exp(-x + a * Math.log(x) - lgamma(a)) * h;
    }

    // ------------------------------------------------------------------ chi-square
    public static double pchisq(double q, double df, boolean lower) {
        double p = pgammaReg(df / 2, q / 2);
        return lower ? p : 1 - p;
    }

    public static double qchisq(double p, double df) {
        if (p <= 0) return 0;
        if (p >= 1) return Double.POSITIVE_INFINITY;
        double lo = 0, hi = Math.max(1, df);
        while (pchisq(hi, df, true) < p) hi *= 2;
        for (int i = 0; i < 300; i++) {
            double mid = 0.5 * (lo + hi);
            if (pchisq(mid, df, true) < p) lo = mid; else hi = mid;
        }
        return 0.5 * (lo + hi);
    }

    // ------------------------------------------------------------------ Student t
    /** Upper tail P(T > t) for t >= 0. */
    private static double ptUpperPos(double t, double df) {
        if (Double.isInfinite(df)) return pnorm(t, 0, 1, false);
        double x = df / (df + t * t);
        return 0.5 * pbetaReg(x, df / 2, 0.5);
    }

    public static double pt(double t, double df, boolean lower) {
        if (Double.isInfinite(df)) return pnorm(t, 0, 1, lower);
        double up = t >= 0 ? ptUpperPos(t, df) : 1 - ptUpperPos(-t, df);
        return lower ? 1 - up : up;
    }

    /** log of the one-sided tail P(T > |t|), usable far into the tail. */
    public static double logPtUpper(double t, double df) {
        t = Math.abs(t);
        if (Double.isInfinite(df)) return logUpperNorm(t);
        double x = df / (df + t * t);
        double a = df / 2, b = 0.5;
        double lbeta = lgamma(a + b) - lgamma(a) - lgamma(b);
        double lfront = lbeta + a * Math.log(x) + b * Math.log1p(-x);
        if (x < (a + 1) / (a + b + 2)) {
            return Math.log(0.5) + lfront + Math.log(betacf(x, a, b)) - Math.log(a);
        }
        return Math.log(0.5) + Math.log(pbetaReg(x, a, b));
    }

    public static double qt(double p, double df) {
        if (Double.isInfinite(df)) return qnorm(p);
        if (p <= 0) return Double.NEGATIVE_INFINITY;
        if (p >= 1) return Double.POSITIVE_INFINITY;
        if (p == 0.5) return 0;
        boolean neg = p < 0.5;
        double pu = neg ? p : 1 - p;
        double lo = 0, hi = 1;
        while (ptUpperPos(hi, df) > pu) { hi *= 2; if (hi > 1e300) break; }
        for (int i = 0; i < 400; i++) {
            double mid = 0.5 * (lo + hi);
            if (ptUpperPos(mid, df) > pu) lo = mid; else hi = mid;
            if (hi - lo <= 1e-15 * Math.max(1, hi)) break;
        }
        double t = 0.5 * (lo + hi);
        return neg ? -t : t;
    }
}
