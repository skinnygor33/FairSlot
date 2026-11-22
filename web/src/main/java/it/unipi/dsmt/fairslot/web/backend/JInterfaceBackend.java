package it.unipi.dsmt.fairslot.web.backend;

import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangBinary;
import com.ericsson.otp.erlang.OtpErlangDecodeException;
import com.ericsson.otp.erlang.OtpErlangExit;
import com.ericsson.otp.erlang.OtpErlangLong;
import com.ericsson.otp.erlang.OtpErlangMap;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangTuple;
import com.ericsson.otp.erlang.OtpMbox;
import com.ericsson.otp.erlang.OtpNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Talks to the Erlang cluster through JInterface: the web tier is a hidden Erlang node
 * that sends {@code {fs_req, ...}} to {@code fairslot_gateway} on the owning node and waits
 * for {@code {fs_reply, ...}} on a mailbox of its own (docs/message-contract.md).
 *
 * <p>Each call uses a fresh mailbox, so concurrent servlet threads never see each other's
 * replies. On a timeout or a routing error it refreshes the partition map and resends once
 * with the same request ID; the back-end's duplicate detection makes that safe.
 */
public class JInterfaceBackend implements BookingBackend {

    static final String GATEWAY = "fairslot_gateway";
    static final String EVENTS_MAILBOX = "events";
    private static final Set<String> RETRY_REASONS = Set.of("wrong_owner", "stale_epoch", "no_majority");
    private static final Logger LOG = Logger.getLogger(JInterfaceBackend.class.getName());

    private final OtpNode node;
    private final List<String> erlangNodes;
    private final long timeoutMs;
    private final OtpMbox eventsMbox;
    private final Thread eventsThread;
    private final List<EventListener> listeners = new CopyOnWriteArrayList<>();
    private volatile PartitionMap map;
    private volatile boolean closed;

    /**
     * @param webNodeName this node's name, e.g. {@code fairslot_web} (host added by JInterface)
     *                    or {@code fairslot_web@10.0.0.4} for long names
     * @param erlangNodes back-end nodes to ask for the partition map, e.g. {@code fairslot_a@vm1}
     */
    public JInterfaceBackend(String webNodeName, String cookie, List<String> erlangNodes, long timeoutMs)
            throws IOException {
        if (erlangNodes.isEmpty()) {
            throw new IllegalArgumentException("No Erlang nodes configured");
        }
        this.node = new OtpNode(webNodeName, cookie);
        this.erlangNodes = List.copyOf(erlangNodes);
        this.timeoutMs = timeoutMs;
        this.eventsMbox = node.createMbox(EVENTS_MAILBOX);
        this.eventsThread = new Thread(this::receiveEvents, "fairslot-erlang-events");
        this.eventsThread.setDaemon(true);
        this.eventsThread.start();
        LOG.info(() -> "JInterface node " + node.node() + " started; back-end nodes " + this.erlangNodes);
    }

    /** Full name of this Java node, e.g. {@code fairslot_web@myhost}. */
    public String nodeName() {
        return node.node();
    }

    /** The underlying JInterface node (tests and diagnostics). */
    OtpNode otpNode() {
        return node;
    }

    @Override
    public void addEventListener(EventListener listener) {
        listeners.add(listener);
    }

    @Override
    public Map<String, Object> call(String requestId, String userId, Op op) throws BackendException {
        PartitionMap current = partitionMap();
        BackendException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0) {
                current = refreshPartitionMap();
            }
            String target = op.name().equals("book_bundle") ? current.coordinator() : current.nodeFor(op.resourceId());
            OtpErlangObject reply = request(target, requestId, current.epoch(), userId, op);
            if (reply == null) {
                last = new BackendException(BackendException.UNAVAILABLE, "no reply from " + target);
                continue;
            }
            try {
                return result(reply);
            } catch (BackendException e) {
                if (!RETRY_REASONS.contains(e.reason())) {
                    throw e;
                }
                last = e;
            }
        }
        throw last;
    }

    /** Sends one request and waits for its reply; null on timeout or if the node is down. */
    OtpErlangObject request(String target, String requestId, long epoch, String userId, Op op) {
        OtpMbox mbox = node.createMbox();
        try {
            OtpErlangObject msg = new OtpErlangTuple(new OtpErlangObject[] {
                    new OtpErlangAtom("fs_req"), binary(requestId), new OtpErlangLong(epoch),
                    mbox.self(), binary(userId), ErlangCodec.encodeOp(op)});
            mbox.send(GATEWAY, target, msg);
            long deadline = System.currentTimeMillis() + timeoutMs;
            long left;
            while ((left = deadline - System.currentTimeMillis()) > 0) {
                OtpErlangObject in = mbox.receive(left);
                if (in == null) {
                    return null;
                }
                if (isReplyTo(in, requestId)) {
                    return ((OtpErlangTuple) in).elementAt(2);
                }
                LOG.warning(() -> "Ignoring unexpected message " + in);
            }
            return null;
        } catch (OtpErlangExit | OtpErlangDecodeException e) {
            LOG.log(Level.WARNING, "Request " + requestId + " to " + target + " failed", e);
            return null;
        } finally {
            mbox.close();
        }
    }

    private static boolean isReplyTo(OtpErlangObject in, String requestId) {
        if (!(in instanceof OtpErlangTuple)) {
            return false;
        }
        OtpErlangTuple t = (OtpErlangTuple) in;
        return t.arity() == 3 && new OtpErlangAtom("fs_reply").equals(t.elementAt(0))
                && binary(requestId).equals(t.elementAt(1));
    }

    /** Turns {@code {ok, Map}} into the map and {@code {error, Reason}} into an exception. */
    static Map<String, Object> result(OtpErlangObject reply) throws BackendException {
        if (reply instanceof OtpErlangTuple && ((OtpErlangTuple) reply).arity() == 2) {
            OtpErlangTuple t = (OtpErlangTuple) reply;
            OtpErlangObject tag = t.elementAt(0);
            if (new OtpErlangAtom("ok").equals(tag) && t.elementAt(1) instanceof OtpErlangMap) {
                return ErlangCodec.decodeMap((OtpErlangMap) t.elementAt(1));
            }
            if (new OtpErlangAtom("error").equals(tag)) {
                Object reason = ErlangCodec.decode(t.elementAt(1));
                if (reason instanceof List && !((List<?>) reason).isEmpty()) {   // {Reason, Detail...}
                    List<?> parts = (List<?>) reason;
                    Object detail = parts.size() == 2 ? parts.get(1) : parts.subList(1, parts.size());
                    throw new BackendException(String.valueOf(parts.get(0)), detail);
                }
                throw new BackendException(String.valueOf(reason));
            }
        }
        throw new BackendException("bad_reply", String.valueOf(reply));
    }

    PartitionMap partitionMap() throws BackendException {
        PartitionMap m = map;
        return m != null ? m : refreshPartitionMap();
    }

    /** Asks the configured nodes, in order, until one answers. */
    synchronized PartitionMap refreshPartitionMap() throws BackendException {
        for (String candidate : erlangNodes) {
            OtpErlangObject reply = request(candidate, "map-" + UUID.randomUUID(), 0, "web", Op.partitionMap());
            if (reply != null) {
                try {
                    PartitionMap fresh = PartitionMap.fromReply(result(reply));
                    map = fresh;
                    LOG.info(() -> "Partition map epoch " + fresh.epoch() + " from " + candidate);
                    return fresh;
                } catch (BackendException | RuntimeException e) {
                    LOG.log(Level.WARNING, "Bad partition map from " + candidate, e);
                }
            }
        }
        throw new BackendException(BackendException.UNAVAILABLE, "no Erlang node answered " + erlangNodes);
    }

    /** Forwards {@code {fs_event, Type, Map}} messages to the listeners until closed. */
    private void receiveEvents() {
        while (!closed) {
            try {
                OtpErlangObject in = eventsMbox.receive();
                if (in instanceof OtpErlangTuple && ((OtpErlangTuple) in).arity() == 3
                        && new OtpErlangAtom("fs_event").equals(((OtpErlangTuple) in).elementAt(0))
                        && ((OtpErlangTuple) in).elementAt(2) instanceof OtpErlangMap) {
                    OtpErlangTuple t = (OtpErlangTuple) in;
                    String type = ((OtpErlangAtom) t.elementAt(1)).atomValue();
                    Map<String, Object> data = ErlangCodec.decodeMap((OtpErlangMap) t.elementAt(2));
                    if (type.equals("partition_moved")) {
                        map = null;   // failover: the next call fetches the new owner (contract §4)
                    }
                    for (EventListener l : listeners) {
                        try {
                            l.onEvent(type, data);
                        } catch (RuntimeException e) {
                            LOG.log(Level.WARNING, "Event listener failed on " + type, e);
                        }
                    }
                } else {
                    LOG.warning(() -> "Ignoring unexpected event " + in);
                }
            } catch (OtpErlangExit | OtpErlangDecodeException e) {
                if (!closed) {
                    LOG.log(Level.WARNING, "Events mailbox error", e);
                }
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Bad event", e);
            }
        }
    }

    private static OtpErlangBinary binary(String s) {
        return new OtpErlangBinary(s.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        closed = true;
        eventsMbox.close();
        node.close();
    }
}
