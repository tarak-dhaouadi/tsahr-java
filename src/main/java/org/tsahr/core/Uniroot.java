package org.tsahr.core;

import java.util.function.DoubleUnaryOperator;

/**
 * Port of R's stats::uniroot() (Brent's zeroin, R_zeroin2 in src/library/stats/src/zeroin.c).
 * Same stopping rule as R: tol, maxiter = 1000.
 */
public final class Uniroot {
    private Uniroot() {}

    public static final class NoSignChange extends RuntimeException {
        public NoSignChange() { super("f() values at end points not of opposite sign"); }
    }

    public static double root(DoubleUnaryOperator f, double lower, double upper, double tol) {
        return root(f, lower, upper, tol, 1000);
    }

    public static double root(DoubleUnaryOperator f, double lower, double upper, double tol, int maxit) {
        double fa = f.applyAsDouble(lower), fb = f.applyAsDouble(upper);
        if (Double.isNaN(fa) || Double.isNaN(fb)) throw new IllegalStateException("f() values at end points not finite");
        if (fa * fb > 0) throw new NoSignChange();
        double a = lower, b = upper, c = a, fc = fa;
        final double EPS = Rmath.DBL_EPSILON;
        int maxitLeft = maxit + 1;
        if (fa == 0.0) return a;
        if (fb == 0.0) return b;
        while (maxitLeft-- > 0) {
            double prevStep = b - a;
            double tolAct, p, q, newStep;
            if (Math.abs(fc) < Math.abs(fb)) {
                a = b; b = c; c = a;
                fa = fb; fb = fc; fc = fa;
            }
            tolAct = 2 * EPS * Math.abs(b) + tol / 2;
            newStep = (c - b) / 2;
            if (Math.abs(newStep) <= tolAct || fb == 0.0) return b;
            if (Math.abs(prevStep) >= tolAct && Math.abs(fa) > Math.abs(fb)) {
                double t1, cb, t2;
                cb = c - b;
                if (a == c) {
                    t1 = fb / fa;
                    p = cb * t1;
                    q = 1.0 - t1;
                } else {
                    q = fa / fc; t1 = fb / fc; t2 = fb / fa;
                    p = t2 * (cb * q * (q - t1) - (b - a) * (t1 - 1.0));
                    q = (q - 1.0) * (t1 - 1.0) * (t2 - 1.0);
                }
                if (p > 0.0) q = -q; else p = -p;
                if (p < (0.75 * cb * q - Math.abs(tolAct * q) / 2) && p < Math.abs(prevStep * q / 2))
                    newStep = p / q;
            }
            if (Math.abs(newStep) < tolAct) newStep = newStep > 0 ? tolAct : -tolAct;
            a = b; fa = fb;
            b += newStep; fb = f.applyAsDouble(b);
            if ((fb > 0 && fc > 0) || (fb < 0 && fc < 0)) {
                c = a; fc = fa;
            }
        }
        return b; // R would warn about non-convergence; unreachable in practice
    }
}
