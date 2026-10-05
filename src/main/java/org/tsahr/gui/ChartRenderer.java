package org.tsahr.gui;

import org.tsahr.core.Fmt;
import org.tsahr.core.TsaHrResult;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

/**
 * Draws the TSA chart (cumulative Z-curve, alpha and non-binding futility boundaries, naive boundaries,
 * DARIS markers, projection) with plain Java2D. Layers, colours and labels follow plot.tsa_hr() of the R package;
 * the look (white panel, fonts, spacing, line styles) is that of tsacor-java's chart.
 *
 * <p>The chart is laid out on a fixed 1100 x 750 logical canvas (an 11 x 7.5 inch page at 100 px per inch, font
 * sizes given in points and converted with 100/72) and scaled uniformly to whatever area it is painted into, so
 * the proportions and the relative size of every text are the same on screen, in a PNG of any size and in the
 * high-resolution export.
 */
public final class ChartRenderer {
    private ChartRenderer() {}

    /** Counterparts of plot.tsa_hr()'s drawing arguments. */
    public static final class Options {
        public boolean legend = true, caption = true, showTheoreticalDaris = true, showHistoricalDaris = true;
        public double xmaxMult = 1.15;
        public Color alphaCol = new Color(0xB2, 0x22, 0x22);     // firebrick
        public Color betaCol = Color.BLUE;
        public Color naiveCol = new Color(0x00, 0x64, 0x00);      // darkgreen
        public Color zCol = Color.BLACK;
        /**
         * Placement of the DARIS-type labels: "auto" (default) puts them in the lower part of the plot when the
         * Z-curve is positive and in the upper part when it is negative (the "Events accrued" label goes just above
         * the Z-curve in the first case and to the bottom in the second); "upper" / "lower" force the DARIS labels
         * to that side.
         */
        public String labelPlacement = "auto";
        /** Size of the DARIS / events-accrued labels in ggplot units (plot.tsa_hr's daris_label_size etc.; default 3.2). */
        public double labelFontSize = 3.2;
        /** Size of the methods caption in points (plot.tsa_hr's caption_size; default 8). */
        public double captionFontSize = 8.0;
    }

    /** Design resolution (logical pixels): 11 x 7.5 inches at 100 px/inch. */
    public static final int LOGICAL_W = 1100, LOGICAL_H = 750;
    private static final double PT = 100.0 / 72.0;      // logical pixels per typographic point
    private static final double MM = 2.845276;          // ggplot2 text size (mm) -> points

    private static final Color GREY35 = new Color(89, 89, 89), GREY60 = new Color(153, 153, 153),
            STEELBLUE4 = new Color(54, 100, 139), DARKORANGE3 = new Color(205, 102, 0), PURPLE4 = new Color(85, 26, 139);

    public static BufferedImage render(TsaHrResult r, Options o, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        paint(g, r, o, w, h);
        g.dispose();
        return img;
    }

    public static void savePng(TsaHrResult r, Options o, File f, int w, int h) throws IOException {
        ImageIO.write(render(r, o, w, h), "png", f);
    }

    /** Paints the chart scaled (uniformly, centred) into a W x H area. */
    public static void paint(Graphics2D g0, TsaHrResult r, Options o, int W, int H) {
        Graphics2D g = (Graphics2D) g0.create();
        double s = Math.min(W / (double) LOGICAL_W, H / (double) LOGICAL_H);
        g.translate((W - LOGICAL_W * s) / 2.0, (H - LOGICAL_H * s) / 2.0);
        g.scale(s, s);
        draw(g, LOGICAL_W, LOGICAL_H, r, o);
        g.dispose();
    }

    // ------------------------------------------------------------------ drawing helpers
    private static Font font(int style, double pt) {
        return new Font(Font.SANS_SERIF, style, 1).deriveFont(style, (float) (pt * PT));
    }

    /** Draw text; hjust 0 = left, 1 = right; vjust 0 = baseline at y (text above), 1 = top at y (text below). */
    private static void text(Graphics2D g, String s, double x, double y, double hjust, double vjust) {
        FontMetrics fm = g.getFontMetrics();
        double w = fm.stringWidth(s);
        g.drawString(s, (float) (x - hjust * w), (float) (y + vjust * fm.getAscent()));
    }

    private static Stroke stroke(double widthPx, String type) {
        float w = (float) widthPx;
        switch (type) {
            case "dashed": return new BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{4 * w, 4 * w}, 0f);
            case "dotted": return new BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{w, 3 * w}, 0f);
            case "dotdash": return new BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{w, 3 * w, 4 * w, 3 * w}, 0f);
            case "longdash": return new BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{7 * w, 3 * w}, 0f);
            default: return new BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND);
        }
    }

    /** Tick positions; the last element of the returned array carries the step. */
    static double[] niceTicks(double lo, double hi, int target) {
        double range = hi - lo;
        double raw = range / Math.max(1, target);
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        double res = raw / mag;
        double step = (res < 1.5 ? 1 : res < 3.5 ? 2 : res < 7.5 ? 5 : 10) * mag;
        List<Double> t = new ArrayList<>();
        for (double v = Math.ceil(lo / step - 1e-9) * step; v <= hi + 1e-9 * step; v += step) t.add(Math.abs(v) < 1e-12 * step ? 0.0 : v);
        double[] a = new double[t.size() + 1];
        for (int i = 0; i < t.size(); i++) a[i] = t.get(i);
        a[a.length - 1] = step;
        return a;
    }

    private static String tickLabel(double v, double step) {
        if (step >= 1 && Math.abs(v - Math.rint(v)) < 1e-9) return Long.toString((long) Math.rint(v));
        int dec = (int) Math.max(0, Math.ceil(-Math.log10(step) - 1e-9));
        return String.format(Locale.ROOT, "%." + dec + "f", v);
    }

    private static double maxOf(double[] a) { double m = Double.NEGATIVE_INFINITY; for (double v : a) m = Math.max(m, v); return m; }

    private static double[] clipHigh(double[] v, double lim) {
        double[] o = v.clone();
        for (int i = 0; i < o.length; i++) if (!Double.isNaN(o[i])) o[i] = Math.min(o[i], lim);
        return o;
    }

    private static double[] clipLow(double[] v, double lim) {
        double[] o = v.clone();
        for (int i = 0; i < o.length; i++) if (!Double.isNaN(o[i])) o[i] = Math.max(o[i], -lim);
        return o;
    }

    private static void vline(Graphics2D g, double xPx, double y0, double h) {
        g.draw(new Line2D.Double(xPx, y0, xPx, y0 + h));
    }

    /** Label to the right of its line (hjust = -0.05 in R); flipped to the left when it would leave the canvas. */
    private static void lineLabel(Graphics2D g, String s, double xPx, double yPx, double vjust, double canvasRight) {
        double w = g.getFontMetrics().stringWidth(s);
        double gap = 0.05 * w;
        if (xPx + gap + w > canvasRight) text(g, s, xPx - gap, yPx, 1, vjust);
        else text(g, s, xPx + gap, yPx, 0, vjust);
    }

    private static void drawSeries(Graphics2D g, double[] xs, double[] ys, DoubleUnaryOperator px, DoubleUnaryOperator py,
                                   Color c, Stroke st) {
        Path2D.Double path = new Path2D.Double();
        boolean started = false;
        for (int i = 0; i < xs.length && i < ys.length; i++) {
            if (!Double.isFinite(xs[i]) || !Double.isFinite(ys[i])) continue;   // na.rm = TRUE: connect remaining points
            double X = px.applyAsDouble(xs[i]), Y = py.applyAsDouble(ys[i]);
            if (!started) { path.moveTo(X, Y); started = true; } else path.lineTo(X, Y);
        }
        if (!started) return;
        g.setColor(c); g.setStroke(st);
        g.draw(path);
    }

    // ================================================================== main draw
    static void draw(Graphics2D g, double W, double H, TsaHrResult r, Options o) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

        TsaHrResult.Cumulative C = r.cumulative;
        TsaHrResult.BoundaryTimeline T = r.boundaryTimeline;
        final int nrow = C.z.length;
        final boolean analysisRoute = "analysis".equals(r.routeUsed);
        final double routeEndpoint = r.routeEndpoint;
        final boolean showEndpointMarker = analysisRoute && r.finalReached && Double.isFinite(r.routeEndpointEventsEst);
        final double endpointTheo = r.DARISEvents * routeEndpoint;
        final boolean showEndpointTheoretical = analysisRoute && !showEndpointMarker && Double.isFinite(endpointTheo)
                && !(Math.abs(routeEndpoint - 1) < 1.5e-8 && o.showTheoreticalDaris);
        final boolean showInfoMarker = r.darisReached && Double.isFinite(r.DARISInfoThresholdEvents);
        final double histTarget = r.projection == null ? Double.NaN : r.projection.targetEventsHistoricalRate;
        final boolean histReached = analysisRoute ? r.finalReached : r.darisReached;
        final boolean showHist = o.showHistoricalDaris && !histReached && Double.isFinite(histTarget);

        // ---- limits
        double yAbs = 0;
        for (double z : C.z) if (Double.isFinite(z)) yAbs = Math.max(yAbs, Math.abs(z));
        for (double b : T.boundaryUpper) if (Double.isFinite(b)) yAbs = Math.max(yAbs, Math.abs(b));
        if (yAbs == 0) yAbs = 1;
        final double yLim = yAbs * 1.15;
        double lastZ = Double.NaN;                          // sign of the Z-curve = sign of its last defined value
        for (double z : C.z) if (Double.isFinite(z)) lastZ = z;
        final boolean lower = "lower".equals(o.labelPlacement) || ("auto".equals(o.labelPlacement) && Double.isFinite(lastZ) && lastZ > 0);

        double m = 0;
        for (double v : C.cumEvents) m = Math.max(m, v);
        if (o.showTheoreticalDaris) m = Math.max(m, r.DARISEvents);
        if (showInfoMarker) m = Math.max(m, r.DARISInfoThresholdEvents);
        if (showEndpointMarker) m = Math.max(m, r.routeEndpointEventsEst);
        if (showEndpointTheoretical) m = Math.max(m, endpointTheo);
        if (showHist) m = Math.max(m, histTarget);
        for (double v : T.cumEvents) if (Double.isFinite(v)) m = Math.max(m, v);
        final double xmax = m * o.xmaxMult;

        // ---- text blocks
        String title = "Trial Sequential Analysis of Hazard Ratios";
        String sub1 = "Random-effects model | Diversity D\u00b2 = " + Fmt.f(r.D2 * 100, 0) + "% | Anticipated HR = " + Fmt.f(r.HRAnticipated, 2)
                + " | psi = " + Fmt.f(r.allocationPUsed, 3) + " | alpha=" + Fmt.f(r.settings.alphaTwoSided * 100, 0)
                + "%, power=" + Fmt.f(r.settings.power * 100, 0) + "%";
        String sub2 = pooledSubtitle(r);
        List<String> capLines = o.caption ? captionLines(r, analysisRoute) : new ArrayList<String>();

        // ---- layout (same constants as tsacor-java)
        Font titleF = font(Font.PLAIN, 14.4), subF = font(Font.PLAIN, 11), axisF = font(Font.PLAIN, 9.6),
                axisTitleF = font(Font.PLAIN, 12), legendF = font(Font.PLAIN, 9.6), capF = font(Font.ITALIC, o.captionFontSize);
        double left = 72, right = 36, top = 14;
        FontMetrics fmT = g.getFontMetrics(titleF), fmS = g.getFontMetrics(subF), fmA = g.getFontMetrics(axisF),
                fmAT = g.getFontMetrics(axisTitleF), fmL = g.getFontMetrics(legendF), fmC = g.getFontMetrics(capF);
        double yTitle = top + fmT.getAscent();
        double yS1 = yTitle + fmT.getDescent() + 7 + fmS.getAscent();
        double yS2 = yS1 + fmS.getHeight() * 1.05;
        double panelTop = yS2 + fmS.getDescent() + 10;
        double capH = capLines.isEmpty() ? 0 : capLines.size() * fmC.getHeight() * 1.05 + 10;
        double legendH = o.legend ? 26 : 0;
        double bottomBlock = fmA.getHeight() + 6 + fmAT.getHeight() + 6 + legendH + capH + 8;
        double panelBottom = H - bottomBlock;
        double pw = W - left - right, ph = panelBottom - panelTop;
        final double X0 = left, Y0 = panelTop;
        DoubleUnaryOperator px = v -> X0 + v / xmax * pw;
        DoubleUnaryOperator py = v -> Y0 + (yLim - v) / (2 * yLim) * ph;

        // ---- panel: grid (white background, no frame)
        double[] xt = niceTicks(0, xmax, 6), yt = niceTicks(-yLim, yLim, 6);
        double xStep = xt[xt.length - 1], yStep = yt[yt.length - 1];
        Shape oldClip = g.getClip();
        g.setClip(new Rectangle2D.Double(X0, Y0, pw, ph));
        g.setColor(new Color(235, 235, 235));
        g.setStroke(new BasicStroke((float) (0.25 * PT * 0.75)));
        for (double v = 0; v <= xmax; v += xStep / 2) g.draw(new Line2D.Double(px.applyAsDouble(v), Y0, px.applyAsDouble(v), Y0 + ph));
        for (double v = Math.ceil(-yLim / (yStep / 2)) * (yStep / 2); v <= yLim; v += yStep / 2)
            g.draw(new Line2D.Double(X0, py.applyAsDouble(v), X0 + pw, py.applyAsDouble(v)));
        g.setColor(new Color(220, 220, 220));
        g.setStroke(new BasicStroke((float) (0.5 * PT * 0.75)));
        for (int i = 0; i < xt.length - 1; i++) g.draw(new Line2D.Double(px.applyAsDouble(xt[i]), Y0, px.applyAsDouble(xt[i]), Y0 + ph));
        for (int i = 0; i < yt.length - 1; i++) g.draw(new Line2D.Double(X0, py.applyAsDouble(yt[i]), X0 + pw, py.applyAsDouble(yt[i])));

        // zero line
        g.setColor(GREY60);
        g.setStroke(new BasicStroke((float) (0.3 * PT * 0.75)));
        g.draw(new Line2D.Double(X0, py.applyAsDouble(0), X0 + pw, py.applyAsDouble(0)));

        double lw = 0.8 * PT * 0.75 * 2.13;       // ggplot linewidth 0.8 mm
        double lwThin = 0.5 * PT * 0.75 * 2.13;
        double lwRef = 0.6 * PT * 0.75 * 2.13;
        // naive boundaries
        g.setColor(o.naiveCol); g.setStroke(stroke(lwThin, "dashed"));
        g.draw(new Line2D.Double(px.applyAsDouble(0), py.applyAsDouble(r.zAlpha), px.applyAsDouble(xmax), py.applyAsDouble(r.zAlpha)));
        g.draw(new Line2D.Double(px.applyAsDouble(0), py.applyAsDouble(-r.zAlpha), px.applyAsDouble(xmax), py.applyAsDouble(-r.zAlpha)));
        // alpha / futility boundaries (alpha clipped to +-yLim as in R)
        drawSeries(g, T.cumEvents, clipHigh(T.boundaryUpper, yLim), px, py, o.alphaCol, stroke(lw, "solid"));
        drawSeries(g, T.cumEvents, clipLow(T.boundaryLower, yLim), px, py, o.alphaCol, stroke(lw, "solid"));
        drawSeries(g, T.cumEvents, T.futilityUpper, px, py, o.betaCol, stroke(lw, "dashed"));
        drawSeries(g, T.cumEvents, T.futilityLower, px, py, o.betaCol, stroke(lw, "dashed"));
        // Z-curve
        drawSeries(g, C.cumEvents, C.z, px, py, o.zCol, stroke(lw, "solid"));
        g.setColor(o.zCol);
        double rad = 2.0 * PT * 0.75 * 1.9 / 2 + 1.2;
        for (int i = 0; i < nrow; i++) if (Double.isFinite(C.z[i]))
            g.fill(new Ellipse2D.Double(px.applyAsDouble(C.cumEvents[i]) - rad, py.applyAsDouble(C.z[i]) - rad, 2 * rad, 2 * rad));

        // ---- reference lines
        if (o.showTheoreticalDaris) { g.setColor(Color.BLACK); g.setStroke(stroke(lwRef, "dotted")); vline(g, px.applyAsDouble(r.DARISEvents), Y0, ph); }
        if (showHist) { g.setColor(DARKORANGE3); g.setStroke(stroke(lwRef, "dotdash")); vline(g, px.applyAsDouble(histTarget), Y0, ph); }
        if (showInfoMarker) { g.setColor(GREY35); g.setStroke(stroke(lwRef, "dashed")); vline(g, px.applyAsDouble(r.DARISInfoThresholdEvents), Y0, ph); }
        if (showEndpointMarker) { g.setColor(PURPLE4); g.setStroke(stroke(lwRef, "longdash")); vline(g, px.applyAsDouble(r.routeEndpointEventsEst), Y0, ph); }
        if (showEndpointTheoretical) { g.setColor(PURPLE4); g.setStroke(stroke(lwRef, "longdash")); vline(g, px.applyAsDouble(endpointTheo), Y0, ph); }
        g.setClip(oldClip);

        // ---- labels (DARIS block placement depends on the sign of the Z-curve)
        final double sgn = lower ? -1.0 : 1.0;
        final double vj = lower ? 1.0 : 0.0;       // top-aligned when the block sits in the lower part
        final double canvasRight = W - 4;
        g.setFont(font(Font.PLAIN, o.labelFontSize * MM));
        if (o.showTheoreticalDaris) {
            g.setColor(Color.BLACK);
            lineLabel(g, "Theoretical DARIS event-equivalent ~ " + Fmt.ceil0(r.DARISEvents),
                    px.applyAsDouble(r.DARISEvents), py.applyAsDouble(sgn * yLim * 0.92), vj, canvasRight);
        }
        if (showHist) {
            g.setColor(DARKORANGE3);
            String t = analysisRoute ? "Historical information/event-rate projection ~ " + Fmt.ceil0(histTarget) + " events"
                    : "DARIS (historical rate) ~ " + Fmt.ceil0(histTarget) + " events (projected)";
            lineLabel(g, t, px.applyAsDouble(histTarget), py.applyAsDouble(sgn * yLim * (analysisRoute ? 0.41 : 0.75)), vj, canvasRight);
        }
        if (showInfoMarker) {
            g.setColor(GREY35);
            lineLabel(g, "DARIS information reached ~ " + Fmt.ceil0(r.DARISInfoThresholdEvents) + " events (est.)",
                    px.applyAsDouble(r.DARISInfoThresholdEvents), py.applyAsDouble(sgn * yLim * 0.75), vj, canvasRight);
        }
        if (showEndpointMarker || showEndpointTheoretical) {
            double xe = showEndpointMarker ? r.routeEndpointEventsEst : endpointTheo;
            String t = showEndpointMarker
                    ? "Analysis-route endpoint (" + Fmt.f(routeEndpoint, 3) + " x DARIS) reached ~ " + Fmt.ceil0(r.routeEndpointEventsEst) + " events (est.)"
                    : "Analysis-route endpoint (" + Fmt.f(routeEndpoint, 3) + " x DARIS) not yet reached; theoretical ~ " + Fmt.ceil0(endpointTheo) + " events";
            g.setColor(PURPLE4);
            lineLabel(g, t, px.applyAsDouble(xe), py.applyAsDouble(sgn * yLim * 0.58), vj, canvasRight);
        }
        // events accrued, right-aligned at the last cumulative events
        {
            g.setColor(STEELBLUE4);
            String t = "Events accrued = " + Fmt.f(r.eventsAccrued, 0);
            double xr = maxOf(C.cumEvents);
            double yBase;                                   // data coordinate of the text baseline
            if (lower) {                                    // just above the Z-curve around the label's horizontal extent
                double wData = g.getFontMetrics().stringWidth(t) / pw * xmax;
                double zTop = Double.NEGATIVE_INFINITY;
                for (int i = 0; i < nrow; i++)
                    if (Double.isFinite(C.z[i]) && C.cumEvents[i] >= xr - wData - 0.01 * xmax && C.cumEvents[i] <= xr + 0.01 * xmax)
                        zTop = Math.max(zTop, C.z[i]);
                if (Double.isInfinite(zTop)) zTop = Double.isFinite(lastZ) ? lastZ : 0;
                yBase = Math.min(zTop + 0.035 * yLim, 0.95 * yLim);
            } else {
                yBase = -yLim * 0.92;
            }
            text(g, t, px.applyAsDouble(xr), py.applyAsDouble(yBase), 1, 0);
        }

        // ---- title, subtitle, axes, legend, caption
        g.setColor(Color.BLACK);
        g.setFont(titleF);
        text(g, title, left - 62, yTitle, 0, 0);
        g.setFont(subF);
        g.setColor(new Color(40, 40, 40));
        text(g, sub1, left - 62, yS1, 0, 0);
        text(g, sub2, left - 62, yS2, 0, 0);

        g.setFont(axisF); g.setColor(new Color(77, 77, 77));
        for (int i = 0; i < xt.length - 1; i++)
            text(g, tickLabel(xt[i], xStep), px.applyAsDouble(xt[i]), panelBottom + 4 + fmA.getAscent(), 0.5, 0);
        for (int i = 0; i < yt.length - 1; i++)
            text(g, tickLabel(yt[i], yStep), left - 6, py.applyAsDouble(yt[i]) + fmA.getAscent() * 0.36, 1, 0);
        g.setFont(axisTitleF); g.setColor(Color.BLACK);
        double yAxisTitle = panelBottom + 4 + fmA.getHeight() + 6 + fmAT.getAscent();
        text(g, "Cumulative number of events", left + pw / 2, yAxisTitle, 0.5, 0);
        Graphics2D gr = (Graphics2D) g.create();
        gr.setFont(axisTitleF);
        gr.rotate(-Math.PI / 2, 16, panelTop + ph / 2);
        text(gr, "Cumulative Z-score", 16, panelTop + ph / 2 + fmAT.getAscent() / 2.0, 0.5, 0);
        gr.dispose();

        double yNext = yAxisTitle + fmAT.getDescent() + 8;
        if (o.legend) {
            String[] names = {"Alpha boundaries", "Non-binding futility boundaries", "Naive boundaries", "Z scores"};
            Color[] cols = {o.alphaCol, o.betaCol, o.naiveCol, o.zCol};
            String[] types = {"solid", "dashed", "dashed", "solid"};
            g.setFont(legendF);
            double total = 0;
            for (String n : names) total += 30 + 6 + fmL.stringWidth(n) + 18;
            double lx = left + pw / 2 - total / 2 + 9, ly = yNext + 10;
            for (int i = 0; i < names.length; i++) {
                g.setColor(cols[i]); g.setStroke(stroke(lw, types[i]));
                g.draw(new Line2D.Double(lx, ly, lx + 30, ly));
                if (i == 3) { double rr = 3.2; g.fill(new Ellipse2D.Double(lx + 15 - rr, ly - rr, 2 * rr, 2 * rr)); }
                g.setColor(new Color(20, 20, 20));
                text(g, names[i], lx + 36, ly + fmL.getAscent() * 0.36, 0, 0);
                lx += 30 + 6 + fmL.stringWidth(names[i]) + 18;
            }
            yNext += legendH;
        }
        if (!capLines.isEmpty()) {
            g.setFont(capF); g.setColor(new Color(40, 40, 40));
            double cy = yNext + 6 + fmC.getAscent();
            for (String line : capLines) { text(g, line, left - 62, cy, 0, 0); cy += fmC.getHeight() * 1.05; }
        }
    }

    // ------------------------------------------------------------------ helpers
    public static String pooledSubtitle(TsaHrResult r) {
        double pv = r.resRe.pval;
        String p = Double.isNaN(pv) ? "p = NA" : pv < 0.001 ? "p < 0.001" : "p = " + Fmt.f(pv, 3);
        return String.format("Pooled HR = %s [95%% CI: %s, %s] | %s | Tau\u00b2 = %s | I\u00b2 = %s%%",
                Fmt.f(Math.exp(r.resRe.b), 2), Fmt.f(Math.exp(r.resRe.ciLb), 2), Fmt.f(Math.exp(r.resRe.ciUb), 2), p,
                Fmt.f(r.tau2, 4), Fmt.f(r.I2, 1));
    }

    static List<String> captionLines(TsaHrResult r, boolean analysisRoute) {
        List<String> l = new ArrayList<>();
        l.add(String.format("Methods: Random-effects (%s) model, allocation psi = %s",
                org.tsahr.core.TsaHr.methodLabel(r.methodUsed), Fmt.f(r.allocationPUsed, 3)));
        if (!"standard".equals(r.reInference)) {
            l.add("hksj_adhoc".equals(r.reInference)
                    ? "Random-effects inference: HKSJ with ad hoc correction (variance scale max(1, q); t distribution, k-1 df); Z = normal-equivalent of the t-statistic"
                    : "Random-effects inference: HKSJ (Hartung-Knapp-Sidik-Jonkman; t distribution, k-1 df); Z = normal-equivalent of the t-statistic");
        }
        l.add("Alpha spending: O'Brien-Fleming-type (asOF); Non-binding futility: RTSA-reconstructed recursive-integration engine, O'Brien-Fleming-type beta-spending (bsOF)");
        l.add(String.format("alpha = %s%% (two-sided), power = %s%% | Diversity D\u00b2 = %s%%, Adjustment factor = %s",
                Fmt.f(r.settings.alphaTwoSided * 100, 0), Fmt.f(r.settings.power * 100, 0), Fmt.f(r.D2 * 100, 0), Fmt.f(r.AF, 2)));
        if (analysisRoute)
            l.add("Boundary route: RTSA analysis (formal endpoint = " + Fmt.f(r.routeEndpoint, 3) + " x DARIS information)");
        TsaHrResult.Projection P = r.projection;
        boolean show = analysisRoute ? !r.finalReached : !r.darisReached;
        if (P != null && show) {
            String target = analysisRoute ? "analysis-route endpoint" : "DARIS";
            double theo = analysisRoute ? P.additionalEventsTheoretical : P.additionalEventsRequiredDesign;
            List<String> parts = new ArrayList<>();
            if (Double.isFinite(theo)) parts.add("Theoretical additional events to " + target + " (Schoenfeld): " + Fmt.big0(Math.ceil(theo)));
            if (Double.isFinite(P.additionalEventsEstimated))
                parts.add("Estimated additional events to " + target + " (historical rate): ~" + Fmt.big0(Math.ceil(P.additionalEventsEstimated)));
            if (!parts.isEmpty()) l.add(String.join(", ", parts));
            if (Double.isFinite(P.nAdditionalStudies)) l.add("Estimated additional studies required: " + Fmt.f(P.nAdditionalStudies, 0));
            if (P.nExcludedEventsProjection > 0) {
                int ne = P.nExcludedEventsProjection;
                l.add(ne == P.nZeroEventStudies
                        ? ne + " zero-event " + (ne == 1 ? "study" : "studies") + " excluded from the events projection"
                        : ne + " " + (ne == 1 ? "study" : "studies") + " excluded from the events projection (zero events or unusable ratio)");
            }
        }
        return l;
    }
}
