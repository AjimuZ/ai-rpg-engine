package org.rpgengine.commands;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure checks of canonical command form version 1: deterministic key order, exact decimal
 * normalization, JSON escaping, rejected text/value shapes and the byte bound. The hash of
 * this form is the durable idempotency identity, so any change here is a format change.
 */
class CommandCanonicalFormTest {
    /** Construct the stateless suite. */
    CommandCanonicalFormTest() {
    }

    /** Envelope and nested keys are sorted by UTF-16 code units and written without whitespace. */
    @Test
    void writesSortedCompactEnvelope() {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("z", List.of(true, false));
        payload.put("a", null);
        payload.put("m", Map.of("y", 1, "b", "t"));
        assertEquals("{\"commandType\":\"MOVE\",\"expectedRevision\":7,\"payload\":"
                        + "{\"a\":null,\"m\":{\"b\":\"t\",\"y\":1},\"z\":[true,false]},\"version\":1}",
                text("MOVE", 7, payload));
    }

    /** Insertion order never changes the bytes; equal decimals share one spelling. */
    @Test
    void normalizesEquivalentInputs() {
        var first = new LinkedHashMap<String, Object>();
        first.put("b", new BigDecimal("1.50"));
        first.put("a", BigInteger.valueOf(3));
        var second = new HashMap<String, Object>();
        second.put("a", 3L);
        second.put("b", new BigDecimal("1.5"));
        assertArrayEquals(CommandCanonicalForm.encode("MOVE", 0, first, 1024),
                CommandCanonicalForm.encode("MOVE", 0, second, 1024));
        assertEquals("{\"commandType\":\"MOVE\",\"expectedRevision\":0,\"payload\":{\"a\":3,\"b\":15e-1},\"version\":1}",
                text("MOVE", 0, first));
        assertTrue(text("MOVE", 0, Map.of("d", new BigDecimal("0.000"))).contains("\"d\":0e0"));
        assertTrue(text("MOVE", 0, Map.of("d", new BigDecimal("1E+3"))).contains("\"d\":1e3"));
    }

    /** JSON integers and decimals stay distinct, as do type, revision and payload changes. */
    @Test
    void distinguishesSemanticDifferences() {
        String integer = hash("MOVE", 0, Map.of("n", BigInteger.TEN));
        assertNotEquals(integer, hash("MOVE", 0, Map.of("n", new BigDecimal("10.0"))));
        assertNotEquals(integer, hash("WAIT", 0, Map.of("n", BigInteger.TEN)));
        assertNotEquals(integer, hash("MOVE", 1, Map.of("n", BigInteger.TEN)));
        assertNotEquals(integer, hash("MOVE", 0, Map.of("n", "10")));
        assertEquals(integer, hash("MOVE", 0, Map.of("n", 10)));
    }

    /** Control characters, quotes and backslashes are escaped; valid pairs and non-ASCII stay raw UTF-8. */
    @Test
    void escapesTextAndKeepsUnicode() {
        String text = text("MOVE", 0, Map.of("s", "q\"b\\n\n\t\u0001é😀"));
        assertTrue(text.contains("\"s\":\"q\\\"b\\\\n\\n\\t\\u0001é😀\""), text);
    }

    /** Unpaired surrogates, non-string keys and foreign value types are rejected, not coerced. */
    @Test
    void rejectsUnrepresentableValues() {
        assertThrows(IllegalArgumentException.class, () -> text("MOVE", 0, Map.of("s", "\uD800")));
        assertThrows(IllegalArgumentException.class, () -> text("MOVE", 0, Map.of("s", "\uDC00x")));
        assertThrows(IllegalArgumentException.class, () -> text("MOVE", 0, Map.of("s", "x\uD800")));
        assertThrows(IllegalArgumentException.class, () -> text("MOVE", 0, Map.of("m", Map.of(1, "x"))));
        assertThrows(IllegalArgumentException.class, () -> text("MOVE", 0, Map.of("d", 1.5d)));
        assertThrows(IllegalArgumentException.class, () -> text("MOVE", 0, Map.of("o", new Object())));
    }

    /** The byte bound is enforced on the final UTF-8 form, including multibyte characters. */
    @Test
    void enforcesByteBound() {
        int envelope = text("MOVE", 0, Map.of("s", "")).getBytes(StandardCharsets.UTF_8).length;
        assertDoesNotThrow(() -> CommandCanonicalForm.encode("MOVE", 0, Map.of("s", "é".repeat(10)), envelope + 20));
        assertThrows(IllegalArgumentException.class,
                () -> CommandCanonicalForm.encode("MOVE", 0, Map.of("s", "é".repeat(10)), envelope + 19));
        char[] large = new char[100_000];
        Arrays.fill(large, 'x');
        assertThrows(IllegalArgumentException.class,
                () -> CommandCanonicalForm.encode("MOVE", 0, Map.of("s", new String(large)), 32_768));
    }

    /** The durable identity is 64 lowercase hexadecimal SHA-256 digits. */
    @Test
    void hashesToLowercaseHex() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                CommandCanonicalForm.sha256(new byte[0]));
        assertTrue(hash("MOVE", 0, Map.of()).matches("[0-9a-f]{64}"));
    }

    /** Direct callers cannot bypass depth/cycle guards that the JSON parser supplies on HTTP. */
    @Test
    void rejectsDeepAndCyclicContainers() {
        Map<String, Object> deep = Map.of();
        for (int index = 0; index < 100; index++) {
            deep = Map.of("next", deep);
        }
        Map<String, Object> payload = deep;
        assertThrows(IllegalArgumentException.class, () -> text("MOVE", 0, payload));
        var cycle = new HashMap<String, Object>();
        cycle.put("self", cycle);
        assertThrows(IllegalArgumentException.class, () -> text("MOVE", 0, cycle));
        var list = new java.util.ArrayList<Object>();
        list.add(list);
        assertThrows(IllegalArgumentException.class, () -> text("MOVE", 0, Map.of("list", list)));
        // Shared children are legal JSON values; only ancestor cycles are forbidden.
        var child = Map.of("n", 1);
        assertDoesNotThrow(() -> text("MOVE", 0, Map.of("a", child, "b", child)));
    }

    /** Numeric normalization must reject scale overflow and bound work before decimal conversion. */
    @Test
    void boundsNumericNormalization() {
        assertThrows(IllegalArgumentException.class, () -> text("MOVE", 0,
                Map.of("n", new BigDecimal(BigInteger.TEN, Integer.MIN_VALUE))));
        assertThrows(IllegalArgumentException.class, () -> text("MOVE", 0,
                Map.of("n", BigInteger.ONE.shiftLeft(200_000))));
        assertTrue(text("MOVE", 0, Map.of("n", new BigDecimal("1e999"))).contains("1e999"));
    }

    /** Reject a wide map before iterating it; sorting must not allocate beyond the output budget. */
    @Test
    void boundsCollectionBeforeCopyingKeys() {
        Map<String, Object> huge = new java.util.AbstractMap<>() {
            @Override public int size() { return 1_000_000; }
            @Override public java.util.Set<Entry<String, Object>> entrySet() {
                throw new AssertionError("oversized map must not be iterated");
            }
        };
        assertThrows(IllegalArgumentException.class, () -> text("MOVE", 0, huge));
    }

    /** Encode with a generous bound and decode as UTF-8 for readable assertions. */
    private static String text(String type, long revision, Map<?, ?> payload) {
        return new String(CommandCanonicalForm.encode(type, revision, payload, 4096), StandardCharsets.UTF_8);
    }

    /** Hash one command under the generous test bound. */
    private static String hash(String type, long revision, Map<?, ?> payload) {
        return CommandCanonicalForm.sha256(CommandCanonicalForm.encode(type, revision, payload, 4096));
    }
}
