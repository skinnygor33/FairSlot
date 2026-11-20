package it.unipi.dsmt.fairslot.web.backend;

import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangBinary;
import com.ericsson.otp.erlang.OtpErlangList;
import com.ericsson.otp.erlang.OtpErlangLong;
import com.ericsson.otp.erlang.OtpErlangMap;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangString;
import com.ericsson.otp.erlang.OtpErlangTuple;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ErlangCodecTest {

    static OtpErlangBinary bin(String s) {
        return new OtpErlangBinary(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void encodesHoldAsContractTuple() {
        OtpErlangObject t = ErlangCodec.encodeOp(Op.hold("room:B1-101", 1795680000000L));
        assertEquals(new OtpErlangTuple(new OtpErlangObject[] {
                new OtpErlangAtom("hold"), bin("room:B1-101"), new OtpErlangLong(1795680000000L)}), t);
    }

    @Test
    void encodesBundleAsListOfTuples() {
        OtpErlangObject t = ErlangCodec.encodeOp(Op.bookBundle(List.of(
                new Op.BundleItem("room:B2-201", 0L), new Op.BundleItem("equip:proj-01", 0L))));
        OtpErlangObject[] items = {
                new OtpErlangTuple(new OtpErlangObject[] {bin("room:B2-201"), new OtpErlangLong(0)}),
                new OtpErlangTuple(new OtpErlangObject[] {bin("equip:proj-01"), new OtpErlangLong(0)})};
        assertEquals(new OtpErlangTuple(new OtpErlangObject[] {
                new OtpErlangAtom("book_bundle"), new OtpErlangList(items)}), t);
    }

    @Test
    void encodesNoArgOpAsOneTuple() {
        assertEquals("{quota}", ErlangCodec.encodeOp(Op.quota()).toString());
    }

    @Test
    void decodesReplyMapWithNestedValues() {
        OtpErlangMap slot = new OtpErlangMap(
                new OtpErlangObject[] {new OtpErlangAtom("start"), new OtpErlangAtom("state")},
                new OtpErlangObject[] {new OtpErlangLong(7L), new OtpErlangAtom("free")});
        OtpErlangMap m = new OtpErlangMap(
                new OtpErlangObject[] {new OtpErlangAtom("slots"), new OtpErlangAtom("note"), new OtpErlangAtom("ok")},
                new OtpErlangObject[] {new OtpErlangList(new OtpErlangObject[] {slot}),
                        new OtpErlangString("hi"), new OtpErlangAtom("true")});
        Map<String, Object> decoded = ErlangCodec.decodeMap(m);
        assertEquals(List.of(Map.of("start", 7L, "state", "free")), decoded.get("slots"));
        assertEquals("hi", decoded.get("note"));
        assertEquals(Boolean.TRUE, decoded.get("ok"));
    }

    @Test
    void errorRepliesBecomeExceptions() {
        OtpErlangTuple simple = new OtpErlangTuple(new OtpErlangObject[] {
                new OtpErlangAtom("error"), new OtpErlangAtom("slot_taken")});
        assertEquals("slot_taken", assertThrows(BackendException.class, () -> JInterfaceBackend.result(simple)).reason());

        OtpErlangTuple withDetail = new OtpErlangTuple(new OtpErlangObject[] {
                new OtpErlangAtom("error"),
                new OtpErlangTuple(new OtpErlangObject[] {new OtpErlangAtom("wrong_owner"), new OtpErlangLong(4)})});
        BackendException e = assertThrows(BackendException.class, () -> JInterfaceBackend.result(withDetail));
        assertEquals("wrong_owner", e.reason());
        assertEquals(4L, e.detail());
    }

    @Test
    void partitionMapRoutesByResourceAndFallsBackToCoordinator() {
        PartitionMap map = new PartitionMap(3, "a@h", Map.of("rooms-B1", "b@h"), Map.of("room:B1-101", "rooms-B1"));
        assertEquals("b@h", map.nodeFor("room:B1-101"));
        assertEquals("a@h", map.nodeFor("room:unknown"));
        assertEquals("a@h", map.nodeFor(null));
    }
}
