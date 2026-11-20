package it.unipi.dsmt.fairslot.web.api;

import com.google.gson.JsonObject;
import it.unipi.dsmt.fairslot.web.backend.Op;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

/**
 * <pre>
 * POST   /api/holds             {"resourceId", "slotStart"} -> {"hold_id", "expires_at"}
 * POST   /api/holds/{id}/confirm                            -> {"booking_id"}
 * DELETE /api/holds/{id}                                    -> {}
 * </pre>
 */
@WebServlet("/api/holds/*")
public class HoldServlet extends ApiServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        List<String> path = segments(req);
        if (path.isEmpty()) {
            JsonObject body = body(req);
            writeJson(resp, 201, backend(req, Op.hold(requireString(body, "resourceId"), requireLong(body, "slotStart"))));
        } else if (path.size() == 2 && path.get(1).equals("confirm")) {
            writeJson(resp, 200, backend(req, Op.confirm(path.get(0))));
        } else {
            throw routeNotFound(req);
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        List<String> path = segments(req);
        if (path.size() != 1) {
            throw routeNotFound(req);
        }
        writeJson(resp, 200, backend(req, Op.release(path.get(0))));
    }
}
