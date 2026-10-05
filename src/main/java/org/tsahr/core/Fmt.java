package org.tsahr.core;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Number formatting that mirrors C's sprintf / R's round / format used by tsahr's printed output. */
public final class Fmt {
    private Fmt() {}

    /** sprintf("%.{d}f", x) with C semantics (exact binary value, round-half-even). */
    public static String f(double x, int d) {
        if (Double.isNaN(x)) return "NaN";
        if (Double.isInfinite(x)) return x > 0 ? "Inf" : "-Inf";
        BigDecimal bd = new BigDecimal(x).setScale(d, RoundingMode.HALF_EVEN);
        String s = bd.toPlainString();
        if (bd.signum() == 0 && (x < 0 || (x == 0 && 1 / x < 0))) s = "-" + s.replace("-", "");
        return s;
    }

    /** R's round(x, d) for the common (non-tie) case: exact binary value, half-even. */
    public static double round(double x, int d) {
        if (!Double.isFinite(x)) return x;
        return new BigDecimal(x).setScale(d, RoundingMode.HALF_EVEN).doubleValue();
    }

    /** format(x, scientific = FALSE, trim = TRUE, digits = 15) */
    public static String num15(double x) {
        if (Double.isNaN(x)) return "NA";
        if (Double.isInfinite(x)) return x > 0 ? "Inf" : "-Inf";
        if (x == 0) return "0";
        BigDecimal bd = new BigDecimal(x).round(new MathContext(15, RoundingMode.HALF_EVEN));
        bd = bd.stripTrailingZeros();
        String s = bd.toPlainString();
        return s;
    }

    /** formatC(x, format = "d", big.mark = ",") */
    public static String big0(double x) {
        if (!Double.isFinite(x)) return "NA";
        return bigF(x, 0);
    }

    /** formatC(x, format = "f", digits = d, big.mark = ",") */
    public static String bigF(double x, int d) {
        if (!Double.isFinite(x)) return "NA";
        String s = f(x, d);
        boolean neg = s.startsWith("-");
        if (neg) s = s.substring(1);
        String ip = s, fp = "";
        int dot = s.indexOf('.');
        if (dot >= 0) { ip = s.substring(0, dot); fp = s.substring(dot); }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ip.length(); i++) {
            if (i > 0 && (ip.length() - i) % 3 == 0) sb.append(',');
            sb.append(ip.charAt(i));
        }
        return (neg ? "-" : "") + sb + fp;
    }

    /** %.0f of ceiling(x) */
    public static String ceil0(double x) {
        if (!Double.isFinite(x)) return "NA";
        return f(Math.ceil(x), 0);
    }

    /** Simple greedy word wrap, like strwrap(x, width). */
    public static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String w : text.split("\\s+")) {
            if (w.isEmpty()) continue;
            if (cur.length() == 0) cur.append(w);
            else if (cur.length() + 1 + w.length() < width) cur.append(' ').append(w);
            else { lines.add(cur.toString()); cur = new StringBuilder(w); }
        }
        if (cur.length() > 0) lines.add(cur.toString());
        return lines;
    }
}
