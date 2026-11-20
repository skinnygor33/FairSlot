package it.unipi.dsmt.fairslot.web.backend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static it.unipi.dsmt.fairslot.web.backend.MockBackend.HOUR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockBackendTest {

    static final String ROOM = "room:B1-101";
    static final String PROJ = "equip:proj-01";
    /** Mon 23 Nov 2026 10:00 UTC; slots below are later the same day. */
    static final long NOW = Instant.parse("2026-11-23T10:00:00Z").toEpochMilli();
    static final long SLOT = NOW + 4 * HOUR;

    MutableClock clock;
    MockBackend backend;
    int req;
    List<String> events;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        backend = new MockBackend(List.of(ROOM, PROJ), clock);
        events = new ArrayList<>();
        backend.addEventListener((type, data) -> events.add(type + ":" + data.get("user")));
    }

    Map<String, Object> call(String user, Op op) throws BackendException {
        return backend.call("r" + req++, user, op);
    }

    String reason(Callable<?> c) {
        BackendException e = assertThrows(BackendException.class, c::call);
        return e.reason();
    }

    @Test
    void holdThenConfirm() throws Exception {
        String holdId = (String) call("alice", Op.hold(ROOM, SLOT)).get("hold_id");
        Map<String, Object> booking = call("alice", Op.confirm(holdId));
        assertTrue(((String) booking.get("booking_id")).startsWith("b-"));
        assertEquals("taken", stateFor("bob"));
        assertEquals("mine", stateFor("alice"));
    }

    @Test
    void contentionOnlyOneHoldWins() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger taken = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String user = "u" + i;
            futures.add(pool.submit(() -> {
                try {
                    backend.call("c-" + user, user, Op.hold(ROOM, SLOT));
                    ok.incrementAndGet();
                } catch (BackendException e) {
                    assertEquals("slot_taken", e.reason());
                    taken.incrementAndGet();
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        assertEquals(1, ok.get());
        assertEquals(49, taken.get());
    }

    @Test
    void duplicateRequestIdIsAppliedOnce() throws Exception {
        Map<String, Object> first = backend.call("same", "alice", Op.hold(ROOM, SLOT));
        Map<String, Object> retry = backend.call("same", "alice", Op.hold(ROOM, SLOT));
        assertEquals(first, retry);
        assertEquals(1L, backend.call("q", "alice", Op.quota()).get("used_hours"));
    }

    @Test
    void weeklyQuotaIsEnforced() throws Exception {
        for (int h = 0; h < MockBackend.QUOTA_HOURS; h++) {
            call("alice", Op.hold(ROOM, SLOT + h * HOUR));
        }
        assertEquals("quota_exceeded", reason(() -> call("alice", Op.hold(PROJ, SLOT))));
    }

    @Test
    void expiredHoldFreesSlotAndQuota() throws Exception {
        String holdId = (String) call("alice", Op.hold(ROOM, SLOT)).get("hold_id");
        clock.advance(MockBackend.HOLD_MS + 1);
        assertEquals("hold_expired", reason(() -> call("alice", Op.confirm(holdId))));
        assertEquals(0L, call("alice", Op.quota()).get("used_hours"));
        assertTrue(events.contains("hold_expired:alice"));
        call("bob", Op.hold(ROOM, SLOT));
    }

    @Test
    void cancelledSlotIsOfferedToFirstOnWaitlist() throws Exception {
        String holdId = (String) call("alice", Op.hold(ROOM, SLOT)).get("hold_id");
        String bookingId = (String) call("alice", Op.confirm(holdId)).get("booking_id");
        assertEquals(1L, call("bob", Op.joinWaitlist(ROOM, SLOT)).get("position"));
        assertEquals(2L, call("carol", Op.joinWaitlist(ROOM, SLOT)).get("position"));

        call("alice", Op.cancel(bookingId));

        assertTrue(events.contains("offer:bob"));
        assertEquals("slot_taken", reason(() -> call("dave", Op.hold(ROOM, SLOT))));
    }

    @Test
    void missedCheckInReleasesSlot() throws Exception {
        String holdId = (String) call("alice", Op.hold(ROOM, SLOT)).get("hold_id");
        String bookingId = (String) call("alice", Op.confirm(holdId)).get("booking_id");
        assertEquals("too_early", reason(() -> call("alice", Op.checkIn(bookingId))));
        clock.set(SLOT + MockBackend.CHECKIN_WINDOW_MS + 1);
        assertEquals("not_found", reason(() -> call("alice", Op.checkIn(bookingId))));
        assertTrue(events.contains("checkin_missed:alice"));
    }

    @Test
    void bundleIsAllOrNothing() throws Exception {
        call("bob", Op.hold(PROJ, SLOT));
        List<Op.BundleItem> items = List.of(new Op.BundleItem(ROOM, SLOT), new Op.BundleItem(PROJ, SLOT));
        assertEquals("slot_taken", reason(() -> call("alice", Op.bookBundle(items))));
        assertEquals("free", stateFor("alice"));   // the room was not booked either

        List<Op.BundleItem> other = List.of(new Op.BundleItem(ROOM, SLOT + HOUR), new Op.BundleItem(PROJ, SLOT + HOUR));
        Map<String, Object> ok = call("alice", Op.bookBundle(other));
        assertEquals(2, ((List<?>) ok.get("booking_ids")).size());
    }

    @Test
    void onlyHolderCanConfirm() throws Exception {
        String holdId = (String) call("alice", Op.hold(ROOM, SLOT)).get("hold_id");
        assertEquals("not_holder", reason(() -> call("bob", Op.confirm(holdId))));
    }

    /** State of SLOT in ROOM as seen by a user, with "held"/"booked" collapsed to "taken". */
    private String stateFor(String user) throws BackendException {
        long dayStart = SLOT - (SLOT % (24 * HOUR));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slots = (List<Map<String, Object>>) call(user, Op.availability(ROOM, dayStart)).get("slots");
        String state = slots.stream().filter(s -> s.get("start").equals(SLOT)).findFirst().orElseThrow()
                .get("state").toString();
        return state.equals("held") || state.equals("booked") ? "taken" : state;
    }

    static final class MutableClock extends Clock {
        private long millis;

        MutableClock(long millis) {
            this.millis = millis;
        }

        void advance(long ms) {
            millis += ms;
        }

        void set(long ms) {
            millis = ms;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
