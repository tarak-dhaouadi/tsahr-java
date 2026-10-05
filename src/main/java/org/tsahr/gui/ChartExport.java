package org.tsahr.gui;

import org.tsahr.core.TsaHrResult;
import org.w3c.dom.Node;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.SampleModel;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.util.Vector;

/**
 * High-resolution PNG export of the TSA chart: an 11 x 7.5 inch page rendered at 150 / 300 / 600 / 1200 dpi
 * (1200 dpi = 13,200 x 9,000 px). The chart is drawn on a 792 x 540 point canvas and scaled to the requested dpi, so
 * fonts keep their physical size (labels ~9 pt, caption 8 pt, by default) at every resolution. The image is
 * produced in horizontal strips while the PNG is written, so memory stays small even at 1200 dpi.
 * The dpi is stored in the file (pHYs chunk) so that Word, PowerPoint, journals' upload systems etc. size it correctly.
 */
public final class ChartExport {
    private ChartExport() {}

    public static final double WIDTH_IN = 11.0, HEIGHT_IN = 7.5;
    public static final int[] DPIS = {150, 300, 600, 1200};
    private static final double POINTS_PER_INCH = 72.0;

    public static int widthPx(int dpi) { return (int) Math.round(WIDTH_IN * dpi); }
    public static int heightPx(int dpi) { return (int) Math.round(HEIGHT_IN * dpi); }

    /** "3,300 x 2,250 px" */
    public static String sizeText(int dpi) {
        return String.format(java.util.Locale.ROOT, "%,d x %,d px", widthPx(dpi), heightPx(dpi));
    }

    /** Text of the resolution dialog. */
    public static String dialogMessage() {
        return "Resolution (dpi) of the 11 x 7.5 inch chart:\n"
                + "300 dpi = " + sizeText(300) + ";  600 dpi = " + sizeText(600) + ";\n"
                + "1200 dpi = " + sizeText(1200) + " (large file, takes several seconds).";
    }

    public static void savePng(TsaHrResult r, ChartRenderer.Options o, File f, int dpi) throws IOException {
        if (dpi < 30 || dpi > 2400) throw new IllegalArgumentException("dpi must be between 30 and 2400");
        Strips img = new Strips(r, o, dpi);
        ImageWriter w = ImageIO.getImageWritersByFormatName("png").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(f)) {
            ImageWriteParam p = w.getDefaultWriteParam();
            IIOMetadata md = w.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(img), p);
            IIOMetadataNode phys = new IIOMetadataNode("pHYs");
            String ppm = Integer.toString((int) Math.round(dpi / 0.0254));
            phys.setAttribute("pixelsPerUnitXAxis", ppm);
            phys.setAttribute("pixelsPerUnitYAxis", ppm);
            phys.setAttribute("unitSpecifier", "meter");
            IIOMetadataNode root = new IIOMetadataNode("javax_imageio_png_1.0");
            root.appendChild(phys);
            md.mergeTree("javax_imageio_png_1.0", root);
            w.setOutput(out);
            w.write(null, new IIOImage(img, null, md), p);
        } finally {
            w.dispose();
        }
    }

    /** A RenderedImage whose pixels are drawn on demand, one full-width strip (tile) at a time. */
    private static final class Strips implements RenderedImage {
        private final TsaHrResult r;
        private final ChartRenderer.Options o;
        private final int dpi, w, h, tileH;
        private final BufferedImage proto;
        private int cachedTy = -1;
        private BufferedImage cached;

        Strips(TsaHrResult r, ChartRenderer.Options o, int dpi) {
            this.r = r; this.o = o; this.dpi = dpi;
            w = widthPx(dpi); h = heightPx(dpi);
            int t = Math.min(128, h);
            while (h % t != 0) t--;               // tiles divide the height exactly
            tileH = t;
            proto = new BufferedImage(w, tileH, BufferedImage.TYPE_INT_RGB);
        }

        private synchronized BufferedImage strip(int ty) {
            if (ty == cachedTy) return cached;
            BufferedImage bi = new BufferedImage(w, tileH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = bi.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, tileH);
            g.translate(0, -ty * tileH);
            double s = dpi / POINTS_PER_INCH;
            g.scale(s, s);
            ChartRenderer.paint(g, r, o, (int) Math.round(WIDTH_IN * POINTS_PER_INCH), (int) Math.round(HEIGHT_IN * POINTS_PER_INCH));
            g.dispose();
            cached = bi; cachedTy = ty;
            return bi;
        }

        @Override public Vector<RenderedImage> getSources() { return null; }
        @Override public Object getProperty(String name) { return java.awt.Image.UndefinedProperty; }
        @Override public String[] getPropertyNames() { return null; }
        @Override public ColorModel getColorModel() { return proto.getColorModel(); }
        @Override public SampleModel getSampleModel() { return proto.getSampleModel(); }
        @Override public int getWidth() { return w; }
        @Override public int getHeight() { return h; }
        @Override public int getMinX() { return 0; }
        @Override public int getMinY() { return 0; }
        @Override public int getNumXTiles() { return 1; }
        @Override public int getNumYTiles() { return h / tileH; }
        @Override public int getMinTileX() { return 0; }
        @Override public int getMinTileY() { return 0; }
        @Override public int getTileWidth() { return w; }
        @Override public int getTileHeight() { return tileH; }
        @Override public int getTileGridXOffset() { return 0; }
        @Override public int getTileGridYOffset() { return 0; }

        @Override public Raster getTile(int tx, int ty) {
            return strip(ty).getRaster().createChild(0, 0, w, tileH, 0, ty * tileH, null);
        }

        @Override public Raster getData() { return getData(new Rectangle(0, 0, w, h)); }

        @Override public Raster getData(Rectangle rect) {
            WritableRaster dest = Raster.createWritableRaster(
                    getSampleModel().createCompatibleSampleModel(rect.width, rect.height), new Point(rect.x, rect.y));
            copyInto(dest);
            return dest;
        }

        @Override public WritableRaster copyData(WritableRaster dest) {
            if (dest == null)
                dest = Raster.createWritableRaster(getSampleModel().createCompatibleSampleModel(w, h), new Point(0, 0));
            copyInto(dest);
            return dest;
        }

        private void copyInto(WritableRaster dest) {
            Rectangle b = dest.getBounds();
            int first = Math.max(0, b.y / tileH), last = Math.min(h / tileH - 1, (b.y + b.height - 1) / tileH);
            for (int ty = first; ty <= last; ty++) dest.setRect(getTile(0, ty));
        }
    }
}
