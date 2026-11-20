package it.unipi.dsmt.fairslot.web;

import it.unipi.dsmt.fairslot.web.auth.Authenticator;
import it.unipi.dsmt.fairslot.web.auth.SqlAuthenticator;
import it.unipi.dsmt.fairslot.web.backend.BookingBackend;
import it.unipi.dsmt.fairslot.web.backend.MockBackend;
import it.unipi.dsmt.fairslot.web.catalog.Resource;
import it.unipi.dsmt.fairslot.web.catalog.ResourceCatalog;
import it.unipi.dsmt.fairslot.web.catalog.SqlResourceCatalog;
import it.unipi.dsmt.fairslot.web.db.Database;
import it.unipi.dsmt.fairslot.web.db.DemoUsers;
import it.unipi.dsmt.fairslot.web.db.UserDao;
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
 *   <li>{@code FAIRSLOT_DB_URL}, {@code FAIRSLOT_DB_USER}, {@code FAIRSLOT_DB_PASSWORD}: JDBC
 *       connection (default: in-memory H2)</li>
 *   <li>{@code FAIRSLOT_DB_SEED_USERS}: create the demo accounts if there are no users
 *       (default true on H2, false otherwise)</li>
 * </ul>
 */
@WebListener
public class AppBootstrap implements ServletContextListener {

    private static final Logger LOG = Logger.getLogger(AppBootstrap.class.getName());

    @Override
    public void contextInitialized(ServletContextEvent event) {
        ZoneId zone = ZoneId.of(Config.get("FAIRSLOT_TIMEZONE", "Europe/Rome"));

        Database db = new Database(Config.get("FAIRSLOT_DB_URL", Database.DEV_URL),
                Config.get("FAIRSLOT_DB_USER", "sa"), Config.get("FAIRSLOT_DB_PASSWORD", ""));
        db.initSchema();
        UserDao users = new UserDao(db);
        if (Boolean.parseBoolean(Config.get("FAIRSLOT_DB_SEED_USERS", String.valueOf(db.isH2())))) {
            int created = DemoUsers.seed(users);
            if (created > 0) {
                LOG.info(() -> "Created " + created + " demo users (password '" + DemoUsers.PASSWORD + "')");
            }
        }

        ResourceCatalog catalog = new SqlResourceCatalog(db);
        Authenticator authenticator = new SqlAuthenticator(users);
        BookingBackend backend = createBackend(catalog);
        event.getServletContext().setAttribute(AppContext.ATTRIBUTE,
                new AppContext(backend, catalog, authenticator, zone));
        LOG.info(() -> "FairSlot web tier started with backend " + backend.getClass().getSimpleName()
                + " and " + catalog.all().size() + " resources");
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
