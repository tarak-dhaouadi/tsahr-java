package org.tsahr.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Java port of tsahr::tsa_hr() (R package tsahr, version 0.2.8.18):
 * Trial Sequential Analysis for meta-analyses of hazard ratios.
 *
 * Differences from the R package, all deliberate and disclosed in the README:
 * <ul>
 *   <li>the legacy R-only boundary engine (a fallback in R) is not ported; if the RTSA-derived engine fails,
 *       an exception is thrown (equivalent to legacy_fallback = FALSE in R);</li>
 *   <li>tau^2 for ML/REML/EB/PM/PMM is solved by Brent's method to tight tolerance rather than by metafor's
 *       iteration with a 1e-5 threshold;</li>
 *   <li>R warnings are collected in {@link TsaHrResult#warnings}.</li>
 * </ul>
 *
 * Copyright (C) Tarak Dhaouadi for the tsahr package and this port; the boundary engine derives from RTSA
 * (Soerensen, Olsen, Lange, Gluud). GPL (>= 2).
 */
public final class TsaHr {
    private TsaHr() {}

    /** Version of the R package whose behaviour this Java code mirrors. */
    public static final String TSAHR_PARITY_VERSION = "0.2.8.18";

    // ------------------------------------------------------------------ labels
    public static String methodLabel(String m) {
        switch (m) {
            case "DL": return "DerSimonian-Laird";
            case "HE": return "Hedges";
            case "HS": return "Hunter-Schmidt";
            case "HSk": return "Hunter-Schmidt (with k correction)";
            case "SJ": return "Sidik-Jonkman";
            case "ML": return "maximum likelihood";
            case "REML": return "restricted maximum likelihood";
            case "EB": return "empirical Bayes";
            case "PM": return "Paule-Mandel";
            case "PMM": return "Paule-Mandel (median-unbiased)";
            case "GENQ": return "generalized Q-statistic";
            case "GENQM": return "generalized Q-statistic (median-unbiased)";
            case "CO": return "Hedges (Cochran alias)";
            case "VC": return "Hedges (variance-component alias)";
            default: return "'" + m + "'";
        }
    }

    public static String reInferenceLabel(String r) {
        switch (r) {
            case "standard": return "standard (Wald-type z test)";
            case "hksj": return "Hartung-Knapp-Sidik-Jonkman (HKSJ)";
            case "hksj_adhoc": return "HKSJ with ad hoc variance correction";
            default: return "'" + r + "'";
        }
    }

    static String normaliseReInference(String s) {
        String msg = "re_inference must be one of: \"standard\", \"hksj\" (alias \"knha\"), "
                + "\"hksj_adhoc\" (alias \"knha_adhoc\"); matching is case-insensitive.";
        if (s == null) throw new IllegalArgumentException(msg);
        switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "standard": return "standard";
            case "hksj": case "knha": return "hksj";
            case "hksj_adhoc": case "knha_adhoc": return "hksj_adhoc";
            default: throw new IllegalArgumentException(msg);
        }
    }

    // ------------------------------------------------------------------ data helpers
    private static final String[] REQUIRED = {"Study", "log_HR", "Std_Error", "Events_Treatment", "N_treatment",
            "Events_controls", "N_controls"};
    private static final String[] NUMERIC = {"log_HR", "Std_Error", "Events_Treatment", "N_treatment",
            "Events_controls", "N_controls"};
    private static final String[] COUNTS = {"Events_Treatment", "N_treatment", "Events_controls", "N_controls"};

    private static double[] numCol(DataTable t, String name) {
        int c = t.col(name);
        double[] a = new double[t.nrow()];
        for (int i = 0; i < a.length; i++) {
            Object o = t.rows.get(i)[c];
            a[i] = (o instanceof Double) ? (Double) o : (o instanceof Number ? ((Number) o).doubleValue() : Double.NaN);
        }
        return a;
    }

    private static boolean anyNonFinite(double[] a) { for (double v : a) if (!Double.isFinite(v)) return true; return false; }

    private static double sum(double[] a) { double s = 0; for (double v : a) s += v; return s; }

    private static boolean allEqualOne(double v) {   // isTRUE(all.equal(v, 1))
        return Math.abs(v - 1) < 1.5e-8;
    }

    // ------------------------------------------------------------------ main
    public static TsaHrResult run(DataTable input, TsaHrSettings st) {
        final TsaHrResult R = new TsaHrResult();
        final TsaHrSettings s = st.copy();
        R.settings = s;
        final List<String> warn = R.warnings;
        final StringBuilder log = new StringBuilder();
        final boolean verbose = s.verbose;

        // ---- argument validation -------------------------------------------------------------
        if (!"data".equals(s.allocationSource) && !"manual".equals(s.allocationSource))
            throw new IllegalArgumentException("allocation_source must be \"data\" or \"manual\"");
        if (!"design".equals(s.boundaryRoute) && !"analysis".equals(s.boundaryRoute))
            throw new IllegalArgumentException("boundary_route must be \"design\" or \"analysis\"");
        if (!"median".equals(s.projectionStat) && !"mean".equals(s.projectionStat))
            throw new IllegalArgumentException("projection_stat must be \"median\" or \"mean\"");
        if (!"per_study".equals(s.infoPerEventBasis) && !"pooled".equals(s.infoPerEventBasis))
            throw new IllegalArgumentException("info_per_event_basis must be \"per_study\" or \"pooled\"");

        String method = s.method;
        if ("CO".equals(method) || "VC".equals(method)) method = "HE";
        if (method == null) throw new IllegalArgumentException("method must be a single character string; one of: "
                + String.join(", ", MetaAnalysis.METHODS) + ".");
        if ("GENQ".equals(method) || "GENQM".equals(method))
            throw new IllegalArgumentException("method = \"" + method + "\" is not currently supported by tsa_hr(): "
                    + "metafor's generalized-Q-statistic estimators require a user-supplied `weights` argument. "
                    + "Supported methods are: " + String.join(", ", MetaAnalysis.METHODS) + ".");
        if (!MetaAnalysis.validMethod(method))
            throw new IllegalArgumentException("method must be one of: " + String.join(", ", MetaAnalysis.METHODS)
                    + " (the random-effects heterogeneity-variance estimators supported).");
        R.methodUsed = method;

        final String reRequested = s.reInference;
        String reInf = normaliseReInference(s.reInference);

        if (!Double.isFinite(s.alphaTwoSided) || s.alphaTwoSided <= 0 || s.alphaTwoSided >= 1)
            throw new IllegalArgumentException("alpha_two_sided must be a single finite value strictly between 0 and 1.");
        if (!Double.isFinite(s.power) || s.power <= 0 || s.power >= 1)
            throw new IllegalArgumentException("power must be a single finite value strictly between 0 and 1.");

        // ---- data ------------------------------------------------------------------------------
        DataTable data = input.copy();
        for (int i = 0; i < data.columns.size(); i++) data.columns.set(i, data.columns.get(i).trim().replace(' ', '_'));
        {
            List<String> dup = new ArrayList<>();
            for (int i = 0; i < data.columns.size(); i++)
                if (data.columns.indexOf(data.columns.get(i)) != i && !dup.contains(data.columns.get(i)))
                    dup.add(data.columns.get(i));
            if (!dup.isEmpty())
                throw new IllegalArgumentException("Column names are not unique after spaces are replaced with underscores: "
                        + String.join(", ", dup) + ". Rename the columns in the source data so they remain distinct "
                        + "once spaces become underscores.");
        }
        String orderBy = s.orderBy == null || s.orderBy.trim().isEmpty() ? null : s.orderBy.trim().replace(' ', '_');

        List<String> missing = new ArrayList<>();
        for (String c : REQUIRED) if (data.col(c) < 0) missing.add(c);
        if (!missing.isEmpty()) throw new IllegalArgumentException("Missing required column(s): " + String.join(", ", missing));

        int n0 = data.nrow();
        {
            int cs = data.col("Study");
            List<String> seen = new ArrayList<>();
            for (int i = 0; i < n0; i++) {
                Object o = data.rows.get(i)[cs];
                String nm = o == null ? null : (o instanceof Double ? Fmt.num15((Double) o) : o.toString());
                if (nm == null || nm.trim().isEmpty())
                    throw new IllegalArgumentException("Study must contain non-missing, non-empty identifiers.");
                if (seen.contains(nm))
                    throw new IllegalArgumentException("Study names must be unique (duplicate found in 'Study' column).");
                seen.add(nm);
                data.rows.get(i)[cs] = nm;
            }
        }
        if (n0 < 2) throw new IllegalArgumentException("tsa_hr() requires at least two studies.");
        if (n0 < 10)
            warn.add("Only " + n0 + " studies were supplied. Heterogeneity/D2 estimates (and therefore DARIS and the "
                    + "monitoring boundaries) can be highly unstable with few studies; the Copenhagen TSA manual "
                    + "cautions about this below roughly 10 studies.");

        {
            List<String> bad = new ArrayList<>();
            for (String c : NUMERIC) {
                int ci = data.col(c);
                for (int i = 0; i < n0; i++) if (data.rows.get(i)[ci] instanceof String) { bad.add(c + " (character)"); break; }
            }
            if (!bad.isEmpty())
                throw new IllegalArgumentException("Column(s) must be numeric, but are not: " + String.join(", ", bad)
                        + ". Check for text, footnote markers, or blank-but-not-empty cells in the source data.");
        }

        // ---- order_by (before extracting arrays so that every column follows the order)
        if (orderBy != null) {
            final int oc = data.col(orderBy);
            if (oc < 0) throw new IllegalArgumentException("order_by = '" + orderBy + "' is not a column in data.");
            boolean anyNa = false, allNum = true;
            for (Object[] r : data.rows) {
                if (r[oc] == null) anyNa = true;
                else if (!(r[oc] instanceof Double)) allNum = false;
            }
            if (anyNa) warn.add("order_by column '" + orderBy + "' contains NA values; those rows will sort to the end.");
            if (!allNum) warn.add("order_by column '" + orderBy + "' is not numeric; sorting will use lexical ordering of its "
                    + "text, which may not reflect chronological order unless e.g. formatted as 'YYYY-MM-DD'.");
            {
                List<Object> nn = new ArrayList<>();
                boolean dupTie = false;
                for (Object[] r : data.rows) if (r[oc] != null) { if (nn.contains(r[oc])) dupTie = true; nn.add(r[oc]); }
                if (dupTie)
                    warn.add("order_by column '" + orderBy + "' contains tied values; TSA is order-dependent, so the "
                            + "relative order of tied studies (broken by stable sort, i.e. their original row order among "
                            + "ties) may affect the cumulative TSA. Consider a finer-grained order_by column.");
            }
            final boolean numeric = allNum;
            data.rows.sort((a, b) -> {
                Object x = a[oc], y = b[oc];
                if (x == null && y == null) return 0;
                if (x == null) return 1;
                if (y == null) return -1;
                if (numeric) return Double.compare((Double) x, (Double) y);
                return x.toString().compareTo(y.toString());
            });
            log.append("Studies sorted by '").append(orderBy).append("' (ascending) for the cumulative analysis.\n");
        } else {
            log.append("Study order used for sequential analysis (assumed chronological -- set order_by to sort explicitly):\n");
        }

        final int k = data.nrow();
        R.study = new String[k];
        for (int i = 0; i < k; i++) R.study[i] = (String) data.rows.get(i)[data.col("Study")];
        R.logHR = numCol(data, "log_HR");
        R.stdError = numCol(data, "Std_Error");
        R.eventsTreatment = numCol(data, "Events_Treatment");
        R.nTreatment = numCol(data, "N_treatment");
        R.eventsControls = numCol(data, "Events_controls");
        R.nControls = numCol(data, "N_controls");

        if (anyNonFinite(R.logHR)) throw new IllegalArgumentException("log_HR must be finite for every study (found NA/NaN/Inf).");
        if (anyNonFinite(R.stdError) || Arrays.stream(R.stdError).anyMatch(v -> v <= 0))
            throw new IllegalArgumentException("Std_Error must be finite and strictly positive for every study.");
        {
            List<String> nf = new ArrayList<>();
            for (String c : COUNTS) if (anyNonFinite(numCol(data, c))) nf.add(c);
            if (!nf.isEmpty())
                throw new IllegalArgumentException("Column(s) must contain only finite values (found NA/NaN/Inf): " + String.join(", ", nf));
        }
        if (Arrays.stream(R.eventsTreatment).anyMatch(v -> v < 0) || Arrays.stream(R.eventsControls).anyMatch(v -> v < 0))
            throw new IllegalArgumentException("Events_Treatment and Events_controls must be >= 0.");
        if (Arrays.stream(R.nTreatment).anyMatch(v -> v <= 0) || Arrays.stream(R.nControls).anyMatch(v -> v <= 0))
            throw new IllegalArgumentException("N_treatment and N_controls must be > 0.");
        for (int i = 0; i < k; i++) {
            if (R.eventsTreatment[i] > R.nTreatment[i])
                throw new IllegalArgumentException("Events_Treatment cannot exceed N_treatment for any study.");
            if (R.eventsControls[i] > R.nControls[i])
                throw new IllegalArgumentException("Events_controls cannot exceed N_controls for any study.");
        }

        final double targetHR = s.targetHR;
        final boolean targetNA = Double.isNaN(targetHR);
        if (!targetNA && !Double.isFinite(targetHR))
            throw new IllegalArgumentException("target_HR must be a single finite numeric value, or NA.");
        if (!targetNA && targetHR <= 0) throw new IllegalArgumentException("target_HR must be > 0.");
        if (!targetNA && allEqualOne(targetHR))
            throw new IllegalArgumentException("target_HR cannot equal 1: ln(HR)=0 makes the required information infinite.");
        if (!targetNA && targetHR > 0.90 && targetHR < 1.10)
            warn.add("target_HR (" + Fmt.num15(targetHR) + ") is very close to the null value of 1; the required information "
                    + "size increases rapidly as log(target_HR) approaches zero. Confirm that this represents a clinically "
                    + "meaningful target effect.");
        if ("manual".equals(s.allocationSource)
                && (!Double.isFinite(s.allocationP) || s.allocationP <= 0 || s.allocationP >= 1))
            throw new IllegalArgumentException("allocation_p must be a single finite numeric value strictly between 0 and 1.");
        {
            double tolInt = Math.sqrt(Rmath.DBL_EPSILON);
            List<String> ni = new ArrayList<>();
            for (String c : COUNTS) {
                for (double v : numCol(data, c)) if (Math.abs(v - Math.rint(v)) > tolInt) { ni.add(c); break; }
            }
            if (!ni.isEmpty())
                throw new IllegalArgumentException("Event counts and sample sizes must be whole numbers. Non-integer value(s) found in: "
                        + String.join(", ", ni));
        }

        R.totalEvents = new double[k];
        R.totalN = new double[k];
        for (int i = 0; i < k; i++) {
            R.totalEvents[i] = R.eventsTreatment[i] + R.eventsControls[i];
            R.totalN[i] = R.nTreatment[i] + R.nControls[i];
        }
        if (verbose) log.append(String.join(" | ", R.study)).append("\n\n");
        log.append("Loaded ").append(k).append(" studies. Total events across all studies: ")
           .append(Fmt.num15(sum(R.totalEvents))).append(" / total participants: ").append(Fmt.num15(sum(R.totalN))).append("\n\n");

        // ---- meta-analysis -----------------------------------------------------------------------
        MetaAnalysis.Fit resStd = MetaAnalysis.fit(R.logHR, R.stdError, method, "z");
        MetaAnalysis.Fit resFe = MetaAnalysis.fitFE(R.logHR, R.stdError);
        MetaAnalysis.Fit resRe;
        if ("standard".equals(reInf)) {
            resRe = resStd;
        } else {
            double qPooled = MetaAnalysis.hksjQ(R.logHR, R.stdError, resStd.tau2);
            if (!Double.isFinite(qPooled) || qPooled <= 0) {
                warn.add("re_inference = \"" + reRequested + "\" needs a positive, finite Hartung-Knapp scale factor, but it is "
                        + Fmt.num15(qPooled) + " for these data (e.g. identical study estimates); falling back to re_inference = \"standard\".");
                reInf = "standard";
                resRe = resStd;
            } else {
                String testArg = ("hksj_adhoc".equals(reInf) && qPooled < 1) ? "t" : "knha";
                resRe = MetaAnalysis.fit(R.logHR, R.stdError, method, testArg);
            }
        }
        if (!"standard".equals(reInf)) {
            warn.add("HKSJ inference (re_inference = \"" + reRequested + "\") can be unstable at early cumulative looks because the "
                    + "degrees of freedom are k-1: the HKSJ statistic is undefined at k=1 (\"NA\" at the first look) and is based "
                    + "on only one degree of freedom at k=2. Early cumulative HKSJ values should not be interpreted as directly "
                    + "comparable in magnitude with conventional normal Z-statistics.");
        }
        R.reInference = reInf;
        R.resStd = resStd; R.resFe = resFe; R.resRe = resRe;

        if (verbose) {
            if ("standard".equals(reInf))
                log.append("=== Random-effects (").append(methodLabel(method)).append(") meta-analysis ===\n");
            else
                log.append("=== Random-effects (").append(methodLabel(method)).append(") meta-analysis; inference: ")
                   .append(reInferenceLabel(reInf)).append(" ===\n");
            log.append("k = ").append(resRe.k).append("; tau^2 = ").append(Fmt.f(resRe.tau2, 4))
               .append("; I^2 = ").append(Fmt.f(resRe.i2, 2)).append("%\n");
            log.append("Test of heterogeneity: Q(df = ").append(resStd.k - 1).append(") = ").append(Fmt.f(resStd.qe, 4))
               .append(", p-val = ").append(Fmt.f(resStd.qep, 4)).append("\n");
            log.append("Pooled log HR = ").append(Fmt.f(resRe.b, 4)).append(" (se = ").append(Fmt.f(resRe.se, 4))
               .append(", ").append("z".equals(resRe.test) ? "z" : "t").append(" = ").append(Fmt.f(resRe.zval, 4))
               .append(", p = ").append(Fmt.f(resRe.pval, 4)).append(", 95% CI: ").append(Fmt.f(resRe.ciLb, 4))
               .append(", ").append(Fmt.f(resRe.ciUb, 4)).append(")\n");
            log.append(String.format("%nPooled HR (random effects): %s  95%% CI: %s-%s%n%n", Fmt.f(Math.exp(resRe.b), 3),
                    Fmt.f(Math.exp(resRe.ciLb), 3), Fmt.f(Math.exp(resRe.ciUb), 3)));
        }

        R.Q = resStd.qe;
        R.Qdf = resStd.k - 1;
        R.I2 = resStd.i2;
        R.tau2 = resStd.tau2;
        R.QEp = resStd.qep;
        double varRandom = resStd.vb, varFixed = resFe.vb;
        double d2Raw = Math.max(0, (varRandom - varFixed) / varRandom);
        boolean d2Capped = d2Raw >= 0.999;
        double D2 = d2Capped ? 0.999 : d2Raw;
        if (d2Capped)
            warn.add("Diversity D2 is at or very near its theoretical upper bound (100%), indicating extreme heterogeneity "
                    + "relative to the number of studies. D2 has been capped at 99.9% to avoid a numerically unstable/explosive "
                    + "heterogeneity adjustment factor; interpret the required information size and DARIS with caution in this scenario.");
        double AF = 1 / (1 - D2);
        R.D2Raw = d2Raw; R.D2 = D2; R.D2Capped = d2Capped; R.AF = AF;

        log.append("=== Heterogeneity ===\n");
        log.append(String.format("Q = %s (df = %d), p = %s%n", Fmt.f(R.Q, 2), R.Qdf, Fmt.f(R.QEp, 4)));
        log.append(String.format("I^2 = %s%%   tau^2 = %s%n", Fmt.f(R.I2, 1), Fmt.f(R.tau2, 4)));
        log.append(String.format("Diversity D^2 = %s%%   Adjustment factor (1/(1-D2)) = %s%n%n", Fmt.f(D2 * 100, 1), Fmt.f(AF, 3)));

        // ---- allocation ----------------------------------------------------------------------------
        double allocP;
        String allocNote;
        if ("data".equals(s.allocationSource)) {
            allocP = sum(R.nTreatment) / sum(R.totalN);
            allocNote = String.format("computed from pooled data: %s/%s treatment patients (psi = %s, ~%s:%s ratio)",
                    Fmt.f(sum(R.nTreatment), 0), Fmt.f(sum(R.totalN), 0), Fmt.f(allocP, 4),
                    Fmt.f(Math.rint(allocP * 100), 0), Fmt.f(Math.rint((1 - allocP) * 100), 0));
        } else {
            allocP = s.allocationP;
            allocNote = "user-specified (manual) psi = " + Fmt.f(allocP, 4);
        }
        if (allocP <= 0 || allocP >= 1)
            throw new IllegalArgumentException("Computed/used allocation_p_used must be strictly between 0 and 1 -- check N_treatment/N_controls in your data.");
        R.allocationPUsed = allocP; R.allocationNote = allocNote;
        log.append("=== Allocation ratio ===\n").append(allocNote).append("\n\n");

        // ---- required information size -----------------------------------------------------------------
        double zAlpha = Rmath.qnorm(1 - s.alphaTwoSided / 2);
        double zBeta = Rmath.qnorm(s.power);
        double hrAnt, logHrAnt;
        String effectSource;
        if (targetNA) {
            logHrAnt = resRe.b;
            hrAnt = Math.exp(resRe.b);
            effectSource = "OBSERVED pooled HR (random-effects model) -- see circularity caution in the tsahr documentation";
            warn.add("target_HR was not specified: the OBSERVED pooled hazard ratio from this meta-analysis is being used to "
                    + "calculate the required information size. This is circular and is appropriate for exploratory use only -- "
                    + "for a publication-quality TSA, set target_HR to a pre-specified, clinically-anticipated hazard ratio, "
                    + "e.g. target_HR = 0.80.");
        } else {
            hrAnt = targetHR;
            logHrAnt = Math.log(targetHR);
            effectSource = "user-specified target_HR (pre-specified)";
        }
        double infoRequired = Math.pow(zAlpha + zBeta, 2) / (logHrAnt * logHrAnt);
        double risEvents = infoRequired / (allocP * (1 - allocP));
        double darisInfo = infoRequired * AF;
        double darisEvents = risEvents * AF;
        R.zAlpha = zAlpha; R.zBeta = zBeta; R.HRAnticipated = hrAnt; R.logHRAnticipated = logHrAnt;
        R.infoRequired = infoRequired; R.RISEvents = risEvents; R.DARISInfo = darisInfo; R.DARISEvents = darisEvents;
        R.effectSource = effectSource;
        double eventsAccrued = sum(R.totalEvents);
        R.eventsAccrued = eventsAccrued;

        log.append("=== Required Information Size (time-to-event / Schoenfeld formula) ===\n");
        log.append("Effect size source: ").append(effectSource).append("\n");
        log.append(String.format("Anticipated HR (used for RIS calculation): %s (ln HR = %s)%n", Fmt.f(hrAnt, 3), Fmt.f(logHrAnt, 4)));
        log.append(String.format("alpha (2-sided) = %s, power = %s%%, allocation psi = %s (%s:%s)%n", Fmt.f(s.alphaTwoSided, 3),
                Fmt.f(s.power * 100, 0), Fmt.f(allocP, 4), Fmt.f(Math.rint(allocP * 100), 0), Fmt.f(Math.rint((1 - allocP) * 100), 0)));
        log.append(String.format("Required statistical information (allocation-free): %s%n", Fmt.f(infoRequired, 4)));
        log.append(String.format("Required number of events (RIS, no heterogeneity adj., under psi=%s): %s%n", Fmt.f(allocP, 3), Fmt.ceil0(risEvents)));
        log.append(String.format("Diversity-Adjusted Required Information (DARIS, information units): %s%n", Fmt.f(darisInfo, 4)));
        log.append(String.format("DARIS translated to an equivalent number of events (under pooled psi): %s%n%n", Fmt.ceil0(darisEvents)));
        log.append(String.format("Total events accrued across included studies: %s (%s%% of DARIS)%n%n", Fmt.f(eventsAccrued, 0),
                Fmt.f(100 * eventsAccrued / darisEvents, 1)));

        R.circularityWarning = targetNA;
        R.circularitySevere = targetNA && eventsAccrued / darisEvents > 3;
        if (R.circularitySevere) {
            log.append("*** NOTE: accrued events greatly exceed the DARIS because the RIS was\n"
                    + "    calculated from the observed (very large, very precise) pooled effect.\n"
                    + "    This is circular and will make the TSA boundary collapse almost\n"
                    + "    immediately to the conventional boundary. Consider re-running with\n"
                    + "    a pre-specified 'target_HR' for a more standard, protocol-driven TSA. ***\n\n");
        } else if (R.circularityWarning) {
            log.append("*** NOTE: 'target_HR' was not specified, so the required information size\n"
                    + "    was calculated from the OBSERVED pooled effect. This is circular: the\n"
                    + "    required information size depends on the result it is being used to\n"
                    + "    evaluate. Set a pre-specified 'target_HR' for a standard,\n"
                    + "    protocol-driven TSA. ***\n\n");
        }

        // ---- cumulative analysis -----------------------------------------------------------------
        MetaAnalysis.Fit[] cum = MetaAnalysis.cumulative(R.logHR, R.stdError, method);
        TsaHrResult.Cumulative C = new TsaHrResult.Cumulative();
        C.study = R.study.clone();
        C.estimate = new double[k]; C.se = new double[k]; C.z = new double[k]; C.zval = new double[k];
        C.pval = new double[k]; C.ciLb = new double[k]; C.ciUb = new double[k]; C.tau2 = new double[k];
        C.reScale = new double[k]; C.reDf = new double[k];
        C.cumEvents = new double[k]; C.cumN = new double[k]; C.infoAccrued = new double[k];
        C.infoFraction = new double[k]; C.infoFractionEventBased = new double[k];
        C.boundaryUpper = new double[k]; C.boundaryLower = new double[k];
        C.futilityUpper = new double[k]; C.futilityLower = new double[k];
        MetaAnalysis.CumHksj hk = "standard".equals(reInf) ? null
                : MetaAnalysis.cumulativeHksj(cum, R.logHR, R.stdError, "hksj_adhoc".equals(reInf));
        double ce = 0, cn = 0, ci = 0;
        for (int i = 0; i < k; i++) {
            C.estimate[i] = cum[i].b;
            C.tau2[i] = cum[i].tau2;
            if (hk == null) {
                C.se[i] = cum[i].se; C.zval[i] = cum[i].zval; C.pval[i] = cum[i].pval;
                C.ciLb[i] = cum[i].ciLb; C.ciUb[i] = cum[i].ciUb;
                C.z[i] = cum[i].b / cum[i].se;
                C.reScale[i] = 1.0; C.reDf[i] = Double.POSITIVE_INFINITY;
            } else {
                C.se[i] = hk.se[i]; C.zval[i] = hk.stat[i]; C.pval[i] = hk.p[i];
                C.ciLb[i] = hk.lb[i]; C.ciUb[i] = hk.ub[i]; C.z[i] = hk.z[i];
                C.reScale[i] = hk.scale[i]; C.reDf[i] = hk.df[i];
            }
            ce += R.totalEvents[i]; cn += R.totalN[i]; ci += 1.0 / (R.stdError[i] * R.stdError[i]);
            C.cumEvents[i] = ce; C.cumN[i] = cn; C.infoAccrued[i] = ci;
            C.infoFraction[i] = ci / darisInfo;
            C.infoFractionEventBased[i] = ce / darisEvents;
            C.boundaryUpper[i] = C.boundaryLower[i] = C.futilityUpper[i] = C.futilityLower[i] = Double.NaN;
        }
        R.cumulative = C;

        if (verbose) {
            log.append(String.format("%-14s %10s %9s %9s %10s %13s %13s%n", "Study", "estimate", "se", "Z", "cum_events",
                    "info_fraction", "info_frac_ev"));
            for (int i = 0; i < k; i++)
                log.append(String.format("%-14s %10s %9s %9s %10s %13s %13s%n", trunc(R.study[i], 14), Fmt.f(C.estimate[i], 5),
                        Fmt.f(C.se[i], 5), Double.isNaN(C.z[i]) ? "NA" : Fmt.f(C.z[i], 4), Fmt.f(C.cumEvents[i], 0),
                        Fmt.f(C.infoFraction[i], 5), Fmt.f(C.infoFractionEventBased[i], 5)));
            log.append("\n*** IMPORTANT CAVEAT: the cumulative Z-curve above is from a RANDOM-\n"
                    + "    EFFECTS model, whose between-study variance (tau^2) is RE-ESTIMATED\n"
                    + "    at every step. The Lan-DeMets/O'Brien-Fleming monitoring boundaries\n"
                    + "    strictly assume a fixed, CANONICAL information process with\n"
                    + "    independent Brownian-motion increments. A random-effects cumulative\n"
                    + "    Z-curve with tau^2 re-estimated at each look does not exactly satisfy\n"
                    + "    those assumptions, so applying the boundaries here is a widely-used\n"
                    + "    APPROXIMATION (as in the official Copenhagen Trial Unit TSA\n"
                    + "    software), not an exact result. ***\n\n");
        }

        // ---- boundaries --------------------------------------------------------------------------
        final double[] infoFracs = C.infoFraction;
        double[] timingDesign;
        {
            java.util.TreeSet<Double> ts = new java.util.TreeSet<>();
            for (double v : infoFracs) if (v < 1) ts.add(v);
            ts.add(1.0);
            timingDesign = new double[ts.size()];
            int j = 0;
            for (double v : ts) timingDesign[j++] = v;
        }
        RtsaBounds.Bounds designFit;
        try {
            designFit = RtsaBounds.designBounds(timingDesign, s.alphaTwoSided, 1 - s.power);
        } catch (RuntimeException e) {
            throw new IllegalStateException("The RTSA-derived boundary engine failed (" + e.getMessage() + "). The Java edition has no "
                    + "legacy approximate engine to fall back on; check the design (information fractions, alpha, power).", e);
        }
        warn.addAll(designFit.warnings);

        String routeUsed = s.boundaryRoute;
        double routeEndpoint;
        double[] boundaryTiming, alphaBoundsDesign, betaBoundsDesign;
        boolean fallbackUsed = false;
        String fallbackRoute = "none", fallbackReason = null, engine;
        R.designRoot = designFit.root; R.delta = designFit.delta;
        double[] betaSpentCum, betaSpentIncr;
        int rmBs;

        if ("design".equals(s.boundaryRoute)) {
            alphaBoundsDesign = designFit.alphaUbound;
            double[] pre = new double[timingDesign.length - 1];
            for (int i = 0; i < pre.length; i++) pre[i] = designFit.betaUbound[i];
            routeEndpoint = 1;
            betaBoundsDesign = designFit.betaUbound.clone();
            // beta at the final look equals the efficacy wall (beta_final <- tail(alpha_bounds_design, 1))
            betaBoundsDesign[betaBoundsDesign.length - 1] = alphaBoundsDesign[alphaBoundsDesign.length - 1];
            boundaryTiming = timingDesign;
            engine = "rtsa_design_cpp";
            betaSpentCum = designFit.betaSpent; betaSpentIncr = designFit.betaSpentDelta; rmBs = designFit.rmBs;
        } else {
            double designR = designFit.root;
            double mx = Arrays.stream(infoFracs).max().getAsDouble();
            double[] tExt;
            if (mx < designR) {
                tExt = Arrays.copyOf(infoFracs, infoFracs.length + 1);
                tExt[tExt.length - 1] = designR;
            } else if (mx > designR) {
                List<Double> l = new ArrayList<>();
                for (double v : infoFracs) if (v < designR) l.add(v);
                l.add(designR);
                tExt = new double[l.size()];
                for (int i = 0; i < tExt.length; i++) tExt[i] = l.get(i);
            } else {
                tExt = infoFracs.clone();
            }
            RtsaBounds.Bounds ana = null;
            String anaErr = null;
            try {
                ana = RtsaBounds.analysisBounds(tExt, designR, s.alphaTwoSided, 1 - s.power);
            } catch (RuntimeException e) {
                anaErr = e.getMessage();
            }
            if (ana == null) {
                String fb = "*** WARNING: THE RTSA ANALYSIS-ROUTE ENGINE FAILED (" + anaErr + "). boundary_route = \"analysis\" was "
                        + "requested, but tsa_hr() is falling back to its \"design\"-route result instead (still the RTSA-derived "
                        + "engine, just the other route). ***";
                if (!s.legacyFallback)
                    throw new IllegalStateException("The RTSA analysis-route boundary engine failed (" + anaErr + "), and "
                            + "legacy_fallback = false means tsa_hr() will not silently fall back to the design-route result.");
                warn.add(fb);
                if (verbose) log.append('\n').append(fb).append("\n\n");
                alphaBoundsDesign = designFit.alphaUbound;
                routeEndpoint = 1;
                betaBoundsDesign = designFit.betaUbound.clone();
                betaBoundsDesign[betaBoundsDesign.length - 1] = alphaBoundsDesign[alphaBoundsDesign.length - 1];
                boundaryTiming = timingDesign;
                fallbackUsed = true; fallbackRoute = "design"; fallbackReason = anaErr; routeUsed = "design";
                engine = "rtsa_design_cpp";
                betaSpentCum = designFit.betaSpent; betaSpentIncr = designFit.betaSpentDelta; rmBs = designFit.rmBs;
            } else {
                warn.addAll(ana.warnings);
                routeEndpoint = designR;
                boundaryTiming = ana.timing;
                alphaBoundsDesign = ana.alphaUbound;
                betaBoundsDesign = ana.betaUbound;
                engine = "rtsa_analysis_cpp";
                betaSpentCum = ana.betaSpent; betaSpentIncr = ana.betaSpentDelta; rmBs = ana.rmBs;
            }
        }
        R.routeUsed = routeUsed; R.engine = engine; R.routeEndpoint = routeEndpoint;
        R.fallbackUsed = fallbackUsed; R.fallbackRoute = fallbackRoute; R.fallbackReason = fallbackReason;
        R.betaSpentCum = betaSpentCum; R.betaSpentIncr = betaSpentIncr; R.rmBs = rmBs;

        double maxInfo = Arrays.stream(infoFracs).max().getAsDouble();
        boolean finalReached = maxInfo >= routeEndpoint;
        boolean darisReached = maxInfo >= 1;
        boolean analysisEndpoint = "analysis".equals(routeUsed);
        double routeEndpointInfo = routeEndpoint * darisInfo;
        double darisThresholdEvents = RtsaBounds.eventsAtFraction(C.infoFraction, C.cumEvents, 1);
        double routeEndpointEventsEst = routeEndpoint == 1 ? darisThresholdEvents
                : RtsaBounds.eventsAtFraction(C.infoFraction, C.cumEvents, routeEndpoint);
        String endpointName = analysisEndpoint ? "analysis-route endpoint (" + Fmt.f(routeEndpoint, 3) + " x DARIS)" : "DARIS";
        R.finalReached = finalReached; R.darisReached = darisReached;
        R.routeEndpointInfo = routeEndpointInfo; R.DARISInfoThresholdEvents = darisThresholdEvents;
        R.routeEndpointEventsEst = routeEndpointEventsEst;

        for (int i = 0; i < k; i++) {
            if (infoFracs[i] < routeEndpoint) {
                int m = matchIdx(boundaryTiming, infoFracs[i]);
                double a = m < 0 ? Double.NaN : alphaBoundsDesign[m];
                double b = m < 0 ? Double.NaN : betaBoundsDesign[m];
                C.boundaryUpper[i] = a; C.boundaryLower[i] = -a;
                C.futilityUpper[i] = b; C.futilityLower[i] = -b;
            }
        }

        double boundaryEndpointEvents = (finalReached && Double.isFinite(routeEndpointEventsEst))
                ? routeEndpointEventsEst : darisEvents * routeEndpoint;
        R.boundaryEndpointEvents = boundaryEndpointEvents;

        int nb = boundaryTiming.length;
        TsaHrResult.BoundaryTimeline TL = new TsaHrResult.BoundaryTimeline();
        TL.infoFraction = boundaryTiming.clone();
        TL.cumEvents = new double[nb]; TL.boundaryUpper = new double[nb]; TL.boundaryLower = new double[nb];
        TL.futilityUpper = new double[nb]; TL.futilityLower = new double[nb]; TL.synthetic = new boolean[nb];
        {
            int cnt = 0;
            for (int i = 0; i < nb; i++) {
                if (boundaryTiming[i] < routeEndpoint) {
                    int idx = -1;
                    for (int j = 0; j < k; j++) if (infoFracs[j] == boundaryTiming[i]) { idx = j; break; }
                    TL.cumEvents[cnt++] = idx < 0 ? Double.NaN : C.cumEvents[idx];
                }
            }
            if (cnt < nb) TL.cumEvents[cnt] = boundaryEndpointEvents;   // R's c(...) has the same length as the timing
        }
        for (int i = 0; i < nb; i++) {
            TL.boundaryUpper[i] = alphaBoundsDesign[i]; TL.boundaryLower[i] = -alphaBoundsDesign[i];
            TL.futilityUpper[i] = betaBoundsDesign[i]; TL.futilityLower[i] = -betaBoundsDesign[i];
            TL.synthetic[i] = boundaryTiming[i] == routeEndpoint;
        }
        R.boundaryTimeline = TL;

        if (verbose) {
            log.append("=== Trial sequential monitoring boundaries (alpha/beta spending) ===\n");
            if (analysisEndpoint)
                log.append(String.format("Formal final boundary endpoint (%s = %s information units): %s cumulative events%n",
                        endpointName, Fmt.f(routeEndpointInfo, 4), Fmt.f(boundaryEndpointEvents, 1)));
            else
                log.append(String.format("Formal final boundary endpoint (DARIS information reached): %s cumulative events%n",
                        Fmt.f(boundaryEndpointEvents, 1)));
            log.append(String.format("%-14s %13s %9s %12s %12s%n", "Study", "info_fraction", "Z", "boundary", "futility"));
            for (int i = 0; i < k; i++)
                log.append(String.format("%-14s %13s %9s %12s %12s%n", trunc(R.study[i], 14), Fmt.f(C.infoFraction[i], 5),
                        Double.isNaN(C.z[i]) ? "NA" : Fmt.f(C.z[i], 4),
                        Double.isNaN(C.boundaryUpper[i]) ? "NA" : Fmt.f(C.boundaryUpper[i], 4),
                        Double.isNaN(C.futilityUpper[i]) ? "NA" : Fmt.f(C.futilityUpper[i], 4)));
            log.append('\n');
        }

        // ---- decisions -----------------------------------------------------------------------------
        int finalTsaLook = k;
        if (finalReached) for (int i = 0; i < k; i++) if (infoFracs[i] >= routeEndpoint) { finalTsaLook = i + 1; break; }
        R.finalTsaLook = finalTsaLook;
        double[] decB = new double[finalTsaLook], decF = new double[finalTsaLook];
        for (int i = 0; i < finalTsaLook; i++) {
            int m = matchIdx(TL.infoFraction, Math.min(infoFracs[i], routeEndpoint));
            decB[i] = m < 0 ? Double.NaN : TL.boundaryUpper[m];
            decF[i] = m < 0 ? Double.NaN : TL.futilityUpper[m];
        }
        boolean crossedTsa = false, enteredFut = false, crossedConv = false;
        for (int i = 0; i < finalTsaLook; i++) {
            double az = Math.abs(C.z[i]);
            if (!Double.isNaN(az) && !Double.isNaN(decB[i]) && az >= decB[i]) crossedTsa = true;
            if (!Double.isNaN(az) && !Double.isNaN(decF[i]) && az <= decF[i]) enteredFut = true;
        }
        for (int i = 0; i < k; i++) {
            double az = Math.abs(C.z[i]);
            if (!Double.isNaN(az) && az >= zAlpha) crossedConv = true;
        }
        R.crossedTsa = crossedTsa; R.enteredFutilityRegion = enteredFut; R.crossedConventional = crossedConv;
        if (finalReached) {
            double zf = Math.abs(C.z[finalTsaLook - 1]);
            double bf = decB[finalTsaLook - 1], ff = decF[finalTsaLook - 1];
            Boolean crossed = (Double.isNaN(bf) || Double.isNaN(zf)) ? null : (zf >= bf);
            Boolean entered = (Double.isNaN(ff) || Double.isNaN(zf)) ? null : (zf <= ff);
            R.finalCrossedEfficacy = crossed;
            R.finalEnteredFutilityRegion = entered;
            R.finalNonEfficacy = crossed == null ? null : !crossed;
        }
        double infoAccruedFinal = C.infoAccrued[k - 1];
        R.infoAccruedFinal = infoAccruedFinal;

        // ---- projection ----------------------------------------------------------------------------
        TsaHrResult.Projection P = new TsaHrResult.Projection();
        double[] perInfo = new double[k], perEv = R.totalEvents;
        for (int i = 0; i < k; i++) perInfo[i] = 1.0 / (R.stdError[i] * R.stdError[i]);
        boolean useMean = "mean".equals(s.projectionStat);
        double centralInfoInc = useMean ? MetaAnalysis.mean(perInfo) : MetaAnalysis.median(perInfo);
        double centralEvInc = useMean ? MetaAnalysis.mean(perEv) : MetaAnalysis.median(perEv);
        List<Double> ratios = new ArrayList<>();
        double sumInfoOk = 0, sumEvOk = 0;
        int nZero = 0, nExcl = 0;
        for (int i = 0; i < k; i++) {
            if (perEv[i] == 0) nZero++;
            double ratio = perInfo[i] / perEv[i];
            boolean ok = Double.isFinite(ratio) && ratio > 0;
            if (!ok) nExcl++;
            else { ratios.add(ratio); sumInfoOk += perInfo[i]; sumEvOk += perEv[i]; }
        }
        double[] rat = new double[ratios.size()];
        for (int i = 0; i < rat.length; i++) rat[i] = ratios.get(i);
        double studyLevelIPE = rat.length > 0 ? (useMean ? MetaAnalysis.mean(rat) : MetaAnalysis.median(rat)) : Double.NaN;
        double pooledIPE = rat.length > 0 ? sumInfoOk / sumEvOk : Double.NaN;
        boolean pooledBasis = "pooled".equals(s.infoPerEventBasis);
        double centralIPE = pooledBasis ? pooledIPE : studyLevelIPE;
        String basisLabel = pooledBasis ? "pooled ratio: total information / total events"
                : s.projectionStat + " of the study-level information per event";
        String basisShort = pooledBasis ? "pooled" : s.projectionStat;
        String exclusionNote = null;
        if (nExcl > 0) {
            if (nExcl == nZero)
                exclusionNote = String.format("%d of %d %s excluded from the information-per-event (events) projection: they carry "
                        + "information but no events to attach it to. They remain in the additional-studies projection and in all "
                        + "other results.", nExcl, k, nExcl == 1 ? "study with zero events was" : "studies with zero events were");
            else
                exclusionNote = String.format("%d of %d studies were excluded from the information-per-event (events) projection "
                        + "(%d with zero events, %d with another non-finite or non-positive ratio). They remain in the "
                        + "additional-studies projection and in all other results.", nExcl, k, nZero, nExcl - nZero);
        }
        double addInfoRaw = routeEndpointInfo - infoAccruedFinal;
        if (!finalReached && Double.isFinite(addInfoRaw) && addInfoRaw > 0) {
            if (Double.isFinite(studyLevelIPE) && studyLevelIPE > 0) P.additionalEventsStudyLevel = addInfoRaw / studyLevelIPE;
            if (Double.isFinite(pooledIPE) && pooledIPE > 0) P.additionalEventsPooled = addInfoRaw / pooledIPE;
            P.additionalEventsEstimated = pooledBasis ? P.additionalEventsPooled : P.additionalEventsStudyLevel;
            if (Double.isFinite(P.additionalEventsEstimated)) P.targetEventsHistoricalRate = eventsAccrued + P.additionalEventsEstimated;
            if (Double.isFinite(centralInfoInc) && centralInfoInc > 0) {
                P.nAdditionalStudies = Math.max(1.0, Math.ceil(addInfoRaw / centralInfoInc));
                if (Double.isFinite(centralEvInc)) P.eventsFromWholeStudies = P.nAdditionalStudies * centralEvInc;
            }
        }
        if (!darisReached) P.additionalEventsRequiredDesign = Math.max(Math.ceil(darisEvents - eventsAccrued), 0);
        if (!finalReached) P.additionalEventsTheoretical = Math.max(Math.ceil(darisEvents * routeEndpoint - eventsAccrued), 0);
        P.note = "Projection assumes future studies contribute information at approximately the observed historical rate ("
                + basisLabel + "); it is not a formal guarantee of the number of future studies or events required. It is a "
                + "linear extrapolation on the fixed-effect, study-level inverse-variance information scale that is compared with "
                + "DARIS, and it does not model how random-effects weights or the between-study variance (\u03c4\u00b2) would "
                + "change as further studies are added; treat it as indicative only.";
        P.method = s.projectionStat; P.infoPerEventBasis = s.infoPerEventBasis;
        P.iRequired = routeEndpointInfo; P.infoAccrued = infoAccruedFinal; P.eventsAccrued = eventsAccrued;
        P.additionalInfoRequired = !finalReached ? Math.max(addInfoRaw, 0) : Double.NaN;
        P.centralInfoIncrement = centralInfoInc; P.centralEventIncrement = centralEvInc; P.centralInfoPerEvent = centralIPE;
        P.studyLevelInfoPerEvent = studyLevelIPE; P.pooledInfoPerEvent = pooledIPE;
        P.nStudies = k; P.nZeroEventStudies = nZero; P.nExcludedEventsProjection = nExcl; P.exclusionNote = exclusionNote;
        R.projection = P;

        // ---- verbose conclusions -----------------------------------------------------------------------
        if (verbose) {
            if (finalReached && finalTsaLook < k)
                log.append(String.format("Note: %s was reached at study #%d of %d ('%s'). Formal TSA%n"
                        + "  boundary-crossing/futility decisions below are evaluated only%n"
                        + "  through that look (studies added afterward are still shown in%n"
                        + "  the returned data and plot, but are not treated as additional%n"
                        + "  formal '%s' analyses).%n", endpointName, finalTsaLook, k, R.study[finalTsaLook - 1],
                        analysisEndpoint ? "t=" + Fmt.f(routeEndpoint, 3) : "t=1"));
            log.append("Cumulative Z-curve crossed the conventional (P<0.05) boundary: ").append(crossedConv ? "YES" : "NO").append('\n');
            log.append("Cumulative Z-curve crossed the TSA monitoring boundary       : ").append(crossedTsa ? "YES" : "NO").append('\n');
            log.append("Cumulative Z-curve entered the non-binding futility region  : ").append(enteredFut ? "YES" : "NO").append('\n');
            if (finalReached)
                log.append("Definitive look (").append(analysisEndpoint ? "route endpoint" : "DARIS")
                   .append(") crossed the efficacy boundary").append(analysisEndpoint ? "" : "         ").append(": ")
                   .append(Boolean.TRUE.equals(R.finalCrossedEfficacy) ? "YES" : "NO").append('\n');
            log.append("Required information size (DARIS) reached                    : ").append(darisReached ? "YES" : "NO").append('\n');
            if (analysisEndpoint)
                log.append(String.format("Analysis-route endpoint (%s x DARIS) reached               : %s%n", Fmt.f(routeEndpoint, 3), finalReached ? "YES" : "NO"));
            log.append("  Theoretical DARIS event-equivalent (Schoenfeld-based)        : ").append(Fmt.ceil0(darisEvents)).append('\n');
            if (analysisEndpoint)
                log.append(String.format("  Theoretical event-equivalent of the analysis-route endpoint%n  (%s x DARIS)                                            : %s%n",
                        Fmt.f(routeEndpoint, 3), Fmt.ceil0(darisEvents * routeEndpoint)));
            if (darisReached) {
                log.append("  Estimated cumulative events at which DARIS information\n  was reached (interpolated, not an observed look)          : ")
                   .append(Fmt.ceil0(darisThresholdEvents)).append('\n');
                if (Math.abs(darisThresholdEvents - darisEvents) > 0.01 * darisEvents)
                    log.append("  (These may differ because the observed study-level information\n"
                            + "   per event differs from the pooled psi*(1-psi) approximation\n"
                            + "   used for the theoretical event-equivalent -- in either\n"
                            + "   direction, not necessarily because information accrued faster.)\n");
            }
            log.append('\n');
            String tgt = analysisEndpoint ? "analysis-route endpoint" : "DARIS";
            if (!analysisEndpoint && !darisReached) {
                log.append("Events accrued = ").append(Fmt.f(eventsAccrued, 0)).append("\n\n");
                log.append("Theoretical additional events to DARIS (Schoenfeld): ").append(Fmt.f(P.additionalEventsRequiredDesign, 0)).append('\n');
                projectionLines(log, P, tgt, centralIPE, "DARIS (historical rate): ~");
            } else if (analysisEndpoint && !finalReached) {
                log.append(String.format("Analysis-route endpoint (%s x DARIS) = %s information units): %s cumulative events%n",
                        Fmt.f(routeEndpoint, 3), Fmt.bigF(routeEndpointInfo, 4), Fmt.bigF(boundaryEndpointEvents, 0)));
                log.append("Additional information required: ").append(Fmt.bigF(P.additionalInfoRequired, 3)).append('\n');
                log.append("Events accrued = ").append(Fmt.f(eventsAccrued, 0)).append('\n');
                log.append("Theoretical additional events to analysis-route endpoint (Schoenfeld): ")
                   .append(Fmt.f(P.additionalEventsTheoretical, 0)).append('\n');
                projectionLines(log, P, tgt, centralIPE, "Historical information/event-rate projection = ~");
            }
            for (String l : Fmt.wrap("Note: The \u03c4\u00b2 estimator may have limited influence on the pooled average effect-size when the "
                    + "evidence base is substantial, but it can materially influence heterogeneity-dependent quantities, prediction "
                    + "intervals, DARIS, and the timing of TSA conclusions\u2014particularly when cumulative information is near the "
                    + "DARIS threshold.", 78)) log.append(l).append('\n');
        }

        buildSummary(R, analysisEndpoint, basisShort);
        R.log = log.toString();
        return R;
    }

    private static void projectionLines(StringBuilder log, TsaHrResult.Projection P, String tgt, double centralIPE, String histPrefix) {
        if (Double.isFinite(P.targetEventsHistoricalRate))
            log.append(histPrefix).append(Fmt.big0(Math.ceil(P.targetEventsHistoricalRate))).append(" cumulative events\n")
               .append("  (").append(Fmt.f(centralIPE, 4)).append(" information units per event)\n");
        if (Double.isNaN(P.additionalEventsEstimated)) {
            log.append("Estimated additional events to ").append(tgt).append(" (historical rate): cannot be estimated\n");
            log.append("  (no study with a usable information-per-event ratio to project from)\n");
        } else {
            log.append("Estimated additional events to ").append(tgt).append(" (historical rate): ~")
               .append(Fmt.big0(Math.ceil(P.additionalEventsEstimated))).append('\n');
        }
        if (Double.isNaN(P.nAdditionalStudies)) {
            log.append("Estimated additional studies required: cannot be estimated\n");
            log.append("  (no usable historical per-study information increment to project from)\n");
        } else {
            log.append("Estimated additional studies required: ").append(Fmt.f(P.nAdditionalStudies, 0)).append('\n');
        }
        if (P.exclusionNote != null) {
            for (String l : Fmt.wrap("Note: " + P.exclusionNote, 78)) log.append(l).append('\n');
            log.append('\n');
        }
        for (String l : Fmt.wrap("Note: " + P.note, 78)) log.append(l).append('\n');
        log.append('\n');
    }

    private static void buildSummary(TsaHrResult R, boolean analysisEndpoint, String basisShort) {
        TsaHrResult.Projection P = R.projection;
        List<String[]> t = new ArrayList<>();
        MetaAnalysis.Fit re = R.resRe;
        t.add(row("Pooled HR (RE, observed)", num(Fmt.round(Math.exp(re.b), 3))));
        t.add(row("95% CI lower", num(Fmt.round(Math.exp(re.ciLb), 3))));
        t.add(row("95% CI upper", num(Fmt.round(Math.exp(re.ciUb), 3))));
        t.add(row("Anticipated HR (for RIS)", num(Fmt.round(R.HRAnticipated, 3))));
        t.add(row("I2 (%)", num(Fmt.round(R.I2, 1))));
        t.add(row("tau2", num(Fmt.round(R.tau2, 4))));
        t.add(row("Diversity D2 (%)", num(Fmt.round(R.D2 * 100, 1))));
        t.add(row("Adjustment factor", num(Fmt.round(R.AF, 3))));
        t.add(row("Allocation psi (treatment-arm prop.)", num(Fmt.round(R.allocationPUsed, 4))));
        t.add(row("Required info (allocation-free)", num(Fmt.round(R.infoRequired, 4))));
        t.add(row("RIS, events (pooled psi)", num(Math.ceil(R.RISEvents))));
        t.add(row("DARIS (info units)", num(Fmt.round(R.DARISInfo, 4))));
        t.add(row("DARIS event-equiv. (Schoenfeld, pooled psi)", num(Math.ceil(R.DARISEvents))));
        t.add(row("Events accrued (reporting scale)", num(R.eventsAccrued)));
        t.add(row("Info accrued (observed inv-var)", num(Fmt.round(R.infoAccruedFinal, 4))));
        t.add(row("% of DARIS reached", num(Fmt.round(100 * R.infoAccruedFinal / R.DARISInfo, 1))));
        t.add(row("Events at DARIS reached (est.)", numCeil(R.DARISInfoThresholdEvents)));
        if (analysisEndpoint)
            t.add(row("Events at AR endpoint (" + Fmt.f(R.routeEndpoint, 3) + "xDARIS) reached (est.)", numCeil(R.routeEndpointEventsEst)));
        if (!analysisEndpoint && !R.darisReached) {
            t.add(row("Add'l events to DARIS (Schoenfeld, theoretical)", num(P.additionalEventsRequiredDesign)));
            t.add(row("DARIS reached, hist. rate (est. events)", numCeil(P.targetEventsHistoricalRate)));
            t.add(row("Add'l events to DARIS (hist. rate; " + basisShort + " IPE)", numCeil(P.additionalEventsEstimated)));
            t.add(row("Add'l studies to DARIS (" + R.settings.projectionStat + "-based proj.)", num(P.nAdditionalStudies)));
            t.add(row("Studies excluded from projection (zero events/ratio)", num(P.nExcludedEventsProjection)));
        } else if (analysisEndpoint && !R.finalReached) {
            t.add(row("Add'l info to AR endpoint (" + Fmt.f(R.routeEndpoint, 3) + "xDARIS)", num(Fmt.round(P.additionalInfoRequired, 4))));
            t.add(row("Add'l events to AR endpoint (Schoenfeld, theoretical)", num(P.additionalEventsTheoretical)));
            t.add(row("AR endpoint reached, hist. rate (est. events)", numCeil(P.targetEventsHistoricalRate)));
            t.add(row("Add'l events to AR endpoint (hist. rate; " + basisShort + " IPE)", numCeil(P.additionalEventsEstimated)));
            t.add(row("Add'l studies to AR endpoint (" + R.settings.projectionStat + "-based proj.)", num(P.nAdditionalStudies)));
            t.add(row("Studies excluded from projection (zero events/ratio)", num(P.nExcludedEventsProjection)));
        }
        t.add(row("Crossed conventional boundary", lg(R.crossedConventional)));
        t.add(row("Crossed TSA boundary (any formal look)", lg(R.crossedTsa)));
        t.add(row("Entered futility region (any look; not a stop decision)", lg(R.enteredFutilityRegion)));
        t.add(row("Definitive look crossed efficacy (NA if not reached)", lg(R.finalCrossedEfficacy)));
        t.add(row("Definitive look: non-efficacy (NA if not reached)", lg(R.finalNonEfficacy)));
        if (!"standard".equals(R.reInference))
            t.add(3, row("Random-effects inference", reInferenceLabel(R.reInference)));
        R.summaryTable = t;
        Map<String, String> ab = new LinkedHashMap<>();
        ab.put("RE", "random effects");
        ab.put("RIS", "Required Information Size");
        ab.put("DARIS", "Diversity-Adjusted RIS");
        ab.put("AR endpoint", "analysis-route endpoint");
        ab.put("psi", "allocation proportion in the treatment arm");
        ab.put("hist. rate", "historical event/information rate");
        ab.put("IPE", "information per event (pooled: total information / total events; otherwise the per-study statistic named, e.g. median)");
        ab.put("proj.", "projection");
        ab.put("inv-var", "inverse-variance");
        ab.put("est.", "estimated");
        ab.put("Add'l", "Additional");
        R.abbreviations = ab;
    }

    private static String[] row(String a, String b) { return new String[]{a, b}; }
    private static String num(double v) { return Fmt.num15(v); }
    private static String numCeil(double v) { return Double.isNaN(v) ? "NA" : Fmt.num15(Math.ceil(v)); }
    private static String lg(Boolean b) { return b == null ? "NA" : (b ? "TRUE" : "FALSE"); }
    private static String trunc(String s, int n) { return s.length() <= n ? s : s.substring(0, n); }

    private static int matchIdx(double[] arr, double v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return -1;
    }
}
