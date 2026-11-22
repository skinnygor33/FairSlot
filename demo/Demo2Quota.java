import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Demo 2 — Quota (proposal §6).
 * One student logs in from several sessions (think: laptop, phone, a friend's PC) and fires
 * hold requests for different free slots from all of them at the same instant, asking for
 * more hours than the weekly quota. The total granted never exceeds the quota.
 *
 * <pre>
 * java demo/Demo2Quota.java [baseUrl] [user] [sessions] [requestsPerSession] [date] [--keep]
 * java demo/Demo2Quota.java http://localhost:8080 student50 3 6 2026-11-24
 * </pre>
 * Defaults: http://localhost:8080, student50, 3 sessions x 6 requests, tomorrow.
 * The holds are released at the end so the demo can be repeated; --keep leaves them.
 * Exit code 0 = PASS.
 */
public class Demo2Quota {

    public static void main(String[] args) throws Exception {
        List<String> a = new ArrayList<>(List.of(args));
        boolean keep = a.remove("--keep");
        String base = a.size() > 0 ? a.get(0) : "http://localhost:8080";
        String user = a.size() > 1 ? a.get(1) : "student50";
        int sessions = a.size() > 2 ? Integer.parseInt(a.get(2)) : 3;
        int perSession = a.size() > 3 ? Integer.parseInt(a.get(3)) : 6;
        String date = a.size() > 4 ? a.get(4) : LocalDate.now().plusDays(1).toString();
        int total = sessions * perSession;

        System.out.printf("Demo 2 - quota: %s from %d sessions x %d requests on %s, server %s%n%n",
                user, sessions, perSession, date, base);

        List<Client> logins = new ArrayList<>();
        for (int i = 0; i < sessions; i++) {
            logins.add(Client.login(base, user, "fairslot"));
        }
        Client.Response q0 = logins.get(0).get("/api/quota");
        long used0 = Long.parseLong(q0.field("used_hours"));
        long limit = Long.parseLong(q0.field("limit_hours"));
        System.out.printf("Quota before: %d/%d hours%n", used0, limit);

        List<long[]> slots = new ArrayList<>();       // {resource index, start}
        List<String> resources = logins.get(0).resourceIds();
        for (int r = 0; r < resources.size() && slots.size() < total; r++) {
            for (long start : logins.get(0).freeSlots(resources.get(r), date)) {
                if (slots.size() < total) {
                    slots.add(new long[] {r, start});
                }
            }
        }
        if (slots.size() < total) {
            System.out.println("Not enough free slots on that day: pick another date. FAIL");
            System.exit(2);
        }
        System.out.printf("Asking for %d different slots (%d hours) at once; at most %d can be granted.%n",
                total, total, limit - used0);

        ExecutorService pool = Executors.newFixedThreadPool(total);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<String[]> granted = new ConcurrentLinkedQueue<>();   // {session, holdId}
        Map<String, Integer> outcomes = new TreeMap<>();
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            Client session = logins.get(i % sessions);
            long[] s = slots.get(i);
            String body = "{\"resourceId\":\"" + resources.get((int) s[0]) + "\",\"slotStart\":" + s[1] + "}";
            int sessionIndex = i % sessions;
            futures.add(pool.submit(() -> {
                go.await();
                Client.Response r = session.post("/api/holds", body);
                if (r.status == 201) {
                    granted.add(new String[] {String.valueOf(sessionIndex), r.field("hold_id")});
                    return "hold granted";
                }
                return r.field("error");
            }));
        }
        go.countDown();
        for (Future<String> f : futures) {
            outcomes.merge(f.get(), 1, Integer::sum);
        }
        pool.shutdown();

        System.out.printf("%nResults of %d simultaneous requests:%n", total);
        outcomes.forEach((k, v) -> System.out.printf("  %-15s %3d%n", k, v));

        long usedAfter = Long.parseLong(logins.get(0).get("/api/quota").field("used_hours"));
        long expectedGranted = Math.min(total, limit - used0);
        System.out.printf("Quota after: %d/%d hours%n", usedAfter, limit);

        boolean pass = granted.size() == expectedGranted && usedAfter <= limit
                && usedAfter == used0 + granted.size()
                && outcomes.getOrDefault("quota_exceeded", 0) == total - expectedGranted;

        if (!keep) {
            for (String[] g : granted) {
                logins.get(Integer.parseInt(g[0])).delete("/api/holds/" + g[1]);
            }
            System.out.printf("Released the %d holds (use --keep to leave them).%n", granted.size());
        }

        System.out.println();
        System.out.println(pass ? "PASS: the weekly quota was never exceeded." : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    /** Minimal JSON-over-HTTP client with its own cookie jar (= its own login session). */
    static final class Client {
        final String base;
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

        Client(String base) {
            this.base = base;
        }

        static Client login(String base, String user, String password) throws Exception {
            Client c = new Client(base);
            Response r = c.post("/api/auth/login", "{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}");
            if (r.status != 200) {
                throw new IllegalStateException("Login failed for " + user + ": " + r.body);
            }
            return c;
        }

        Response post(String path, String json) throws Exception {
            return send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build());
        }

        Response get(String path) throws Exception {
            return send(HttpRequest.newBuilder(URI.create(base + path)).build());
        }

        Response delete(String path) throws Exception {
            return send(HttpRequest.newBuilder(URI.create(base + path)).DELETE().build());
        }

        private Response send(HttpRequest req) throws Exception {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.body());
        }

        List<String> resourceIds() throws Exception {
            List<String> ids = new ArrayList<>();
            Matcher m = Pattern.compile("\"id\":\"([^\"]+)\"").matcher(get("/api/resources").body);
            while (m.find()) {
                ids.add(m.group(1));
            }
            return ids;
        }

        List<Long> freeSlots(String resource, String date) throws Exception {
            List<Long> free = new ArrayList<>();
            Response r = get("/api/resources/" + resource + "/availability?date=" + date);
            Matcher m = Pattern.compile("\\{\"start\":(\\d+),\"state\":\"free\"").matcher(r.body);
            while (m.find()) {
                free.add(Long.parseLong(m.group(1)));
            }
            return free;
        }

        record Response(int status, String body) {
            String field(String name) {
                Matcher m = Pattern.compile("\"" + name + "\":\"?([^\",}]*)").matcher(body);
                return m.find() ? m.group(1) : null;
            }
        }
    }
}
