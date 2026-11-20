package it.unipi.dsmt.fairslot.web.backend;

import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpMbox;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Week 1 proof of concept: a real Erlang node running tools/stub-backend/fairslot_stub.erl,
 * driven from Java through JInterface. Skipped when {@code erl}/{@code erlc} are not on the PATH.
 */
class JInterfaceStubIntegrationTest {

    static final long HOUR = 3_600_000L;
    static final long SLOT = Instant.parse("2026-12-01T10:00:00Z").toEpochMilli();
    static final long STUB_HOLD_MS = 1500;
    static final String COOKIE = "fairslot-it-" + System.nanoTime();

    static Process erl;
    static JInterfaceBackend backend;
    static String erlNode;
    static final BlockingQueue<String> events = new ArrayBlockingQueue<>(100);

    @BeforeAll
    static void startErlangNode() throws Exception {
        Assumptions.assumeTrue(onPath("erl") && onPath("erlc"), "Erlang not installed: skipping JInterface test");

        Path stub = Path.of("..", "tools", "stub-backend", "fairslot_stub.erl").toAbsolutePath().normalize();
        Path out = Files.createTempDirectory("fairslot-stub");
        run(new ProcessBuilder("erlc", "-o", out.toString(), stub.toString()));

        String name = "fs_it_" + ProcessHandle.current().pid();
        ProcessBuilder pb = new ProcessBuilder("erl", "-sname", name, "-setcookie", COOKIE, "-noshell",
                "-pa", out.toString(), "-eval", "fairslot_stub:start()");
        pb.environment().put("FAIRSLOT_STUB_HOLD_MS", String.valueOf(STUB_HOLD_MS));
        pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        erl = pb.start();

        // Same short host name rule as erl -sname and JInterface's OtpLocalNode.
        String host = InetAddress.getLocalHost().getHostName();
        erlNode = name + "@" + (host.contains(".") ? host.substring(0, host.indexOf('.')) : host);

        // OtpNode registers with EPMD when it is created, and EPMD is started by erl:
        // retry until erl is up, then until the stub's gateway answers.
        long deadline = System.currentTimeMillis() + 20_000;
        while (backend == null) {
            try {
                backend = new JInterfaceBackend("fs_web_it_" + ProcessHandle.current().pid(), COOKIE, List.of(erlNode), 2000);
            } catch (IOException e) {
                waitOrFail(deadline, e);
            }
        }
        backend.addEventListener((type, data) -> events.add(type + ":" + data.getOrDefault("user", "")));
        while (true) {
            try {
                backend.refreshPartitionMap();
                break;
            } catch (BackendException e) {
                waitOrFail(deadline, e);
            }
        }
    }

    private static void waitOrFail(long deadline, Exception cause) throws InterruptedException {
        if (System.currentTimeMillis() > deadline) {
            throw new AssertionError("Erlang stub node " + erlNode + " did not come up", cause);
        }
        Thread.sleep(300);
    }

    @AfterAll
    static void stopErlangNode() throws Exception {
        if (backend != null) {
            try {
                OtpMbox mbox = backend.otpNode().createMbox();
                mbox.send(JInterfaceBackend.GATEWAY, erlNode, new OtpErlangAtom("stop_node"));
                mbox.close();
            } catch (Exception ignored) {
                // fall through to killing the process
            }
            backend.close();
        }
        if (erl != null) {
            erl.waitFor(5, TimeUnit.SECONDS);
            erl.descendants().forEach(ProcessHandle::destroyForcibly);
            erl.destroyForcibly();
        }
    }

    @Test
    void javaJoinsAsHiddenNode() throws Exception {
        Map<String, Object> nodes = backend.call("dbg-1", "test", new Op("debug_nodes", List.of()));
        assertTrue(((List<?>) nodes.get("hidden")).contains(backend.nodeName()), nodes.toString());
        assertFalse(((List<?>) nodes.get("visible")).contains(backend.nodeName()), nodes.toString());
    }

    @Test
    void holdAndConfirmThroughErlang() throws Exception {
        long slot = SLOT + 2 * HOUR;
        String holdId = (String) backend.call("r-hold", "alice", Op.hold("room:B1-101", slot)).get("hold_id");
        Map<String, Object> booking = backend.call("r-confirm", "alice", Op.confirm(holdId));
        assertTrue(((String) booking.get("booking_id")).startsWith("b-"));

        List<?> slots = (List<?>) backend.call("r-av", "bob", Op.availability("room:B1-101", SLOT - 10 * HOUR)).get("slots");
        assertTrue(slots.contains(Map.of("start", slot, "state", "booked")), slots.toString());
    }

    @Test
    void contentionThroughErlangOnlyOneWins() throws Exception {
        long slot = SLOT + 3 * HOUR;
        ExecutorService pool = Executors.newFixedThreadPool(20);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger taken = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String user = "u" + i;
            futures.add(pool.submit(() -> {
                try {
                    backend.call("c-" + user, user, Op.hold("room:B1-102", slot));
                    ok.incrementAndGet();
                } catch (BackendException e) {
                    if (e.reason().equals("slot_taken")) {
                        taken.incrementAndGet();
                    }
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        assertEquals(1, ok.get());
        assertEquals(39, taken.get());
    }

    @Test
    void retriedRequestIdIsNotAppliedTwice() throws Exception {
        long slot = SLOT + 4 * HOUR;
        Map<String, Object> first = backend.call("dup-1", "carol", Op.hold("room:B2-201", slot));
        Map<String, Object> again = backend.call("dup-1", "carol", Op.hold("room:B2-201", slot));
        assertEquals(first, again);
    }

    @Test
    void errorsArriveAsBackendExceptions() {
        BackendException e = assertThrows(BackendException.class,
                () -> backend.call("bad-1", "dave", Op.hold("room:B1-101", SLOT + 1)));
        assertEquals("bad_request", e.reason());
    }

    @Test
    void expiredHoldIsPushedAsEvent() throws Exception {
        backend.call("ev-1", "erin", Op.hold("room:B2-202", SLOT + 5 * HOUR));
        long deadline = System.currentTimeMillis() + STUB_HOLD_MS + 5000;
        String found = null;
        while (found == null && System.currentTimeMillis() < deadline) {
            String e = events.poll(500, TimeUnit.MILLISECONDS);
            if ("hold_expired:erin".equals(e)) {
                found = e;
            }
        }
        assertNotNull(found, "no hold_expired event pushed from Erlang");
    }

    @Test
    void measuresRoundTripLatency() throws Exception {
        int n = 200;
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            backend.call("lat-" + i, "lat", Op.availability("room:B1-101", SLOT));
        }
        double avgMs = (System.nanoTime() - start) / 1e6 / n;
        System.out.printf("JInterface round trip (availability, 13 slots): %.2f ms average over %d calls%n", avgMs, n);
        assertTrue(avgMs < 50, "round trip too slow: " + avgMs + " ms");
    }

    // ------------------------------------------------------------------ helpers

    private static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (new File(dir, exe).canExecute() || new File(dir, exe + ".exe").canExecute()) {
                return true;
            }
        }
        return false;
    }

    private static void run(ProcessBuilder pb) throws Exception {
        Process p = pb.redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new AssertionError(String.join(" ", pb.command()) + " failed:\n" + output);
        }
    }
}
