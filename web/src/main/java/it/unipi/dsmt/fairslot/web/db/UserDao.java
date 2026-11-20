package it.unipi.dsmt.fairslot.web.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** The {@code users} table. */
public class UserDao {

    public record User(String username, String displayName, String passwordHash, String role) { }

    private final Database db;

    public UserDao(Database db) {
        this.db = db;
    }

    public Optional<User> find(String username) {
        String sql = "SELECT username, display_name, password_hash, role FROM users WHERE username = ?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new User(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read user " + username, e);
        }
    }

    /** Inserts a user; returns false if the username is already taken. */
    public boolean create(String username, String displayName, String passwordHash, String role) {
        String sql = "INSERT INTO users (username, display_name, password_hash, role) VALUES (?, ?, ?, ?)";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, displayName);
            ps.setString(3, passwordHash);
            ps.setString(4, role);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (e.getSQLState() != null && e.getSQLState().startsWith("23")) {   // integrity constraint
                return false;
            }
            throw new IllegalStateException("Cannot create user " + username, e);
        }
    }

    public int count() {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM users");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot count users", e);
        }
    }
}
