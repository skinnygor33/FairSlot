package it.unipi.dsmt.fairslot.web.backend;

import java.util.ArrayList;
import java.util.List;

/**
 * One back-end operation. It is sent to Erlang as the tuple {@code {Name, Arg1, Arg2, ...}}
 * (docs/message-contract.md §3). Arguments are {@link String} (Erlang binary),
 * {@link Long} (integer), {@link Tuple} or {@link List} of those.
 */
public record Op(String name, List<Object> args) {

    public Op {
        args = List.copyOf(args);
    }

    /** An Erlang tuple used inside an argument, e.g. {@code {ResId, SlotStart}} in a bundle. */
    public record Tuple(List<Object> elements) {
        public Tuple {
            elements = List.copyOf(elements);
        }

        public static Tuple of(Object... elements) {
            return new Tuple(List.of(elements));
        }
    }

    /** One element of a bundle booking. */
    public record BundleItem(String resourceId, long slotStart) { }

    public static Op availability(String resourceId, long dayStart) {
        return new Op("availability", List.of(resourceId, dayStart));
    }

    public static Op hold(String resourceId, long slotStart) {
        return new Op("hold", List.of(resourceId, slotStart));
    }

    public static Op release(String holdId) {
        return new Op("release", List.of(holdId));
    }

    public static Op confirm(String holdId) {
        return new Op("confirm", List.of(holdId));
    }

    public static Op cancel(String bookingId) {
        return new Op("cancel", List.of(bookingId));
    }

    public static Op checkIn(String bookingId) {
        return new Op("check_in", List.of(bookingId));
    }

    public static Op joinWaitlist(String resourceId, long slotStart) {
        return new Op("join_waitlist", List.of(resourceId, slotStart));
    }

    public static Op acceptOffer(String offerId) {
        return new Op("accept_offer", List.of(offerId));
    }

    public static Op declineOffer(String offerId) {
        return new Op("decline_offer", List.of(offerId));
    }

    public static Op quota() {
        return new Op("quota", List.of());
    }

    public static Op bookBundle(List<BundleItem> items) {
        List<Object> tuples = new ArrayList<>();
        for (BundleItem item : items) {
            tuples.add(Tuple.of(item.resourceId(), item.slotStart()));
        }
        return new Op("book_bundle", List.of(tuples));
    }

    public static Op partitionMap() {
        return new Op("partition_map", List.of());
    }

    /** The resource this op is about, used to route it to the owning node; null if none. */
    public String resourceId() {
        switch (name) {
            case "availability":
            case "hold":
            case "join_waitlist":
                return (String) args.get(0);
            default:
                return null;
        }
    }

    public String stringArg(int i) {
        return (String) args.get(i);
    }

    public long longArg(int i) {
        return (Long) args.get(i);
    }
}
