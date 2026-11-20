package it.unipi.dsmt.fairslot.web.api;

import it.unipi.dsmt.fairslot.web.backend.BackendException;

import java.util.Map;

/**
 * An error answered to the browser as {@code {"error": code, "message": ...}} with an HTTP status.
 * Back-end reasons keep their contract name as the code, so the UI can react to them.
 */
public class ApiError extends RuntimeException {

    private static final Map<String, Integer> STATUS = Map.ofEntries(
            Map.entry("bad_request", 400),
            Map.entry("not_holder", 403),
            Map.entry("not_found", 404),
            Map.entry("slot_taken", 409),
            Map.entry("slot_free", 409),
            Map.entry("quota_exceeded", 409),
            Map.entry("already_waiting", 409),
            Map.entry("too_early", 409),
            Map.entry("too_late", 409),
            Map.entry("hold_expired", 410),
            Map.entry("offer_expired", 410),
            Map.entry(BackendException.UNAVAILABLE, 503),
            Map.entry("wrong_owner", 503),
            Map.entry("stale_epoch", 503),
            Map.entry("no_majority", 503));

    private final int status;
    private final String code;

    public ApiError(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiError badRequest(String message) {
        return new ApiError(400, "bad_request", message);
    }

    public static ApiError notFound(String message) {
        return new ApiError(404, "not_found", message);
    }

    public static ApiError fromBackend(BackendException e) {
        int status = STATUS.getOrDefault(e.reason(), 500);
        String message = e.detail() == null ? e.reason() : String.valueOf(e.detail());
        return new ApiError(status, e.reason(), message);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
