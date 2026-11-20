package it.unipi.dsmt.fairslot.web.auth;

import java.util.Optional;

/** Checks login credentials. */
public interface Authenticator {

    /** Returns the canonical user ID if the credentials are valid. */
    Optional<String> authenticate(String username, String password);
}
