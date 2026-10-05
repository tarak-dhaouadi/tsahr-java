package org.tsahr.gui;

import org.tsahr.cli.Main;

import javax.swing.*;
import java.awt.*;
import java.io.File;

/** The application window: a frame around {@link AppPanel}. */
public final class MainWindow extends JFrame {
    private MainWindow(AppPanel panel) {
        super("tsahr-java " + Main.VERSION + " -- Trial Sequential Analysis for hazard ratios");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
        setSize(Math.min(1380, scr.width - 40), Math.min(940, scr.height - 60));
        setLocationRelativeTo(null);
        setJMenuBar(panel.menu());
        setContentPane(panel);
    }

    public static void launch(File preload) {
        SwingUtilities.invokeLater(() -> {
            AppPanel panel = new AppPanel();
            MainWindow w = new MainWindow(panel);
            w.setVisible(true);
            if (preload != null) panel.loadFile(preload, null);
        });
    }
}
