package it.unipi.dsmt.fairslot.web.auth;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Development-only login: any well-formed username with the password {@code dev}.
 * Lets several browser sessions act as different students without a database.
 */
public class DevAuthenticator implements Authenticator {

    static final Pattern USERNAME = Pattern.compile("[a-z0-9_]{2,32}");

    @Override
    public Optional<String> authenticate(String username, String password) {
        if (username != null && USERNAME.matcher(username).matches() && "dev".equals(password)) {
            return Optional.of(username);
        }
        return Optional.empty();
    }
}
