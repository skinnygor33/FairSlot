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
 * POST /api/waitlist            {"resourceId", "slotStart"} -> {"position"}
 * POST /api/offers/{id}/accept                              -> {"booking_id"}
 * POST /api/offers/{id}/decline                             -> {}
 * </pre>
 */
@WebServlet({"/api/waitlist", "/api/offers/*"})
public class WaitlistServlet extends ApiServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        List<String> path = segments(req);
        if (req.getServletPath().equals("/api/waitlist")) {
            JsonObject body = body(req);
            writeJson(resp, 201, backend(req, Op.joinWaitlist(requireString(body, "resourceId"),
                    requireLong(body, "slotStart"))));
        } else if (path.size() == 2 && path.get(1).equals("accept")) {
            writeJson(resp, 200, backend(req, Op.acceptOffer(path.get(0))));
        } else if (path.size() == 2 && path.get(1).equals("decline")) {
            writeJson(resp, 200, backend(req, Op.declineOffer(path.get(0))));
        } else {
            throw routeNotFound(req);
        }
    }
}
