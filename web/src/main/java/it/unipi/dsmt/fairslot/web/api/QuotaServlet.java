package it.unipi.dsmt.fairslot.web.api;

import it.unipi.dsmt.fairslot.web.backend.Op;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** {@code GET /api/quota -> {"used_hours", "limit_hours", "week_start"}} */
@WebServlet("/api/quota")
public class QuotaServlet extends ApiServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        writeJson(resp, 200, backend(req, Op.quota()));
    }
}
