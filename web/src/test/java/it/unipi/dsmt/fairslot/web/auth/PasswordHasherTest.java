package it.unipi.dsmt.fairslot.web.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {

    @Test
    void verifiesTheRightPasswordOnly() {
        String hash = PasswordHasher.hash("s3cret");
        assertTrue(hash.startsWith("pbkdf2_sha256$210000$"));
        assertTrue(PasswordHasher.verify("s3cret", hash));
        assertFalse(PasswordHasher.verify("S3cret", hash));
        assertFalse(PasswordHasher.verify("", hash));
    }

    @Test
    void saltMakesEveryHashDifferent() {
        assertNotEquals(PasswordHasher.hash("same"), PasswordHasher.hash("same"));
    }

    @Test
    void malformedHashesNeverVerify() {
        assertFalse(PasswordHasher.verify("x", null));
        assertFalse(PasswordHasher.verify("x", "plain-text"));
        assertFalse(PasswordHasher.verify("x", "pbkdf2_sha256$abc$%%%$%%%"));
    }
}
