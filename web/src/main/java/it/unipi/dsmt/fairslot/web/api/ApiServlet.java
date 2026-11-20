package it.unipi.dsmt.fairslot.web.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import it.unipi.dsmt.fairslot.web.AppContext;
import it.unipi.dsmt.fairslot.web.backend.BackendException;
import it.unipi.dsmt.fairslot.web.backend.Op;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Base class of the REST servlets: JSON in and out, session user, request IDs, error mapping. */
public abstract class ApiServlet extends HttpServlet {

    public static final String SESSION_USER = "fairslot.user";
    protected static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Clients may send this header to make a retried POST idempotent. */
    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        req.setCharacterEncoding("UTF-8");
        try {
            super.service(req, resp);
        } catch (ApiError e) {
            writeError(resp, e.status(), e.code(), e.getMessage());
        }
    }

    protected AppContext app() {
        return AppContext.from(getServletContext());
    }

    /** The logged-in user; {@link AuthFilter} guarantees there is one on protected paths. */
    protected static String user(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        Object user = session == null ? null : session.getAttribute(SESSION_USER);
        if (user == null) {
            throw new ApiError(401, "unauthenticated", "login required");
        }
        return (String) user;
    }

    /**
     * A unique ID for this user action (contract §2). A client-supplied idempotency key is
     * scoped by user, so two users can never collide on the same ID.
     */
    static String requestId(HttpServletRequest req, String user) {
        String key = req.getHeader(IDEMPOTENCY_HEADER);
        if (key != null && IDEMPOTENCY_KEY.matcher(key).matches()) {
            return user + ":" + key;
        }
        return UUID.randomUUID().toString();
    }

    /** Runs one back-end operation for the current user. */
    protected Map<String, Object> backend(HttpServletRequest req, Op op) {
        String user = user(req);
        try {
            return app().backend().call(requestId(req, user), user, op);
        } catch (BackendException e) {
            throw ApiError.fromBackend(e);
        }
    }

    /** Path segments after the servlet mapping: "/h-1/confirm" -> [h-1, confirm]. */
    protected static List<String> segments(HttpServletRequest req) {
        String info = req.getPathInfo();
        if (info == null || info.equals("/")) {
            return List.of();
        }
        return Arrays.asList(info.substring(1).split("/"));
    }

    protected static JsonObject body(HttpServletRequest req) throws IOException {
        try {
            JsonElement json = JsonParser.parseReader(req.getReader());
            if (!json.isJsonObject()) {
                throw ApiError.badRequest("request body must be a JSON object");
            }
            return json.getAsJsonObject();
        } catch (JsonParseException e) {
            throw ApiError.badRequest("invalid JSON");
        }
    }

    protected static String requireString(JsonObject body, String field) {
        JsonElement v = body.get(field);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString() || v.getAsString().isBlank()) {
            throw ApiError.badRequest("missing string field '" + field + "'");
        }
        return v.getAsString();
    }

    protected static long requireLong(JsonObject body, String field) {
        JsonElement v = body.get(field);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) {
            throw ApiError.badRequest("missing numeric field '" + field + "'");
        }
        try {
            return v.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException e) {
            throw ApiError.badRequest("field '" + field + "' must be an integer");
        }
    }

    protected static void writeJson(HttpServletResponse resp, int status, Object body) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        GSON.toJson(body, resp.getWriter());
    }

    protected static void writeError(HttpServletResponse resp, int status, String code, String message)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        writeJson(resp, status, body);
    }

    protected static ApiError routeNotFound(HttpServletRequest req) {
        return ApiError.notFound("no route for " + req.getMethod() + " " + req.getRequestURI());
    }
}
