package it.unipi.dsmt.fairslot.web.backend;

import java.util.Map;

/**
 * The web tier's view of the Erlang back-end (docs/message-contract.md).
 * Implementations: {@link MockBackend} (in memory, for UI work and tests) and the
 * JInterface client that talks to the real cluster.
 */
public interface BookingBackend extends AutoCloseable {

    /**
     * Runs one operation for a user and returns the {@code Map} of {@code {ok, Map}}.
     *
     * @param requestId unique per user action; calling again with the same ID must not
     *                  apply the operation twice, it returns the first result
     * @throws BackendException on {@code {error, Reason}} or when the back-end is unreachable
     */
    Map<String, Object> call(String requestId, String userId, Op op) throws BackendException;

    /** Registers a listener for events pushed by the back-end (§5 of the contract). */
    void addEventListener(EventListener listener);

    @Override
    default void close() { }

    /** Receives {@code {fs_event, Type, Map}} messages. Called from a back-end thread. */
    @FunctionalInterface
    interface EventListener {
        void onEvent(String type, Map<String, Object> data);
    }
}
