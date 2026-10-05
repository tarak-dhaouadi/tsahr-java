package org.tsahr.core;

/** Arguments of R's tsa_hr(), with the same names (camelCase) and defaults as tsahr 0.2.8.18. */
public final class TsaHrSettings {
    public double alphaTwoSided = 0.05;
    public double power = 0.80;
    /** "data" or "manual" */
    public String allocationSource = "data";
    public double allocationP = 0.5;
    /** NaN = use the observed pooled HR (circular; exploratory only) */
    public double targetHR = Double.NaN;
    /** DL, HE, HS, HSk, SJ, ML, REML, EB, PM, PMM (CO and VC are accepted as aliases of HE) */
    public String method = "DL";
    /** column used to sort studies chronologically; null = keep row order */
    public String orderBy = null;
    /** "design" or "analysis" */
    public String boundaryRoute = "design";
    /**
     * In R, legacy_fallback = TRUE lets a failed analysis route fall back to the design route
     * (and a failed design route to the legacy R-only engine). The Java edition has no legacy engine;
     * this flag only controls the analysis -> design fallback.
     */
    public boolean legacyFallback = true;
    /** "median" or "mean" */
    public String projectionStat = "median";
    /** "per_study" or "pooled" */
    public String infoPerEventBasis = "per_study";
    /** "standard", "hksj" (alias "knha") or "hksj_adhoc" (alias "knha_adhoc") */
    public String reInference = "standard";
    /** verbose report text (the Java analogue of verbose = TRUE) */
    public boolean verbose = true;

    public TsaHrSettings copy() {
        TsaHrSettings s = new TsaHrSettings();
        s.alphaTwoSided = alphaTwoSided; s.power = power; s.allocationSource = allocationSource;
        s.allocationP = allocationP; s.targetHR = targetHR; s.method = method; s.orderBy = orderBy;
        s.boundaryRoute = boundaryRoute; s.legacyFallback = legacyFallback; s.projectionStat = projectionStat;
        s.infoPerEventBasis = infoPerEventBasis; s.reInference = reInference; s.verbose = verbose;
        return s;
    }
}
