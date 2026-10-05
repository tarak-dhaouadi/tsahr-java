package org.tsahr.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Result of {@link TsaHr#run}: the Java counterpart of the "tsa_hr" object returned by R's tsa_hr(). */
public final class TsaHrResult {
    public TsaHrSettings settings;
    public String reInference = "standard";      // canonical value actually used
    public String methodUsed;

    // ---- data (after optional sorting)
    public String[] study;
    public double[] logHR, stdError, eventsTreatment, nTreatment, eventsControls, nControls, totalEvents, totalN;

    // ---- model fits
    public MetaAnalysis.Fit resStd, resFe, resRe;

    // ---- heterogeneity
    public double Q, I2, tau2, QEp, D2Raw, D2, AF;
    public int Qdf;
    public boolean D2Capped;

    // ---- allocation / information size
    public double allocationPUsed;
    public String allocationNote;
    public double zAlpha, zBeta, HRAnticipated, logHRAnticipated, infoRequired, RISEvents, DARISInfo, DARISEvents;
    public String effectSource;
    public boolean circularityWarning, circularitySevere;
    public double DARISInfoThresholdEvents;

    // ---- boundary route
    public String routeUsed;                      // design | analysis
    public String engine;                         // rtsa_design_cpp | rtsa_analysis_cpp (Java port)
    public double routeEndpoint = 1.0;
    public double routeEndpointInfo, routeEndpointEventsEst, boundaryEndpointEvents;
    public boolean fallbackUsed;
    public String fallbackRoute = "none";
    public String fallbackReason;
    public double designRoot = Double.NaN;        // RTSA "root" of the design pass
    public int rmBs;
    public double delta;

    // ---- cumulative table
    public static final class Cumulative {
        public String[] study;
        public double[] estimate, se, z, zval, pval, ciLb, ciUb, tau2, reScale, reDf;
        public double[] cumEvents, cumN, infoAccrued, infoFraction, infoFractionEventBased;
        public double[] boundaryUpper, boundaryLower, futilityUpper, futilityLower;
    }
    public Cumulative cumulative;

    public static final class BoundaryTimeline {
        public double[] infoFraction, cumEvents, boundaryUpper, boundaryLower, futilityUpper, futilityLower;
        public boolean[] synthetic;
    }
    public BoundaryTimeline boundaryTimeline;
    public double[] betaSpentCum, betaSpentIncr;

    // ---- verdicts
    public boolean crossedTsa, crossedConventional, enteredFutilityRegion, finalReached, darisReached;
    public int finalTsaLook;                       // 1-based
    public Boolean finalCrossedEfficacy, finalEnteredFutilityRegion, finalNonEfficacy;   // null = NA
    public double eventsAccrued, infoAccruedFinal;

    // ---- projection
    public static final class Projection {
        public String method, infoPerEventBasis, note;
        public double iRequired, infoAccrued, eventsAccrued, additionalInfoRequired;
        public double centralInfoIncrement, centralEventIncrement, centralInfoPerEvent;
        public double studyLevelInfoPerEvent, pooledInfoPerEvent;
        public double nAdditionalStudies = Double.NaN;
        public double additionalEventsEstimated = Double.NaN, additionalEventsStudyLevel = Double.NaN,
                additionalEventsPooled = Double.NaN, eventsFromWholeStudies = Double.NaN,
                targetEventsHistoricalRate = Double.NaN, additionalEventsRequiredDesign = Double.NaN,
                additionalEventsTheoretical = Double.NaN;
        public int nStudies, nZeroEventStudies, nExcludedEventsProjection;
        public String exclusionNote;
    }
    public Projection projection;

    // ---- tables / text
    public List<String[]> summaryTable = new ArrayList<>();      // {Parameter, Value}
    public Map<String, String> abbreviations = new LinkedHashMap<>();
    public List<String> warnings = new ArrayList<>();
    public String log = "";                                      // verbose console-style report

    // ------------------------------------------------------------------ text outputs
    public String printText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Trial Sequential Analysis (Hazard Ratios)\n");
        sb.append("------------------------------------------\n");
        sb.append(String.format("Studies: %d | Events accrued: %s%n", study.length, Fmt.f(eventsAccrued, 0)));
        sb.append(String.format("Pooled HR (random effects): %s%n", Fmt.f(Math.exp(resRe.b), 3)));
        if (!"standard".equals(reInference))
            sb.append("Random-effects inference: ").append(TsaHr.reInferenceLabel(reInference)).append('\n');
        sb.append(String.format("Anticipated HR (RIS calc): %s%n", Fmt.f(HRAnticipated, 3)));
        sb.append(String.format("Theoretical DARIS event-equivalent: %s%n", Fmt.ceil0(DARISEvents)));
        boolean analysis = "analysis".equals(routeUsed);
        sb.append(String.format("Crossed TSA boundary: %s | Entered futility region: %s | DARIS information reached: %s%n",
                yn(crossedTsa), yn(enteredFutilityRegion), yn(darisReached)));
        if (analysis)
            sb.append(String.format("Boundary route: RTSA analysis | Analysis-route endpoint (%s x DARIS) reached: %s%n",
                    Fmt.f(routeEndpoint, 3), yn(finalReached)));
        if (finalReached)
            sb.append("Definitive look crossed the efficacy boundary: ")
              .append(yn(Boolean.TRUE.equals(finalCrossedEfficacy))).append('\n');
        if ("design".equals(fallbackRoute))
            sb.append("\n*** NOTE: boundary_route = \"analysis\" FAILED; the results shown are the\n")
              .append("    DESIGN-route (RTSA-derived) result -- see the fallback reason below. ***\n")
              .append("    ").append(fallbackReason).append('\n');
        return sb.toString();
    }

    public String summaryText() {
        StringBuilder sb = new StringBuilder();
        int w = 0;
        for (String[] r : summaryTable) w = Math.max(w, r[0].length());
        w = Math.max(w, "Parameter".length());
        sb.append(String.format("%-" + w + "s  %s%n", "Parameter", "Value"));
        for (String[] r : summaryTable) sb.append(String.format("%-" + w + "s  %s%n", r[0], r[1]));
        if (!abbreviations.isEmpty()) {
            sb.append("\nAbbreviations: ");
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, String> e : abbreviations.entrySet()) parts.add(e.getKey() + " = " + e.getValue());
            sb.append(String.join("; ", parts)).append(".\n");
        }
        if ("design".equals(fallbackRoute))
            sb.append("\n*** NOTE: boundary_route = \"analysis\" FAILED; the results shown are the\n")
              .append("    DESIGN-route (RTSA-derived) result. ***\n");
        if (circularityWarning) {
            sb.append("\nNOTE: target_HR was not specified, so the observed pooled HR was used\n");
            sb.append("for the required information size. This is circular -- see the tsahr documentation.\n");
            if (circularitySevere) {
                sb.append("Accrued events also greatly exceed the resulting DARIS, so the TSA\n");
                sb.append("boundary will collapse to the conventional boundary almost immediately.\n");
            }
        }
        return sb.toString();
    }

    /** Tab-separated cumulative table (for export / the GUI). */
    public String cumulativeTsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("Study\tcum_events\tcum_n\testimate\tse\tZ\tpval\tci_lb\tci_ub\ttau2\tinfo_accrued\tinfo_fraction"
                + "\tinfo_fraction_eventbased\tTSA_boundary_upper\tTSA_boundary_lower\tTSA_futility_upper\tTSA_futility_lower\n");
        Cumulative c = cumulative;
        for (int i = 0; i < c.study.length; i++) {
            sb.append(c.study[i]).append('\t')
              .append(Fmt.num15(c.cumEvents[i])).append('\t').append(Fmt.num15(c.cumN[i])).append('\t')
              .append(g(c.estimate[i])).append('\t').append(g(c.se[i])).append('\t').append(g(c.z[i])).append('\t')
              .append(g(c.pval[i])).append('\t').append(g(c.ciLb[i])).append('\t').append(g(c.ciUb[i])).append('\t')
              .append(g(c.tau2[i])).append('\t').append(g(c.infoAccrued[i])).append('\t').append(g(c.infoFraction[i])).append('\t')
              .append(g(c.infoFractionEventBased[i])).append('\t').append(g(c.boundaryUpper[i])).append('\t')
              .append(g(c.boundaryLower[i])).append('\t').append(g(c.futilityUpper[i])).append('\t')
              .append(g(c.futilityLower[i])).append('\n');
        }
        return sb.toString();
    }

    public String boundaryTsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("info_fraction\tcum_events\tTSA_boundary_upper\tTSA_boundary_lower\tTSA_futility_upper\tTSA_futility_lower\tsynthetic\n");
        BoundaryTimeline b = boundaryTimeline;
        for (int i = 0; i < b.infoFraction.length; i++) {
            sb.append(g(b.infoFraction[i])).append('\t').append(g(b.cumEvents[i])).append('\t')
              .append(g(b.boundaryUpper[i])).append('\t').append(g(b.boundaryLower[i])).append('\t')
              .append(g(b.futilityUpper[i])).append('\t').append(g(b.futilityLower[i])).append('\t')
              .append(b.synthetic[i]).append('\n');
        }
        return sb.toString();
    }

    private static String g(double v) {
        if (Double.isNaN(v)) return "NA";
        if (v != 0 && Math.abs(v) < 1e-5) return String.format(java.util.Locale.ROOT, "%.10e", v);   // tiny p-values
        return Fmt.num15(v);
    }
    private static String yn(boolean b) { return b ? "YES" : "NO"; }
}
