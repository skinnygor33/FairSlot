package it.unipi.dsmt.fairslot.web.db;

import it.unipi.dsmt.fairslot.web.auth.PasswordHasher;
import it.unipi.dsmt.fairslot.web.auth.SqlAuthenticator;
import it.unipi.dsmt.fairslot.web.catalog.Resource;
import it.unipi.dsmt.fairslot.web.catalog.SqlResourceCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Schema, users, login and catalog against a fresh in-memory H2 database in MySQL mode. */
class SqlLayerTest {

    Database db;
    UserDao users;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        db = new Database(url, "sa", "");
        db.initSchema();
        users = new UserDao(db);
    }

    @Test
    void schemaIsIdempotentAndSeedsTheCatalog() {
        db.initSchema();   // second run must not fail or duplicate rows
        List<Resource> resources = new SqlResourceCatalog(db).all();
        assertEquals(7, resources.size());
        assertEquals("room", resources.get(0).kind());
        assertTrue(resources.stream().anyMatch(r -> r.id().equals("equip:proj-01")));
    }

    @Test
    void loginChecksPasswordAndNormalisesUsername() {
        users.create("alice", "Alice", PasswordHasher.hash("pw-alice"), "student");
        SqlAuthenticator auth = new SqlAuthenticator(users);
        assertEquals(Optional.of("alice"), auth.authenticate(" Alice ", "pw-alice"));
        assertEquals(Optional.empty(), auth.authenticate("alice", "wrong"));
        assertEquals(Optional.empty(), auth.authenticate("nobody", "pw-alice"));
        assertEquals(Optional.empty(), auth.authenticate("alice'; DROP TABLE users; --", "x"));
    }

    @Test
    void duplicateUsernameIsRejected() {
        assertTrue(users.create("bob", "Bob", "h", "student"));
        assertFalse(users.create("bob", "Bob 2", "h", "student"));
    }

    @Test
    void demoUsersAreSeededOnlyIntoAnEmptyTable() {
        assertEquals(DemoUsers.usernames().size(), DemoUsers.seed(users));
        assertEquals(0, DemoUsers.seed(users));
        SqlAuthenticator auth = new SqlAuthenticator(users);
        assertEquals(Optional.of("student07"), auth.authenticate("student07", DemoUsers.PASSWORD));
    }
}
