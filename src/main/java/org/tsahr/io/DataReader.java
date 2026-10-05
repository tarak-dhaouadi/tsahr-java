package org.tsahr.io;

import org.tsahr.core.DataTable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads the study table from an .xlsx workbook (first worksheet, header in row 1, like
 * readxl::read_excel()) or from a delimited text file (.csv / .tsv / .txt).
 * No third-party libraries: an .xlsx file is a zip of XML parts and is parsed with the JDK's own XML API.
 */
public final class DataReader {
    private DataReader() {}

    public static DataTable read(File f) throws IOException {
        String n = f.getName().toLowerCase();
        if (n.endsWith(".xlsx") || n.endsWith(".xlsm")) return readXlsx(f);
        if (n.endsWith(".csv") || n.endsWith(".tsv") || n.endsWith(".txt")) return readDelimited(f);
        throw new IOException("Unsupported file type: " + f.getName() + " (use .xlsx, .csv, .tsv or .txt)");
    }

    // ------------------------------------------------------------------ delimited text
    public static DataTable readDelimited(File f) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
            String l;
            while ((l = br.readLine()) != null) lines.add(l);
        }
        while (!lines.isEmpty() && lines.get(0).trim().isEmpty()) lines.remove(0);
        if (lines.isEmpty()) throw new IOException("The file is empty.");
        String head = lines.get(0);
        if (head.startsWith("\uFEFF")) { head = head.substring(1); lines.set(0, head); }
        char delim = ',';
        int c = count(head, ','), s = count(head, ';'), t = count(head, '\t');
        if (t > c && t >= s) delim = '\t'; else if (s > c) delim = ';';
        List<String> cols = splitLine(head, delim);
        DataTable tab = new DataTable(trimAll(cols));
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            List<String> cells = splitLine(lines.get(i), delim);
            Object[] row = new Object[cols.size()];
            for (int j = 0; j < row.length; j++) row[j] = j < cells.size() ? parseCell(cells.get(j)) : null;
            tab.rows.add(row);
        }
        return tab;
    }

    private static Object parseCell(String raw) {
        String v = raw.trim();
        if (v.isEmpty() || v.equalsIgnoreCase("NA") || v.equalsIgnoreCase("NaN")) return null;
        try {
            return Double.valueOf(v);
        } catch (NumberFormatException e) {
            if (v.matches("-?\\d+,\\d+")) {           // decimal comma
                try { return Double.valueOf(v.replace(',', '.')); } catch (NumberFormatException ignored) { }
            }
            return v;
        }
    }

    private static int count(String s, char ch) { int n = 0; for (char x : s.toCharArray()) if (x == ch) n++; return n; }

    private static List<String> trimAll(List<String> l) {
        List<String> r = new ArrayList<>();
        for (String s : l) r.add(s.trim());
        return r;
    }

    static List<String> splitLine(String line, char delim) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (q) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; } else q = false;
                } else cur.append(ch);
            } else if (ch == '"') q = true;
            else if (ch == delim) { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }

    // ------------------------------------------------------------------ xlsx
    public static DataTable readXlsx(File f) throws IOException {
        try (ZipFile zf = new ZipFile(f)) {
            List<String> shared = sharedStrings(zf);
            String sheetPath = firstSheetPath(zf);
            ZipEntry se = zf.getEntry(sheetPath);
            if (se == null) throw new IOException("Worksheet not found in workbook: " + sheetPath);
            Document doc = parse(zf.getInputStream(se));
            NodeList rows = doc.getElementsByTagName("row");
            Map<Integer, Map<Integer, Object>> grid = new HashMap<>();
            int maxRow = 0, maxCol = 0;
            for (int i = 0; i < rows.getLength(); i++) {
                Element row = (Element) rows.item(i);
                NodeList cells = row.getElementsByTagName("c");
                int implicitCol = 0;
                String rAttr = row.getAttribute("r");
                int rowIdx = rAttr.isEmpty() ? i + 1 : Integer.parseInt(rAttr);
                for (int j = 0; j < cells.getLength(); j++) {
                    Element c = (Element) cells.item(j);
                    String ref = c.getAttribute("r");
                    int col = ref.isEmpty() ? implicitCol : colIndex(ref);
                    implicitCol = col + 1;
                    Object val = cellValue(c, shared);
                    if (val == null) continue;
                    grid.computeIfAbsent(rowIdx, k -> new HashMap<>()).put(col, val);
                    maxRow = Math.max(maxRow, rowIdx);
                    maxCol = Math.max(maxCol, col + 1);
                }
            }
            if (maxRow == 0) throw new IOException("The first worksheet is empty.");
            int firstRow = 1;
            while (firstRow <= maxRow && !grid.containsKey(firstRow)) firstRow++;
            Map<Integer, Object> head = grid.get(firstRow);
            List<String> cols = new ArrayList<>();
            List<Integer> keep = new ArrayList<>();
            for (int c = 0; c < maxCol; c++) {
                Object h = head.get(c);
                if (h == null) continue;           // readxl drops nothing but unnamed columns are not useful here
                cols.add(h instanceof Double ? stripZero((Double) h) : h.toString().trim());
                keep.add(c);
            }
            DataTable tab = new DataTable(cols);
            for (int r = firstRow + 1; r <= maxRow; r++) {
                Map<Integer, Object> rowCells = grid.get(r);
                if (rowCells == null || rowCells.isEmpty()) continue;
                Object[] out = new Object[cols.size()];
                for (int j = 0; j < keep.size(); j++) out[j] = rowCells.get(keep.get(j));
                tab.rows.add(out);
            }
            return tab;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not read the workbook: " + e.getMessage(), e);
        }
    }

    private static String stripZero(double d) { return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d); }

    private static Document parse(InputStream in) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (InputStream is = in) { return f.newDocumentBuilder().parse(is); }
    }

    private static List<String> sharedStrings(ZipFile zf) throws Exception {
        List<String> out = new ArrayList<>();
        ZipEntry e = zf.getEntry("xl/sharedStrings.xml");
        if (e == null) return out;
        Document d = parse(zf.getInputStream(e));
        NodeList sis = d.getElementsByTagName("si");
        for (int i = 0; i < sis.getLength(); i++) {
            Element si = (Element) sis.item(i);
            StringBuilder sb = new StringBuilder();
            NodeList ts = si.getElementsByTagName("t");
            for (int j = 0; j < ts.getLength(); j++) sb.append(ts.item(j).getTextContent());
            out.add(sb.toString());
        }
        return out;
    }

    private static String firstSheetPath(ZipFile zf) throws Exception {
        ZipEntry wb = zf.getEntry("xl/workbook.xml");
        ZipEntry rels = zf.getEntry("xl/_rels/workbook.xml.rels");
        if (wb != null && rels != null) {
            Document w = parse(zf.getInputStream(wb));
            NodeList sheets = w.getElementsByTagName("sheet");
            if (sheets.getLength() > 0) {
                Element s = (Element) sheets.item(0);
                String rid = s.getAttribute("r:id");
                Document r = parse(zf.getInputStream(rels));
                NodeList rl = r.getElementsByTagName("Relationship");
                for (int i = 0; i < rl.getLength(); i++) {
                    Element rel = (Element) rl.item(i);
                    if (rid.equals(rel.getAttribute("Id"))) {
                        String target = rel.getAttribute("Target");
                        if (target.startsWith("/")) return target.substring(1);
                        return "xl/" + target;
                    }
                }
            }
        }
        return "xl/worksheets/sheet1.xml";
    }

    private static int colIndex(String ref) {
        int c = 0;
        for (int i = 0; i < ref.length(); i++) {
            char ch = ref.charAt(i);
            if (ch >= 'A' && ch <= 'Z') c = c * 26 + (ch - 'A' + 1);
            else if (ch >= 'a' && ch <= 'z') c = c * 26 + (ch - 'a' + 1);
            else break;
        }
        return c - 1;
    }

    private static Object cellValue(Element c, List<String> shared) {
        String t = c.getAttribute("t");
        Node vNode = null, isNode = null;
        for (Node ch = c.getFirstChild(); ch != null; ch = ch.getNextSibling()) {
            if ("v".equals(ch.getNodeName())) vNode = ch;
            else if ("is".equals(ch.getNodeName())) isNode = ch;
        }
        if ("inlineStr".equals(t) && isNode != null) return isNode.getTextContent();
        if (vNode == null) return null;
        String v = vNode.getTextContent();
        if (v == null || v.isEmpty()) return null;
        switch (t) {
            case "s": return shared.get(Integer.parseInt(v.trim()));
            case "str": return v;
            case "b": return "1".equals(v.trim()) ? "TRUE" : "FALSE";
            case "e": return null;
            default:
                try { return Double.valueOf(v.trim()); } catch (NumberFormatException e) { return v; }
        }
    }
}
