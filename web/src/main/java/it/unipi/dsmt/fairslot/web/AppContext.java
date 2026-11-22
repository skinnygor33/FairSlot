package it.unipi.dsmt.fairslot.web;

import it.unipi.dsmt.fairslot.web.auth.Authenticator;
import it.unipi.dsmt.fairslot.web.backend.BookingBackend;
import it.unipi.dsmt.fairslot.web.catalog.ResourceCatalog;
import it.unipi.dsmt.fairslot.web.ws.NotificationHub;
import jakarta.servlet.ServletContext;

import java.time.ZoneId;

/** The services shared by all servlets, built once at start-up by {@link AppBootstrap}. */
public record AppContext(BookingBackend backend, ResourceCatalog catalog, Authenticator authenticator,
                         NotificationHub hub, ZoneId zone) {

    static final String ATTRIBUTE = AppContext.class.getName();

    public static AppContext from(ServletContext servletContext) {
        return (AppContext) servletContext.getAttribute(ATTRIBUTE);
    }
}
