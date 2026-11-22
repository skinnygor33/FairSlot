package it.unipi.dsmt.fairslot.web.ws;

import it.unipi.dsmt.fairslot.web.AppContext;
import it.unipi.dsmt.fairslot.web.api.ApiServlet;
import jakarta.servlet.http.HttpSession;
import jakarta.websocket.CloseReason;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.websocket.server.ServerEndpointConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * {@code ws://host/ws}: live notifications for the logged-in user. The browser opens it
 * after login; the HTTP session cookie identifies the user. Unauthenticated sockets are
 * closed with code 1008 (policy violation).
 */
@ServerEndpoint(value = "/ws", configurator = NotificationEndpoint.SessionConfigurator.class)
public class NotificationEndpoint {

    static final String HTTP_SESSION = "fairslot.httpSession";

    /** Copies the HTTP session (and with it the logged-in user) into the WebSocket session. */
    public static class SessionConfigurator extends ServerEndpointConfig.Configurator {
        @Override
        public void modifyHandshake(ServerEndpointConfig config, HandshakeRequest request, HandshakeResponse response) {
            Object http = request.getHttpSession();
            if (http != null) {
                config.getUserProperties().put(HTTP_SESSION, http);
            }
        }
    }

    @OnOpen
    public void onOpen(Session session, EndpointConfig config) throws IOException {
        // Jakarta WebSocket 2.1 gives each connection its own copy of the user properties.
        HttpSession http = (HttpSession) config.getUserProperties().get(HTTP_SESSION);
        String user = null;
        try {
            user = http == null ? null : (String) http.getAttribute(ApiServlet.SESSION_USER);
        } catch (IllegalStateException invalidated) {
            // logged out between the handshake and now
        }
        if (user == null) {
            session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "login required"));
            return;
        }
        NotificationHub hub = AppContext.from(http.getServletContext()).hub();
        session.getUserProperties().put("hub", hub);
        hub.register(session.getId(), user, http.getId(), text -> {
            synchronized (session) {
                try {
                    session.getBasicRemote().sendText(text);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });
        hub.sendToUser(user, Map.of("type", "hello", "user", user));
    }

    /** Browsers may send "ping" to keep idle proxies from closing the socket. */
    @OnMessage
    public String onMessage(String text) {
        return "ping".equals(text) ? "{\"type\":\"pong\"}" : null;
    }

    @OnClose
    public void onClose(Session session) {
        unregister(session);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        unregister(session);
    }

    private static void unregister(Session session) {
        Object hub = session.getUserProperties().get("hub");
        if (hub instanceof NotificationHub) {
            ((NotificationHub) hub).unregister(session.getId());
        }
    }
}
