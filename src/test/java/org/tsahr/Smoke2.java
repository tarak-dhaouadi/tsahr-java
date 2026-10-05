package org.tsahr;
import org.tsahr.core.*;
import org.tsahr.io.DataReader;
import java.io.File;
public class Smoke2 {
    public static void main(String[] a) throws Exception {
        DataTable t = DataReader.read(new File(a[0]));
        TsaHrSettings s = new TsaHrSettings();
        s.targetHR = Double.parseDouble(a[1]);
        s.boundaryRoute = a[2];
        s.method = a[3];
        s.reInference = a[4];
        if (a.length > 5) { int n = Integer.parseInt(a[5]); while (t.rows.size() > n) t.rows.remove(t.rows.size()-1); }
        long t0 = System.currentTimeMillis();
        TsaHrResult r = TsaHr.run(t, s);
        System.out.println(r.log.substring(r.log.indexOf("=== Trial sequential")));
        System.out.println(r.summaryText());
        for (String w : r.warnings) System.out.println(" WARN: " + w.substring(0, Math.min(140, w.length())));
        System.out.println("root=" + r.designRoot + " rm_bs=" + r.rmBs + " ms=" + (System.currentTimeMillis()-t0));
    }
}
