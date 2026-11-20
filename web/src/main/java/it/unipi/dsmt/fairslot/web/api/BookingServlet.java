package it.unipi.dsmt.fairslot.web.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import it.unipi.dsmt.fairslot.web.backend.Op;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * <pre>
 * POST /api/bookings/{id}/cancel                                     -> {}
 * POST /api/bookings/{id}/check-in                                   -> {}
 * POST /api/bookings/bundle  {"items": [{"resourceId","slotStart"}]} -> {"bundle_id", "booking_ids"}
 * </pre>
 */
@WebServlet("/api/bookings/*")
public class BookingServlet extends ApiServlet {

    static final int MAX_BUNDLE = 5;

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        List<String> path = segments(req);
        if (path.equals(List.of("bundle"))) {
            writeJson(resp, 201, backend(req, Op.bookBundle(bundleItems(body(req)))));
        } else if (path.size() == 2 && path.get(1).equals("cancel")) {
            writeJson(resp, 200, backend(req, Op.cancel(path.get(0))));
        } else if (path.size() == 2 && path.get(1).equals("check-in")) {
            writeJson(resp, 200, backend(req, Op.checkIn(path.get(0))));
        } else {
            throw routeNotFound(req);
        }
    }

    private static List<Op.BundleItem> bundleItems(JsonObject body) {
        JsonElement items = body.get("items");
        if (items == null || !items.isJsonArray()) {
            throw ApiError.badRequest("missing array field 'items'");
        }
        JsonArray array = items.getAsJsonArray();
        if (array.size() < 2 || array.size() > MAX_BUNDLE) {
            throw ApiError.badRequest("a bundle has 2 to " + MAX_BUNDLE + " items");
        }
        List<Op.BundleItem> result = new ArrayList<>();
        for (JsonElement e : array) {
            if (!e.isJsonObject()) {
                throw ApiError.badRequest("each item must be an object");
            }
            JsonObject item = e.getAsJsonObject();
            result.add(new Op.BundleItem(requireString(item, "resourceId"), requireLong(item, "slotStart")));
        }
        return result;
    }
}
