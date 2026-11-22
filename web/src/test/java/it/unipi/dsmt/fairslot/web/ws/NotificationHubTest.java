package it.unipi.dsmt.fairslot.web.ws;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationHubTest {

    final NotificationHub hub = new NotificationHub();
    final List<String> aliceTab1 = new CopyOnWriteArrayList<>();
    final List<String> aliceTab2 = new CopyOnWriteArrayList<>();
    final List<String> bob = new CopyOnWriteArrayList<>();

    @AfterEach
    void close() {
        hub.close();
    }

    void connectAll() {
        hub.register("c1", "alice", "http-a", aliceTab1::add);
        hub.register("c2", "alice", "http-a2", aliceTab2::add);
        hub.register("c3", "bob", "http-b", bob::add);
    }

    /** Sends happen on the hub's thread: wait until they have arrived. */
    static void await(List<String> list, int size) throws InterruptedException {
        for (int i = 0; i < 100 && list.size() < size; i++) {
            Thread.sleep(20);
        }
    }

    @Test
    void userEventsGoOnlyToThatUsersTabs() throws Exception {
        connectAll();
        hub.onEvent("offer", Map.of("user", "alice", "offer_id", "o-1"));
        await(aliceTab1, 1);
        await(aliceTab2, 1);
        Thread.sleep(100);
        assertEquals(1, aliceTab1.size());
        assertTrue(aliceTab1.get(0).startsWith("{\"type\":\"offer\""), aliceTab1.get(0));
        assertTrue(aliceTab1.get(0).contains("\"offer_id\":\"o-1\""), aliceTab1.get(0));
        assertEquals(1, aliceTab2.size());
        assertTrue(bob.isEmpty());
    }

    @Test
    void eventsWithoutUserGoToEveryone() throws Exception {
        connectAll();
        hub.onEvent("slot_freed", Map.of("resource_id", "room:B1-101", "slot_start", 7L));
        await(bob, 1);
        await(aliceTab1, 1);
        assertTrue(bob.get(0).contains("\"type\":\"slot_freed\""));
        assertEquals(1, aliceTab1.size());
    }

    @Test
    void internalEventsAreNotForwarded() throws Exception {
        connectAll();
        hub.onEvent("partition_moved", Map.of("epoch", 2L));
        Thread.sleep(100);
        assertTrue(aliceTab1.isEmpty() && bob.isEmpty());
    }

    @Test
    void brokenClientIsDropped() throws Exception {
        hub.register("dead", "alice", "http-x", text -> {
            throw new IllegalStateException("socket closed");
        });
        hub.onEvent("slot_freed", Map.of());
        for (int i = 0; i < 100 && hub.size() > 0; i++) {
            Thread.sleep(20);
        }
        assertEquals(0, hub.size());
    }

    @Test
    void logoutForgetsThatSessionsSockets() {
        connectAll();
        assertEquals(java.util.Set.of("c1"), hub.forgetHttpSession("http-a"));
        assertEquals(2, hub.size());
    }
}
