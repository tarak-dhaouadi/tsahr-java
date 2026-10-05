package org.tsahr;
import org.tsahr.core.*;
import org.tsahr.gui.ChartRenderer;
import org.tsahr.io.DataReader;
import java.io.File;
public class ChartDemo {
    public static void main(String[] a) throws Exception {
        DataTable t = DataReader.read(new File(a[0]));
        int n = Integer.parseInt(a[3]);
        while (t.rows.size() > n) t.rows.remove(t.rows.size() - 1);
        boolean flip = Boolean.parseBoolean(a[1]);
        if (flip) { int c = t.col("log_HR"); for (Object[] r : t.rows) r[c] = -(Double) r[c]; }
        TsaHrSettings s = new TsaHrSettings();
        s.targetHR = flip ? 1.25 : 0.80;
        s.boundaryRoute = a[4];
        TsaHrResult r = TsaHr.run(t, s);
        ChartRenderer.savePng(r, new ChartRenderer.Options(), new File(a[2]), 1300, 800);
        System.out.println("Z last=" + r.cumulative.z[r.cumulative.z.length - 1] + " darisReached=" + r.darisReached);
    }
}
