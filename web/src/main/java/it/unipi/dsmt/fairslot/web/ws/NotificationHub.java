package it.unipi.dsmt.fairslot.web.ws;

import com.google.gson.Gson;
import it.unipi.dsmt.fairslot.web.backend.BookingBackend;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Forwards back-end events to the browsers over WebSocket (proposal §5, contract §5).
 * An event with a {@code user} field goes only to that user's open tabs; events without
 * one ({@code slot_freed}, {@code slot_taken}) go to everyone so open calendars refresh.
 * Messages are JSON: {@code {"type": "offer", "offer_id": ..., ...}}.
 *
 * <p>Sending happens on one background thread, so a slow browser never blocks the
 * back-end's event thread or a servlet request.
 */
public class NotificationHub implements BookingBackend.EventListener, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(NotificationHub.class.getName());
    private static final Gson GSON = new Gson();
    /** Events the web tier handles itself and does not forward to browsers. */
    private static final Set<String> INTERNAL = Set.of("partition_moved");

    /** One open WebSocket: who it belongs to and how to send it text. */
    private record Client(String user, String httpSessionId, Consumer<String> sender) { }

    private final Map<String, Client> clients = new ConcurrentHashMap<>();   // client id -> client
    private final ExecutorService sendThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fairslot-ws-send");
        t.setDaemon(true);
        return t;
    });

    public void register(String clientId, String user, String httpSessionId, Consumer<String> sender) {
        clients.put(clientId, new Client(user, httpSessionId, sender));
    }

    public void unregister(String clientId) {
        clients.remove(clientId);
    }

    /** Number of open connections (for tests and logs). */
    public int size() {
        return clients.size();
    }

    @Override
    public void onEvent(String type, Map<String, Object> data) {
        if (INTERNAL.contains(type)) {
            return;
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        message.putAll(data);
        String json = GSON.toJson(message);
        Object target = data.get("user");
        for (Map.Entry<String, Client> e : clients.entrySet()) {
            Client c = e.getValue();
            if (target == null || target.equals(c.user())) {
                sendThread.execute(() -> send(e.getKey(), c, json));
            }
        }
    }

    /** Sends a message to every open tab of one user (e.g. a welcome or a logout notice). */
    public void sendToUser(String user, Map<String, Object> message) {
        String json = GSON.toJson(message);
        clients.forEach((id, c) -> {
            if (c.user().equals(user)) {
                sendThread.execute(() -> send(id, c, json));
            }
        });
    }

    /** Drops the sockets of a logged-out HTTP session; returns their client IDs so they can be closed. */
    public Set<String> forgetHttpSession(String httpSessionId) {
        Set<String> removed = ConcurrentHashMap.newKeySet();
        clients.forEach((id, c) -> {
            if (c.httpSessionId().equals(httpSessionId)) {
                removed.add(id);
            }
        });
        removed.forEach(clients::remove);
        return removed;
    }

    private void send(String clientId, Client c, String json) {
        try {
            c.sender().accept(json);
        } catch (RuntimeException ex) {
            LOG.log(Level.FINE, "Dropping WebSocket client " + clientId, ex);
            clients.remove(clientId);
        }
    }

    @Override
    public void close() {
        sendThread.shutdownNow();
        clients.clear();
    }
}
