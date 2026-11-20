package it.unipi.dsmt.fairslot.web.db;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC access to the web-tier database: MySQL on the VMs, H2 (MySQL mode) locally.
 * Opens one connection per use; the web tier issues few queries (login, catalog, history).
 */
public class Database {

    /** Default for local development: in-memory H2 that lives as long as the JVM. */
    public static final String DEV_URL = "jdbc:h2:mem:fairslot;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private final String url;
    private final String user;
    private final String password;

    public Database(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
        loadDriver(url);
    }

    public Connection open() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    public boolean isH2() {
        return url.startsWith("jdbc:h2:");
    }

    /** Creates the tables and the resource catalog if they do not exist yet. */
    public void initSchema() {
        String script = readResource("/db/schema.sql");
        try (Connection c = open(); Statement st = c.createStatement()) {
            for (String sql : splitStatements(script)) {
                st.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot initialise the database schema at " + url, e);
        }
    }

    /** Splits a script on ';' at line ends, dropping '--' comment lines. Enough for schema.sql. */
    static List<String> splitStatements(String script) {
        StringBuilder current = new StringBuilder();
        List<String> statements = new ArrayList<>();
        for (String line : script.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                String sql = current.toString().trim();
                statements.add(sql.substring(0, sql.length() - 1));
                current.setLength(0);
            }
        }
        if (!current.toString().isBlank()) {
            statements.add(current.toString().trim());
        }
        return statements;
    }

    private static String readResource(String path) {
        try (InputStream in = Database.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Inside a servlet container the JDBC driver sits in the web app's class loader, which
     * DriverManager's automatic discovery does not always see; loading it by name is reliable.
     */
    private static void loadDriver(String url) {
        String driver = url.startsWith("jdbc:h2:") ? "org.h2.Driver"
                : url.startsWith("jdbc:mysql:") ? "com.mysql.cj.jdbc.Driver" : null;
        if (driver != null) {
            try {
                Class.forName(driver);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("JDBC driver not on the classpath: " + driver, e);
            }
        }
    }
}
