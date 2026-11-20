package it.unipi.dsmt.fairslot.web.backend;

import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangBinary;
import com.ericsson.otp.erlang.OtpErlangDouble;
import com.ericsson.otp.erlang.OtpErlangList;
import com.ericsson.otp.erlang.OtpErlangLong;
import com.ericsson.otp.erlang.OtpErlangMap;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangString;
import com.ericsson.otp.erlang.OtpErlangTuple;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts between Java values and Erlang terms as docs/message-contract.md §1 defines them.
 *
 * <pre>
 * Java -> Erlang                       Erlang -> Java
 * String       -> binary               binary, charlist   -> String
 * Long/Integer -> integer              integer            -> Long
 * Op.Tuple     -> tuple                float              -> Double
 * List         -> list                 atom true/false    -> Boolean, other atoms -> String
 * Op           -> {Name, Args...}      list, tuple        -> List
 *                                      map                -> Map&lt;String, Object&gt; (keys as strings)
 * </pre>
 */
public final class ErlangCodec {

    private ErlangCodec() { }

    public static OtpErlangObject encodeOp(Op op) {
        OtpErlangObject[] elements = new OtpErlangObject[op.args().size() + 1];
        elements[0] = new OtpErlangAtom(op.name());
        for (int i = 0; i < op.args().size(); i++) {
            elements[i + 1] = encode(op.args().get(i));
        }
        return new OtpErlangTuple(elements);
    }

    public static OtpErlangObject encode(Object value) {
        if (value instanceof String) {
            return new OtpErlangBinary(((String) value).getBytes(StandardCharsets.UTF_8));
        }
        if (value instanceof Long || value instanceof Integer) {
            return new OtpErlangLong(((Number) value).longValue());
        }
        if (value instanceof Op.Tuple) {
            return new OtpErlangTuple(encodeAll(((Op.Tuple) value).elements()));
        }
        if (value instanceof List) {
            return new OtpErlangList(encodeAll((List<?>) value));
        }
        if (value instanceof Op) {
            return encodeOp((Op) value);
        }
        throw new IllegalArgumentException("Cannot encode " + (value == null ? "null" : value.getClass()));
    }

    private static OtpErlangObject[] encodeAll(List<?> values) {
        OtpErlangObject[] out = new OtpErlangObject[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = encode(values.get(i));
        }
        return out;
    }

    public static Object decode(OtpErlangObject term) {
        if (term instanceof OtpErlangBinary) {
            return new String(((OtpErlangBinary) term).binaryValue(), StandardCharsets.UTF_8);
        }
        if (term instanceof OtpErlangAtom) {
            String atom = ((OtpErlangAtom) term).atomValue();
            if (atom.equals("true") || atom.equals("false")) {
                return Boolean.valueOf(atom);
            }
            return atom;
        }
        if (term instanceof OtpErlangLong) {
            OtpErlangLong l = (OtpErlangLong) term;
            return l.isLong() ? (Object) l.longValue() : l.bigIntegerValue();
        }
        if (term instanceof OtpErlangDouble) {
            return ((OtpErlangDouble) term).doubleValue();
        }
        if (term instanceof OtpErlangString) {
            return ((OtpErlangString) term).stringValue();
        }
        if (term instanceof OtpErlangList) {
            return decodeAll(((OtpErlangList) term).elements());
        }
        if (term instanceof OtpErlangTuple) {
            return decodeAll(((OtpErlangTuple) term).elements());
        }
        if (term instanceof OtpErlangMap) {
            return decodeMap((OtpErlangMap) term);
        }
        return term.toString();   // pids, refs, funs: only useful for logging
    }

    public static Map<String, Object> decodeMap(OtpErlangMap map) {
        Map<String, Object> out = new LinkedHashMap<>();
        OtpErlangObject[] keys = map.keys();
        OtpErlangObject[] values = map.values();
        for (int i = 0; i < keys.length; i++) {
            out.put(String.valueOf(decode(keys[i])), decode(values[i]));
        }
        return out;
    }

    private static List<Object> decodeAll(OtpErlangObject[] terms) {
        List<Object> out = new ArrayList<>(terms.length);
        for (OtpErlangObject t : terms) {
            out.add(decode(t));
        }
        return out;
    }
}
