package it.unipi.dsmt.fairslot.web.api;

import com.google.gson.JsonObject;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * <pre>
 * POST /api/auth/login   {"username", "password"}  -> {"user"}
 * POST /api/auth/logout                            -> {}
 * GET  /api/auth/me                                -> {"user"}
 * </pre>
 */
@WebServlet("/api/auth/*")
public class AuthServlet extends ApiServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        List<String> path = segments(req);
        if (path.equals(List.of("login"))) {
            JsonObject body = body(req);
            String user = app().authenticator()
                    .authenticate(requireString(body, "username"), requireString(body, "password"))
                    .orElseThrow(() -> new ApiError(401, "bad_credentials", "wrong username or password"));
            HttpSession old = req.getSession(false);
            if (old != null) {
                old.invalidate();   // new session ID on login (session fixation)
            }
            req.getSession(true).setAttribute(SESSION_USER, user);
            writeJson(resp, 200, Map.of("user", user));
        } else if (path.equals(List.of("logout"))) {
            HttpSession session = req.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            writeJson(resp, 200, Map.of());
        } else {
            throw routeNotFound(req);
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!segments(req).equals(List.of("me"))) {
            throw routeNotFound(req);
        }
        writeJson(resp, 200, Map.of("user", user(req)));
    }
}
