package it.unipi.dsmt.fairslot.web.backend;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Single-process stand-in for the Erlang back-end, following the policies of the proposal
 * (5 min holds, 10 h weekly quota, 10 min waitlist offers, 15 min check-in window).
 * Lets the web tier and the UI be built and tested before the real back-end exists.
 * All state is in memory and guarded by one lock: no distribution, no replication.
 */
public class MockBackend implements BookingBackend {

    static final long HOUR = 3_600_000L;
    static final long HOLD_MS = 5 * 60_000L;
    static final long OFFER_MS = 10 * 60_000L;
    static final long CHECKIN_WINDOW_MS = 15 * 60_000L;
    static final int QUOTA_HOURS = 10;
    static final int FIRST_HOUR = 8;
    static final int LAST_HOUR = 21;

    private final Clock clock;
    private final Set<String> resources;
    private final List<EventListener> listeners = new CopyOnWriteArrayList<>();

    private final Map<String, Slot> slots = new HashMap<>();          // "resId|start" -> slot
    private final Map<String, Slot> holds = new HashMap<>();          // holdId -> slot
    private final Map<String, Slot> bookings = new HashMap<>();       // bookingId -> slot
    private final Map<String, Slot> offers = new HashMap<>();         // offerId -> slot
    private final Map<String, String> expired = new HashMap<>();      // hold/offer id -> reason
    private final Map<String, Integer> quotaUsed = new HashMap<>();   // user -> hours
    private final Map<String, Object> done = new HashMap<>();         // requestId -> Map or BackendException
    private final List<Runnable> pendingEvents = new ArrayList<>();
    private long nextId = 1;
    private ScheduledExecutorService timers;

    /** A slot that is not free. Free slots have no entry. */
    private static final class Slot {
        final String resourceId;
        final long start;
        String user;
        String holdId;
        long holdExpires;
        String bookingId;
        boolean checkedIn;
        String offerId;
        long offerExpires;
        final Deque<String> waitlist = new ArrayDeque<>();

        Slot(String resourceId, long start) {
            this.resourceId = resourceId;
            this.start = start;
        }

        boolean taken() {
            return holdId != null || bookingId != null || offerId != null;
        }
    }

    public MockBackend(Collection<String> resourceIds, Clock clock) {
        this.resources = Set.copyOf(resourceIds);
        this.clock = clock;
    }

    @Override
    public void addEventListener(EventListener listener) {
        listeners.add(listener);
    }

    /**
     * Fires expired holds, offers and check-ins every second, like the back-end's timers,
     * so their events reach the browsers on time. Without it (unit tests) they fire on the
     * next call.
     */
    public synchronized void startTimers() {
        if (timers == null) {
            timers = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "fairslot-mock-timers");
                t.setDaemon(true);
                return t;
            });
            timers.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
        }
    }

    private void tick() {
        try {
            synchronized (this) {
                sweep(clock.millis());
            }
        } finally {
            flushEvents();
        }
    }

    @Override
    public synchronized void close() {
        if (timers != null) {
            timers.shutdownNow();
        }
    }

    @Override
    public Map<String, Object> call(String requestId, String userId, Op op) throws BackendException {
        Map<String, Object> result;
        try {
            synchronized (this) {
                result = callLocked(requestId, userId, op);
            }
        } finally {
            flushEvents();
        }
        return result;
    }

    private Map<String, Object> callLocked(String requestId, String userId, Op op) throws BackendException {
        long now = clock.millis();
        sweep(now);
        Object previous = done.get(requestId);
        if (previous instanceof BackendException) {
            throw (BackendException) previous;
        }
        if (previous != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> stored = (Map<String, Object>) previous;
            return stored;
        }
        try {
            Map<String, Object> result = apply(userId, op, now);
            if (!op.name().equals("availability") && !op.name().equals("quota")) {
                done.put(requestId, result);
            }
            return result;
        } catch (BackendException e) {
            done.put(requestId, e);
            throw e;
        }
    }

    private Map<String, Object> apply(String user, Op op, long now) throws BackendException {
        switch (op.name()) {
            case "availability":
                return availability(user, op.stringArg(0), op.longArg(1));
            case "hold":
                return hold(user, op.stringArg(0), op.longArg(1), now);
            case "release":
                return release(user, op.stringArg(0));
            case "confirm":
                return confirm(user, op.stringArg(0));
            case "cancel":
                return cancel(user, op.stringArg(0), now);
            case "check_in":
                return checkIn(user, op.stringArg(0), now);
            case "join_waitlist":
                return joinWaitlist(user, op.stringArg(0), op.longArg(1));
            case "accept_offer":
                return acceptOffer(user, op.stringArg(0));
            case "decline_offer":
                return declineOffer(user, op.stringArg(0), now);
            case "quota":
                return quota(user, now);
            case "book_bundle":
                return bookBundle(user, op.args().get(0), now);
            case "partition_map":
                return Map.of("epoch", 0L, "coordinator", "mock", "partitions", Map.of(), "resources", Map.of());
            default:
                throw new BackendException("bad_request", "unknown op " + op.name());
        }
    }

    // ---------------------------------------------------------------- operations

    private Map<String, Object> availability(String user, String resId, long dayStart) throws BackendException {
        checkResource(resId);
        List<Map<String, Object>> list = new ArrayList<>();
        for (int h = FIRST_HOUR; h < LAST_HOUR; h++) {
            long start = dayStart + h * HOUR;
            Slot s = slots.get(key(resId, start));
            String state;
            if (s == null || !s.taken()) {
                state = "free";
            } else if (user.equals(s.user)) {
                state = "mine";
            } else {
                state = s.bookingId != null ? "booked" : "held";
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("start", start);
            m.put("state", state);
            if (state.equals("mine")) {
                if (s.holdId != null) {
                    m.put("hold_id", s.holdId);
                    m.put("expires_at", s.holdExpires);
                } else if (s.bookingId != null) {
                    m.put("booking_id", s.bookingId);
                } else {
                    m.put("offer_id", s.offerId);
                    m.put("expires_at", s.offerExpires);
                }
            }
            list.add(m);
        }
        return Map.of("slots", list);
    }

    private Map<String, Object> hold(String user, String resId, long start, long now) throws BackendException {
        checkResource(resId);
        checkSlotStart(start);
        Slot s = slots.computeIfAbsent(key(resId, start), k -> new Slot(resId, start));
        if (s.taken()) {
            throw new BackendException("slot_taken");
        }
        reserveQuota(user, 1);
        s.user = user;
        s.holdId = "h-" + nextId++;
        s.holdExpires = now + HOLD_MS;
        holds.put(s.holdId, s);
        event("slot_taken", Map.of("resource_id", resId, "slot_start", start));
        return Map.of("hold_id", s.holdId, "expires_at", s.holdExpires);
    }

    private Map<String, Object> release(String user, String holdId) throws BackendException {
        Slot s = holds.get(holdId);
        if (s == null) {
            throw new BackendException("not_found");
        }
        requireOwner(s, user);
        holds.remove(holdId);
        s.holdId = null;
        quotaUsed.merge(user, -1, Integer::sum);
        freed(s, clock.millis());
        return Map.of();
    }

    private Map<String, Object> confirm(String user, String holdId) throws BackendException {
        Slot s = holds.get(holdId);
        if (s == null) {
            throw new BackendException(expired.containsKey(holdId) ? "hold_expired" : "not_found");
        }
        requireOwner(s, user);
        holds.remove(holdId);
        s.holdId = null;
        s.bookingId = "b-" + nextId++;
        bookings.put(s.bookingId, s);
        return Map.of("booking_id", s.bookingId);
    }

    private Map<String, Object> cancel(String user, String bookingId, long now) throws BackendException {
        Slot s = bookings.get(bookingId);
        if (s == null) {
            throw new BackendException("not_found");
        }
        requireOwner(s, user);
        bookings.remove(bookingId);
        s.bookingId = null;
        if (s.start > now) {
            quotaUsed.merge(user, -1, Integer::sum);
        }
        freed(s, now);
        return Map.of();
    }

    private Map<String, Object> checkIn(String user, String bookingId, long now) throws BackendException {
        Slot s = bookings.get(bookingId);
        if (s == null) {
            throw new BackendException("not_found");
        }
        requireOwner(s, user);
        if (now < s.start - CHECKIN_WINDOW_MS) {
            throw new BackendException("too_early");
        }
        if (now > s.start + CHECKIN_WINDOW_MS) {
            throw new BackendException("too_late");
        }
        s.checkedIn = true;
        return Map.of();
    }

    private Map<String, Object> joinWaitlist(String user, String resId, long start) throws BackendException {
        checkResource(resId);
        checkSlotStart(start);
        Slot s = slots.get(key(resId, start));
        if (s == null || !s.taken()) {
            throw new BackendException("slot_free");
        }
        if (user.equals(s.user) || s.waitlist.contains(user)) {
            throw new BackendException("already_waiting");
        }
        s.waitlist.addLast(user);
        return Map.of("position", (long) s.waitlist.size());
    }

    private Map<String, Object> acceptOffer(String user, String offerId) throws BackendException {
        Slot s = offers.get(offerId);
        if (s == null) {
            throw new BackendException(expired.containsKey(offerId) ? "offer_expired" : "not_found");
        }
        requireOwner(s, user);
        reserveQuota(user, 1);
        offers.remove(offerId);
        s.offerId = null;
        s.bookingId = "b-" + nextId++;
        bookings.put(s.bookingId, s);
        return Map.of("booking_id", s.bookingId);
    }

    private Map<String, Object> declineOffer(String user, String offerId, long now) throws BackendException {
        Slot s = offers.get(offerId);
        if (s == null) {
            throw new BackendException("not_found");
        }
        requireOwner(s, user);
        offers.remove(offerId);
        s.offerId = null;
        freed(s, now);
        return Map.of();
    }

    private Map<String, Object> quota(String user, long now) {
        long weekStart = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli();
        return Map.of("used_hours", (long) quotaUsed.getOrDefault(user, 0),
                "limit_hours", (long) QUOTA_HOURS, "week_start", weekStart);
    }

    private Map<String, Object> bookBundle(String user, Object itemsArg, long now) throws BackendException {
        List<?> items = (List<?>) itemsArg;
        List<Slot> wanted = new ArrayList<>();
        for (Object o : items) {
            List<Object> t = ((Op.Tuple) o).elements();
            String resId = (String) t.get(0);
            long start = (Long) t.get(1);
            checkResource(resId);
            checkSlotStart(start);
            Slot s = slots.computeIfAbsent(key(resId, start), k -> new Slot(resId, start));
            if (s.taken()) {
                throw new BackendException("slot_taken", resId);
            }
            wanted.add(s);
        }
        reserveQuota(user, wanted.size());
        List<String> ids = new ArrayList<>();
        for (Slot s : wanted) {
            s.user = user;
            s.bookingId = "b-" + nextId++;
            bookings.put(s.bookingId, s);
            ids.add(s.bookingId);
            event("slot_taken", Map.of("resource_id", s.resourceId, "slot_start", s.start));
        }
        return Map.of("bundle_id", "x-" + nextId++, "booking_ids", ids);
    }

    // ---------------------------------------------------------------- timers and helpers

    /** Fires every timer whose deadline has passed (the real back-end does this with timers). */
    private void sweep(long now) {
        for (Slot s : new ArrayList<>(holds.values())) {
            if (s.holdExpires <= now) {
                holds.remove(s.holdId);
                expired.put(s.holdId, "hold_expired");
                event("hold_expired", Map.of("user", s.user, "hold_id", s.holdId));
                s.holdId = null;
                quotaUsed.merge(s.user, -1, Integer::sum);
                freed(s, now);
            }
        }
        for (Slot s : new ArrayList<>(offers.values())) {
            if (s.offerExpires <= now) {
                offers.remove(s.offerId);
                expired.put(s.offerId, "offer_expired");
                event("offer_expired", Map.of("user", s.user, "offer_id", s.offerId));
                s.offerId = null;
                freed(s, now);
            }
        }
        for (Slot s : new ArrayList<>(bookings.values())) {
            if (!s.checkedIn && now > s.start + CHECKIN_WINDOW_MS) {
                bookings.remove(s.bookingId);
                event("checkin_missed", Map.of("user", s.user, "booking_id", s.bookingId));
                s.bookingId = null;
                freed(s, now);
            }
        }
    }

    /** The slot became free: offer it to the first waiting user, or announce it. */
    private void freed(Slot s, long now) {
        s.user = null;
        if (now < s.start + CHECKIN_WINDOW_MS && !s.waitlist.isEmpty()) {
            s.user = s.waitlist.pollFirst();
            s.offerId = "o-" + nextId++;
            s.offerExpires = now + OFFER_MS;
            offers.put(s.offerId, s);
            event("offer", Map.of("user", s.user, "offer_id", s.offerId, "resource_id", s.resourceId,
                    "slot_start", s.start, "expires_at", s.offerExpires));
        } else {
            event("slot_freed", Map.of("resource_id", s.resourceId, "slot_start", s.start));
        }
    }

    private void reserveQuota(String user, int hours) throws BackendException {
        if (quotaUsed.getOrDefault(user, 0) + hours > QUOTA_HOURS) {
            throw new BackendException("quota_exceeded");
        }
        quotaUsed.merge(user, hours, Integer::sum);
    }

    private void checkResource(String resId) throws BackendException {
        if (!resources.contains(resId)) {
            throw new BackendException("not_found");
        }
    }

    private static void checkSlotStart(long start) throws BackendException {
        if (start % HOUR != 0) {
            throw new BackendException("bad_request", "slot start must be on the hour");
        }
    }

    private static void requireOwner(Slot s, String user) throws BackendException {
        if (!user.equals(s.user)) {
            throw new BackendException("not_holder");
        }
    }

    private static String key(String resId, long start) {
        return resId + "|" + start;
    }

    /** Events are queued under the lock and delivered after it is released. */
    private void event(String type, Map<String, Object> data) {
        pendingEvents.add(() -> listeners.forEach(l -> l.onEvent(type, data)));
    }

    private void flushEvents() {
        List<Runnable> toRun;
        synchronized (this) {
            toRun = new ArrayList<>(pendingEvents);
            pendingEvents.clear();
        }
        toRun.forEach(Runnable::run);
    }
}
