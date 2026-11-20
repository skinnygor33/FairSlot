package it.unipi.dsmt.fairslot.web;

import it.unipi.dsmt.fairslot.web.auth.Authenticator;
import it.unipi.dsmt.fairslot.web.auth.DevAuthenticator;
import it.unipi.dsmt.fairslot.web.backend.BookingBackend;
import it.unipi.dsmt.fairslot.web.backend.MockBackend;
import it.unipi.dsmt.fairslot.web.catalog.InMemoryResourceCatalog;
import it.unipi.dsmt.fairslot.web.catalog.Resource;
import it.unipi.dsmt.fairslot.web.catalog.ResourceCatalog;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

import java.time.Clock;
import java.time.ZoneId;
import java.util.logging.Logger;

/**
 * Builds the {@link AppContext} from configuration when the web app starts.
 *
 * <ul>
 *   <li>{@code FAIRSLOT_BACKEND}: {@code mock} (default) or {@code jinterface}</li>
 *   <li>{@code FAIRSLOT_TIMEZONE}: zone used to turn dates into slot times (default Europe/Rome)</li>
 * </ul>
 */
@WebListener
public class AppBootstrap implements ServletContextListener {

    private static final Logger LOG = Logger.getLogger(AppBootstrap.class.getName());

    @Override
    public void contextInitialized(ServletContextEvent event) {
        ZoneId zone = ZoneId.of(Config.get("FAIRSLOT_TIMEZONE", "Europe/Rome"));
        ResourceCatalog catalog = new InMemoryResourceCatalog();
        Authenticator authenticator = new DevAuthenticator();
        BookingBackend backend = createBackend(catalog);
        event.getServletContext().setAttribute(AppContext.ATTRIBUTE,
                new AppContext(backend, catalog, authenticator, zone));
        LOG.info(() -> "FairSlot web tier started with backend " + backend.getClass().getSimpleName());
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        AppContext app = AppContext.from(event.getServletContext());
        if (app != null) {
            app.backend().close();
        }
    }

    private static BookingBackend createBackend(ResourceCatalog catalog) {
        String kind = Config.get("FAIRSLOT_BACKEND", "mock");
        switch (kind) {
            case "mock":
                return new MockBackend(catalog.all().stream().map(Resource::id).toList(), Clock.systemUTC());
            default:
                throw new IllegalStateException("Unknown FAIRSLOT_BACKEND: " + kind);
        }
    }
}
