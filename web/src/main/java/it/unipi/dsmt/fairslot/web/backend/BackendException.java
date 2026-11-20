package it.unipi.dsmt.fairslot.web.backend;

/**
 * The back-end answered {@code {error, Reason}}, or could not be reached.
 * {@link #reason()} is the Erlang reason atom (e.g. {@code slot_taken}); for tuple reasons
 * such as {@code {wrong_owner, Epoch}} it is the first element and {@link #detail()} the rest.
 */
public class BackendException extends Exception {

    /** Reason used when no Erlang node answered in time. */
    public static final String UNAVAILABLE = "unavailable";

    private final String reason;
    private final transient Object detail;

    public BackendException(String reason) {
        this(reason, null);
    }

    public BackendException(String reason, Object detail) {
        super(detail == null ? reason : reason + " " + detail);
        this.reason = reason;
        this.detail = detail;
    }

    public String reason() {
        return reason;
    }

    public Object detail() {
        return detail;
    }
}
