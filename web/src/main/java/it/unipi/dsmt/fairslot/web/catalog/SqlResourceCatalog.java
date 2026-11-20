package it.unipi.dsmt.fairslot.web.catalog;

import it.unipi.dsmt.fairslot.web.db.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** The {@code resources} table, read once at start-up (the catalog changes only with a redeploy). */
public class SqlResourceCatalog implements ResourceCatalog {

    private final List<Resource> resources;

    public SqlResourceCatalog(Database db) {
        String sql = "SELECT id, name, kind, building, capacity FROM resources ORDER BY kind DESC, id";
        List<Resource> list = new ArrayList<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new Resource(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot load the resource catalog", e);
        }
        this.resources = List.copyOf(list);
    }

    @Override
    public List<Resource> all() {
        return resources;
    }
}
