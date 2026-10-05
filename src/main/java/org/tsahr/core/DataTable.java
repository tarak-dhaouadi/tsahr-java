package org.tsahr.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal in-memory table (the Java counterpart of the data.frame / .xlsx that tsa_hr() accepts).
 * Cells are Double (numeric), String (text) or null (missing).
 */
public final class DataTable {
    public final List<String> columns = new ArrayList<>();
    public final List<Object[]> rows = new ArrayList<>();

    public DataTable() {}

    public DataTable(List<String> columns) { this.columns.addAll(columns); }

    public int nrow() { return rows.size(); }

    public int col(String name) { return columns.indexOf(name); }

    public void addRow(Object... cells) {
        Object[] r = new Object[columns.size()];
        for (int i = 0; i < r.length && i < cells.length; i++) r[i] = cells[i];
        rows.add(r);
    }

    public Object get(int row, String column) {
        int c = col(column);
        return c < 0 ? null : rows.get(row)[c];
    }

    public DataTable copy() {
        DataTable t = new DataTable(columns);
        for (Object[] r : rows) t.rows.add(r.clone());
        return t;
    }
}
