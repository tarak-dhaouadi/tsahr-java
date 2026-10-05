package org.tsahr;
import org.tsahr.gui.MainWindow;
import java.io.File;
public class GuiSmoke {
    public static void main(String[] a) throws Exception {
        MainWindow.launch(new File(a[0]));
        Thread.sleep(2500);
        System.out.println("GUI launched ok");
        System.exit(0);
    }
}
