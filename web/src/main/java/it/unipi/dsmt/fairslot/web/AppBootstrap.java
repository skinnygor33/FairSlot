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
import it.unipi.dsmt.fairslot.web.ws.NotificationHub;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

import it.unipi.dsmt.fairslot.web.backend.JInterfaceBackend;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
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
        NotificationHub hub = new NotificationHub();
        backend.addEventListener(hub);
        event.getServletContext().setAttribute(AppContext.ATTRIBUTE,
                new AppContext(backend, catalog, authenticator, hub, zone));
        LOG.info(() -> "FairSlot web tier started with backend " + backend.getClass().getSimpleName()
                + " and " + catalog.all().size() + " resources");
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        AppContext app = AppContext.from(event.getServletContext());
        if (app != null) {
            app.hub().close();
            app.backend().close();
        }
    }

    private static BookingBackend createBackend(ResourceCatalog catalog) {
        String kind = Config.get("FAIRSLOT_BACKEND", "mock");
        switch (kind) {
            case "mock":
                MockBackend mock = new MockBackend(catalog.all().stream().map(Resource::id).toList(), Clock.systemUTC());
                mock.startTimers();
                return mock;
            case "jinterface":
                return createJInterfaceBackend();
            default:
                throw new IllegalStateException("Unknown FAIRSLOT_BACKEND: " + kind);
        }
    }

    /**
     * {@code FAIRSLOT_ERLANG_NODES} (comma-separated, default {@code fairslot_a@<this host>}),
     * {@code FAIRSLOT_COOKIE} (default {@code fairslot-dev}), {@code FAIRSLOT_WEB_NODE}
     * (default {@code fairslot_web}), {@code FAIRSLOT_ERLANG_TIMEOUT_MS} (default 5000).
     */
    private static BookingBackend createJInterfaceBackend() {
        List<String> nodes = Arrays.stream(Config.get("FAIRSLOT_ERLANG_NODES", "fairslot_a@" + shortHostName())
                .split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        try {
            return new JInterfaceBackend(Config.get("FAIRSLOT_WEB_NODE", "fairslot_web"),
                    Config.get("FAIRSLOT_COOKIE", "fairslot-dev"), nodes,
                    Long.parseLong(Config.get("FAIRSLOT_ERLANG_TIMEOUT_MS", "5000")));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start the JInterface node", e);
        }
    }

    /** Same host part that {@code erl -sname} uses. */
    static String shortHostName() {
        try {
            String host = InetAddress.getLocalHost().getHostName();
            int dot = host.indexOf('.');
            return dot > 0 ? host.substring(0, dot) : host;
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }
}
