package org.tsahr;
import org.tsahr.core.*;
import org.tsahr.io.DataReader;
import java.io.File;
public class Smoke {
    public static void main(String[] a) throws Exception {
        DataTable t = DataReader.read(new File(a[0]));
        System.out.println(t.columns + " rows=" + t.nrow());
        TsaHrSettings s = new TsaHrSettings();
        s.targetHR = 0.80;
        if (a.length > 1) s.boundaryRoute = a[1];
        long t0 = System.currentTimeMillis();
        TsaHrResult r = TsaHr.run(t, s);
        System.out.println(r.log);
        System.out.println(r.printText());
        System.out.println(r.summaryText());
        System.out.println("WARNINGS: " + r.warnings.size());
        for (String w : r.warnings) System.out.println(" - " + w);
        System.out.println("elapsed ms " + (System.currentTimeMillis() - t0));
    }
}
