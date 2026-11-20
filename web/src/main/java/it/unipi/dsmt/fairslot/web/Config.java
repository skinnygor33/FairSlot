package it.unipi.dsmt.fairslot.web;

/**
 * Settings read from a JVM system property or, failing that, an environment variable of the
 * same name (e.g. {@code -DFAIRSLOT_BACKEND=jinterface} or {@code FAIRSLOT_BACKEND=jinterface}).
 */
public final class Config {

    private Config() { }

    public static String get(String name, String defaultValue) {
        String v = System.getProperty(name);
        if (v == null || v.isBlank()) {
            v = System.getenv(name);
        }
        return v == null || v.isBlank() ? defaultValue : v.trim();
    }
}
