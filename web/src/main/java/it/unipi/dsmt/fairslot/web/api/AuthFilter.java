package it.unipi.dsmt.fairslot.web.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

/** Every {@code /api/*} call needs a logged-in session, except logging in itself. */
@WebFilter("/api/*")
public class AuthFilter extends HttpFilter {

    @Override
    protected void doFilter(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        String path = req.getRequestURI().substring(req.getContextPath().length());
        HttpSession session = req.getSession(false);
        boolean loggedIn = session != null && session.getAttribute(ApiServlet.SESSION_USER) != null;
        if (loggedIn || path.equals("/api/auth/login")) {
            chain.doFilter(req, resp);
        } else {
            ApiServlet.writeError(resp, 401, "unauthenticated", "login required");
        }
    }
}
