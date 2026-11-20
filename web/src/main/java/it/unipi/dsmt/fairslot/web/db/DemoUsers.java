package it.unipi.dsmt.fairslot.web.db;

import it.unipi.dsmt.fairslot.web.auth.PasswordHasher;

import java.util.ArrayList;
import java.util.List;

/**
 * Demo accounts for development and the demo scripts: alice, bob, carol, dave and
 * student01..student50, all with the password {@value #PASSWORD}.
 * Created only when {@code FAIRSLOT_DB_SEED_USERS=true} and the users table is empty.
 */
public final class DemoUsers {

    public static final String PASSWORD = "fairslot";
    static final int STUDENTS = 50;

    private DemoUsers() { }

    public static List<String> usernames() {
        List<String> names = new ArrayList<>(List.of("alice", "bob", "carol", "dave"));
        for (int i = 1; i <= STUDENTS; i++) {
            names.add(String.format("student%02d", i));
        }
        return names;
    }

    /** Returns how many users were created. */
    public static int seed(UserDao users) {
        if (users.count() > 0) {
            return 0;
        }
        // One hash for all demo users: PBKDF2 is slow on purpose, and 54 hashes would delay start-up.
        String hash = PasswordHasher.hash(PASSWORD);
        int created = 0;
        for (String name : usernames()) {
            String display = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            if (users.create(name, display, hash, "student")) {
                created++;
            }
        }
        return created;
    }
}
