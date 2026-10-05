package org.tsahr.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Lays out and paints the application panel without a window (headless), to check the GUI layout. */
public class LayoutShot {
    static void all(Component c, List<Component> out) {
        out.add(c);
        if (c instanceof Container) for (Component k : ((Container) c).getComponents()) all(k, out);
    }

    static void shot(AppPanel p, String file, int w, int h) throws Exception {
        p.setSize(w, h);
        List<Component> cs = new ArrayList<>();
        all(p, cs);
        for (int i = 0; i < 3; i++) { for (Component c : cs) c.doLayout(); p.validate(); }
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, w, h);
        p.printAll(g);
        g.dispose();
        javax.imageio.ImageIO.write(img, "png", new File(file));
    }

    public static void main(String[] a) throws Exception {
        System.setProperty("java.awt.headless", "true");
        AppPanel p = new AppPanel();
        p.loadFile(new File("src/test/resources/HR_meta.xlsx"), "HR_meta.xlsx");
        int w = Integer.parseInt(a[0]), h = Integer.parseInt(a[1]);
        p.runBlocking();
        List<Component> cs = new ArrayList<>();
        all(p, cs);
        JTabbedPane tabs = null;
        for (Component c : cs) if (c instanceof JTabbedPane) tabs = (JTabbedPane) c;
        for (int i = 0; i < tabs.getTabCount(); i++) {
            tabs.setSelectedIndex(i);
            shot(p, "/tmp/shot_" + tabs.getTitleAt(i).replace(' ', '_') + ".png", w, h);
        }
        // the chart-options dialog content
        JPanel dlg = new JPanel(new BorderLayout());
        dlg.add(p.chartOptionsPanel(), BorderLayout.CENTER);
        dlg.setSize(560, 250);
        List<Component> dc = new ArrayList<>();
        all(dlg, dc);
        for (int i = 0; i < 3; i++) { for (Component c : dc) c.doLayout(); dlg.validate(); }
        BufferedImage di = new BufferedImage(560, 250, BufferedImage.TYPE_INT_RGB);
        Graphics2D dg = di.createGraphics(); dg.setColor(Color.LIGHT_GRAY); dg.fillRect(0, 0, 560, 250); dlg.printAll(dg); dg.dispose();
        javax.imageio.ImageIO.write(di, "png", new File("/tmp/shot_dialog.png"));
        System.out.println("ok");
    }
}
