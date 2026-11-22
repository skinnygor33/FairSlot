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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Demo 1 — Contention (proposal §6).
 * N students, each with their own login session, ask for the same free slot at the same
 * instant. Exactly one gets it; the others are refused with slot_taken and join the waitlist
 * in a single FIFO order.
 *
 * <pre>
 * java demo/Demo1Contention.java [baseUrl] [clients] [resourceId] [date]
 * java demo/Demo1Contention.java http://localhost:8080 30 room:B1-101 2026-11-24
 * </pre>
 * Defaults: http://localhost:8080, 30 clients, room:B1-101, tomorrow. Exit code 0 = PASS.
 */
public class Demo1Contention {

    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0] : "http://localhost:8080";
        int clients = args.length > 1 ? Integer.parseInt(args[1]) : 30;
        String resource = args.length > 2 ? args[2] : "room:B1-101";
        String date = args.length > 3 ? args[3] : LocalDate.now().plusDays(1).toString();

        System.out.printf("Demo 1 - contention: %d students, %s on %s, server %s%n%n", clients, resource, date, base);

        List<Client> students = new ArrayList<>();
        for (int i = 1; i <= clients; i++) {
            students.add(Client.login(base, String.format("student%02d", i), "fairslot"));
        }
        Long slot = students.get(0).firstFreeSlot(resource, date);
        if (slot == null) {
            System.out.println("No free slot left on that day: pick another date. FAIL");
            System.exit(2);
        }
        System.out.printf("Target slot: %s at %s%n", resource, Client.hour(slot));

        // Everyone fires at once.
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        CountDownLatch go = new CountDownLatch(1);
        Map<String, Client.Response> results = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();
        for (Client s : students) {
            futures.add(pool.submit(() -> {
                go.await();
                results.put(s.user, s.post("/api/holds", "{\"resourceId\":\"" + resource + "\",\"slotStart\":" + slot + "}"));
                return null;
            }));
        }
        long t0 = System.nanoTime();
        go.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        pool.shutdown();

        List<String> winners = new ArrayList<>();
        Map<String, Integer> outcomes = new TreeMap<>();
        for (Map.Entry<String, Client.Response> e : results.entrySet()) {
            String outcome = e.getValue().status == 201 ? "hold granted" : e.getValue().field("error");
            outcomes.merge(outcome, 1, Integer::sum);
            if (e.getValue().status == 201) {
                winners.add(e.getKey());
            }
        }
        System.out.printf("%n%d simultaneous requests answered in %d ms:%n", clients, ms);
        outcomes.forEach((k, v) -> System.out.printf("  %-14s %3d%n", k, v));

        boolean pass = winners.size() == 1 && outcomes.getOrDefault("slot_taken", 0) == clients - 1;
        if (winners.size() == 1) {
            Client winner = students.stream().filter(s -> s.user.equals(winners.get(0))).findFirst().orElseThrow();
            String holdId = results.get(winner.user).field("hold_id");
            Client.Response confirm = winner.post("/api/holds/" + holdId + "/confirm", "");
            System.out.printf("%nWinner %s confirms: %s%n", winner.user, confirm.status == 200 ? "booked " + confirm.field("booking_id") : confirm.body);
            pass &= confirm.status == 200;
        }

        // The others join the waitlist; positions must be 1..N-1 with no gaps or repeats.
        List<Long> positions = new ArrayList<>();
        for (Client s : students) {
            if (!winners.contains(s.user)) {
                Client.Response w = s.post("/api/waitlist", "{\"resourceId\":\"" + resource + "\",\"slotStart\":" + slot + "}");
                if (w.status == 201) {
                    positions.add(Long.parseLong(w.field("position")));
                }
            }
        }
        positions.sort(null);
        boolean fifo = positions.size() == clients - 1;
        for (int i = 0; fifo && i < positions.size(); i++) {
            fifo = positions.get(i) == i + 1;
        }
        System.out.printf("%d students joined the waitlist, positions 1..%d: %s%n", positions.size(),
                positions.size(), fifo ? "OK" : "WRONG " + positions);
        pass &= fifo;

        System.out.println();
        System.out.println(pass ? "PASS: exactly one booking, everyone else on the waitlist." : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    /** Minimal JSON-over-HTTP client with its own cookie jar (= its own login session). */
    static final class Client {
        final String base;
        final String user;
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

        Client(String base, String user) {
            this.base = base;
            this.user = user;
        }

        static Client login(String base, String user, String password) throws Exception {
            Client c = new Client(base, user);
            Response r = c.post("/api/auth/login", "{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}");
            if (r.status != 200) {
                throw new IllegalStateException("Login failed for " + user + ": " + r.body);
            }
            return c;
        }

        Response post(String path, String json) throws Exception {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.body());
        }

        Response get(String path) throws Exception {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + path)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.body());
        }

        Long firstFreeSlot(String resource, String date) throws Exception {
            Response r = get("/api/resources/" + resource + "/availability?date=" + date);
            Matcher m = Pattern.compile("\\{\"start\":(\\d+),\"state\":\"free\"").matcher(r.body);
            return m.find() ? Long.parseLong(m.group(1)) : null;
        }

        static String hour(long ms) {
            return java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toString();
        }

        record Response(int status, String body) {
            String field(String name) {
                Matcher m = Pattern.compile("\"" + name + "\":\"?([^\",}]*)").matcher(body);
                return m.find() ? m.group(1) : null;
            }
        }
    }
}
