import java.sql.*;
import java.util.*;

public class H2ToMySQL {
    public static void main(String[] args) throws Exception {
        String h2Url = args[0];
        String myUrl = args[1];
        String myUser = args[2];
        String myPass = args[3];
        Class.forName("org.h2.Driver");
        Class.forName("com.mysql.cj.jdbc.Driver");
        Connection h2 = DriverManager.getConnection(h2Url, "sa", "");
        Connection my = DriverManager.getConnection(myUrl, myUser, myPass);
        my.setAutoCommit(false);

        DatabaseMetaData h2Meta = h2.getMetaData();
        List<String> tables = new ArrayList<String>();
        try (ResultSet rs = h2Meta.getTables(null, "PUBLIC", "%", new String[]{"TABLE"})) {
            while (rs.next()) tables.add(rs.getString("TABLE_NAME"));
        }
        Collections.sort(tables);

        int totalRows = 0;
        for (String table : tables) {
            String myTable = table.toLowerCase(Locale.ROOT);
            if (!mysqlTableExists(my, myTable)) {
                System.out.println("SKIP (no mysql table): " + table);
                continue;
            }
            List<String> h2Cols = new ArrayList<String>();
            try (ResultSet rs = h2Meta.getColumns(null, "PUBLIC", table, "%")) {
                while (rs.next()) h2Cols.add(rs.getString("COLUMN_NAME"));
            }
            List<String> cols = new ArrayList<String>();
            for (String c : h2Cols) {
                if (mysqlColumnExists(my, myTable, c.toLowerCase(Locale.ROOT))) cols.add(c);
            }
            if (cols.isEmpty()) {
                System.out.println("SKIP (no columns): " + table);
                continue;
            }
            StringBuilder select = new StringBuilder("SELECT ");
            StringBuilder insert = new StringBuilder("REPLACE INTO `").append(myTable).append("` (");
            StringBuilder marks = new StringBuilder();
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) { select.append(","); insert.append(","); marks.append(","); }
                select.append("\"").append(cols.get(i)).append("\"");
                insert.append("`").append(cols.get(i).toLowerCase(Locale.ROOT)).append("`");
                marks.append("?");
            }
            select.append(" FROM \"").append(table).append("\"");
            insert.append(") VALUES (").append(marks).append(")");

            int rows = 0;
            try (Statement st = h2.createStatement();
                 ResultSet rs = st.executeQuery(select.toString());
                 PreparedStatement ps = my.prepareStatement(insert.toString())) {
                while (rs.next()) {
                    for (int i = 0; i < cols.size(); i++) {
                        Object v = rs.getObject(cols.get(i));
                        if (v instanceof Clob) {
                            Clob c = (Clob) v;
                            v = c.getSubString(1, (int) Math.min(c.length(), Integer.MAX_VALUE));
                        } else if (v instanceof Blob) {
                            Blob b = (Blob) v;
                            v = b.getBytes(1, (int) b.length());
                        }
                        ps.setObject(i + 1, v);
                    }
                    ps.addBatch();
                    rows++;
                    if (rows % 500 == 0) ps.executeBatch();
                }
                ps.executeBatch();
            }
            my.commit();
            totalRows += rows;
            System.out.println(String.format("%-28s %6d rows", myTable, rows));
        }
        System.out.println("DONE totalRows=" + totalRows);
        my.close();
        h2.close();
    }

    private static boolean mysqlTableExists(Connection my, String table) throws Exception {
        try (PreparedStatement ps = my.prepareStatement("SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private static boolean mysqlColumnExists(Connection my, String table, String column) throws Exception {
        try (PreparedStatement ps = my.prepareStatement("SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }
}