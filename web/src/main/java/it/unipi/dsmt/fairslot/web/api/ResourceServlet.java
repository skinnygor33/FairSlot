package it.unipi.dsmt.fairslot.web.api;

import it.unipi.dsmt.fairslot.web.backend.Op;
import it.unipi.dsmt.fairslot.web.catalog.Resource;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <pre>
 * GET /api/resources                                  -> [{"id","name","kind","building","capacity"}]
 * GET /api/resources/{id}/availability?date=YYYY-MM-DD -> {"resource", "date", "slots": [{"start","state"}]}
 * </pre>
 */
@WebServlet("/api/resources/*")
public class ResourceServlet extends ApiServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        List<String> path = segments(req);
        if (path.isEmpty()) {
            writeJson(resp, 200, app().catalog().all());
        } else if (path.size() == 2 && path.get(1).equals("availability")) {
            Resource resource = app().catalog().find(path.get(0))
                    .orElseThrow(() -> ApiError.notFound("unknown resource " + path.get(0)));
            LocalDate date = parseDate(req.getParameter("date"));
            long dayStart = date.atStartOfDay(app().zone()).toInstant().toEpochMilli();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("resource", resource);
            result.put("date", date.toString());
            result.putAll(backend(req, Op.availability(resource.id(), dayStart)));
            writeJson(resp, 200, result);
        } else {
            throw routeNotFound(req);
        }
    }

    private static LocalDate parseDate(String value) {
        if (value == null) {
            throw ApiError.badRequest("missing parameter 'date' (YYYY-MM-DD)");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw ApiError.badRequest("'date' must be YYYY-MM-DD");
        }
    }
}
