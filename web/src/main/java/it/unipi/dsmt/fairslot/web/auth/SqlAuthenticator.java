package it.unipi.dsmt.fairslot.web.auth;

import it.unipi.dsmt.fairslot.web.db.UserDao;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Login against the {@code users} table. */
public class SqlAuthenticator implements Authenticator {

    static final Pattern USERNAME = Pattern.compile("[a-z0-9_]{2,32}");

    /** Hash checked when the user does not exist, so both cases take the same time. */
    private static final String DUMMY_HASH = PasswordHasher.hash("not-a-real-password");

    private final UserDao users;

    public SqlAuthenticator(UserDao users) {
        this.users = users;
    }

    @Override
    public Optional<String> authenticate(String username, String password) {
        String name = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(name).matches() || password == null) {
            return Optional.empty();
        }
        Optional<UserDao.User> user = users.find(name);
        String hash = user.map(UserDao.User::passwordHash).orElse(DUMMY_HASH);
        boolean ok = PasswordHasher.verify(password, hash);
        return ok && user.isPresent() ? Optional.of(name) : Optional.empty();
    }
}
