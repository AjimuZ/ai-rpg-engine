package org.rpgengine.commands;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic version-1 command normalization for idempotency hashing (docs/07 §3 step 2).
 *
 * <p>The canonical text is compact JSON over {@code commandType}, {@code expectedRevision},
 * {@code payload} and a schema {@code version}. Object members are sorted by key in Java
 * {@link String} (UTF-16) order, arrays keep their order, strings keep their exact text
 * (no Unicode or whitespace normalization, because wording can be meaningful), and only
 * the mandatory JSON escapes are applied. Integers are written in plain decimal; decimals
 * are written as {@code <unscaled>e<exponent>} after stripping trailing zeros, so
 * {@code 1.50} and {@code 1.5} match while {@code 10} (integer) and {@code 10.0}
 * (decimal) stay distinct, exactly like the typed validator will see them. Exponent
 * notation also prevents a tiny input such as {@code 1e999} from expanding into a huge
 * string. Changing any rule changes stored hashes, so a new rule needs a new version.
 */
final class CommandCanonicalForm {
    /** Schema identity mixed into every hash so future normalization rules cannot collide with v1. */
    static final int VERSION = 1;

    /** Static utility; no instances carry state. */
    private CommandCanonicalForm() {
    }

    /**
     * Build the canonical UTF-8 bytes of one command envelope.
     *
     * @param commandType validated command type
     * @param expectedRevision validated non-negative campaign revision targeted by the caller
     * @param payload JSON object made of maps with string keys, lists, strings, integral or
     *        decimal numbers, booleans and nulls (the shapes produced by the bounded parser)
     * @param maxBytes inclusive canonical size limit
     * @return canonical bytes, never longer than {@code maxBytes}
     * @throws IllegalArgumentException for unsupported value types, non-string keys,
     *         unpaired UTF-16 surrogates, cyclic/deep containers, decimal scale overflow
     *         or an oversized canonical form
     */
    static byte[] encode(String commandType, long expectedRevision, Map<?, ?> payload, int maxBytes) {
        var envelope = new TreeMap<String, Object>();
        envelope.put("commandType", commandType);
        envelope.put("expectedRevision", BigInteger.valueOf(expectedRevision));
        envelope.put("payload", payload);
        envelope.put("version", BigInteger.valueOf(VERSION));
        if (maxBytes < 1 || maxBytes > 32_768) {
            throw new IllegalArgumentException("canonical budget must be 1..32768 bytes");
        }
        var writer = new Writer(maxBytes);
        writer.write(envelope, 0);
        return writer.out.toByteArray();
    }

    /**
     * Hash canonical bytes with SHA-256.
     *
     * @param canonical bytes returned by {@link #encode}
     * @return 64 lowercase hexadecimal characters, the journal's {@code payload_hash} format
     * @throws IllegalStateException if the platform lacks SHA-256 (a broken JDK, never input)
     */
    static String sha256(byte[] canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    /**
     * Call-local UTF-8 writer. Allocation is bounded before copying/sorting containers
     * or converting numbers; every emitted byte consumes the output budget. A maximum
     * of 64 nested containers (including the envelope) and ancestor identity checks
     * reject deep/cyclic direct Java input independently of the wire parser's limits.
     * Shared acyclic children are legal and serialized separately at each occurrence.
     */
    private static final class Writer {
        private static final int MAX_DEPTH = 64;
        private final int limit;
        private final ByteArrayOutputStream out;
        private final IdentityHashMap<Object, Boolean> ancestors = new IdentityHashMap<>();

        /** Allocate only a small initial buffer; growth cannot exceed the bounded output size. */
        Writer(int limit) {
            this.limit = limit;
            out = new ByteArrayOutputStream(Math.min(limit, 1024));
        }

        /** Reject before any copy or conversion whose lower bound exceeds remaining bytes. */
        private void require(long bytes) {
            if (bytes > limit - out.size()) {
                throw new IllegalArgumentException("canonical command exceeds byte budget");
            }
        }

        /** Emit one byte only when it fits; callers supply ASCII or already encoded UTF-8. */
        private void emit(int value) {
            require(1);
            out.write(value);
        }

        /** Emit bounded ASCII literals/numbers without allocating an intermediate byte array. */
        private void ascii(String value) {
            require(value.length());
            for (int index = 0; index < value.length(); index++) {
                emit(value.charAt(index));
            }
        }

        /** Encode supported exact JSON values; arbitrary objects and binary floats fail closed. */
        private void write(Object value, int depth) {
            if (value instanceof Map<?, ?> || value instanceof List<?>) {
                if (depth >= MAX_DEPTH || ancestors.put(value, Boolean.TRUE) != null) {
                    throw new IllegalArgumentException("command container depth or cycle limit");
                }
                try {
                    if (value instanceof Map<?, ?> map) {
                        object(map, depth);
                    } else {
                        List<?> list = (List<?>) value;
                        require(2L + list.size());
                        emit('[');
                        boolean first = true;
                        for (Object child : list) {
                            if (!first) {
                                emit(',');
                            }
                            first = false;
                            write(child, depth + 1);
                        }
                        emit(']');
                    }
                } finally {
                    ancestors.remove(value);
                }
                return;
            }
            switch (value) {
                case null -> ascii("null");
                case Boolean flag -> ascii(flag ? "true" : "false");
                case String text -> string(text);
                case Integer number -> ascii(number.toString());
                case Long number -> ascii(number.toString());
                case BigInteger number -> integer(number);
                case BigDecimal number -> decimal(number);
                default -> throw new IllegalArgumentException("unsupported command value type");
            }
        }

        /** Sort bounded keys in UTF-16 order, rejecting non-string keys before any recursive work. */
        private void object(Map<?, ?> map, int depth) {
            require(map.isEmpty() ? 2 : 1L + 5L * map.size());
            var sorted = new TreeMap<String, Object>();
            long minimum = map.isEmpty() ? 2 : 1;
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("command object keys must be strings");
                }
                minimum += key.length() + 5L;
                require(minimum);
                sorted.put(key, entry.getValue());
            }
            emit('{');
            boolean first = true;
            for (var entry : sorted.entrySet()) {
                if (!first) {
                    emit(',');
                }
                first = false;
                string(entry.getKey());
                emit(':');
                write(entry.getValue(), depth + 1);
            }
            emit('}');
        }

        /** Bound integer conversion by bit length first; the emitted decimal then uses the exact byte cap. */
        private void integer(BigInteger number) {
            // Four bits per remaining byte is a conservative allocation bound (decimal
            // needs about 3.32 bits/digit). No arbitrarily large toString is attempted.
            if (number.bitLength() > 4L * (limit - out.size())) {
                throw new IllegalArgumentException("command number exceeds byte budget");
            }
            ascii(number.toString());
        }

        /** Normalize bounded decimals without exponent expansion; scale overflow is invalid input. */
        private void decimal(BigDecimal number) {
            if (number.unscaledValue().bitLength() > 4L * (limit - out.size())) {
                throw new IllegalArgumentException("command number exceeds byte budget");
            }
            final BigDecimal stripped;
            try {
                stripped = number.signum() == 0 ? BigDecimal.ZERO : number.stripTrailingZeros();
            } catch (ArithmeticException failure) {
                throw new IllegalArgumentException("command decimal scale overflow", failure);
            }
            integer(stripped.unscaledValue());
            emit('e');
            ascii(Long.toString(-(long) stripped.scale()));
        }

        /** Encode exact strings with mandatory JSON escapes, rejecting unpaired UTF-16 surrogates. */
        private void string(String text) {
            require(2L + text.length());
            emit('"');
            for (int index = 0; index < text.length(); index++) {
                char current = text.charAt(index);
                if (Character.isSurrogate(current)) {
                    if (!Character.isHighSurrogate(current) || index + 1 >= text.length()
                            || !Character.isLowSurrogate(text.charAt(index + 1))) {
                        throw new IllegalArgumentException("unpaired surrogate in command text");
                    }
                    utf8(Character.toCodePoint(current, text.charAt(++index)));
                } else {
                    switch (current) {
                        case '"' -> ascii("\\\"");
                        case '\\' -> ascii("\\\\");
                        case '\b' -> ascii("\\b");
                        case '\f' -> ascii("\\f");
                        case '\n' -> ascii("\\n");
                        case '\r' -> ascii("\\r");
                        case '\t' -> ascii("\\t");
                        default -> {
                            if (current < 0x20) {
                                ascii("\\u00");
                                emit("0123456789abcdef".charAt(current >> 4));
                                emit("0123456789abcdef".charAt(current & 15));
                            } else {
                                utf8(current);
                            }
                        }
                    }
                }
            }
            emit('"');
        }

        /** Emit one validated Unicode scalar without intermediate text or replacement characters. */
        private void utf8(int point) {
            if (point < 0x80) {
                emit(point);
            } else if (point < 0x800) {
                emit(0xc0 | (point >> 6));
                emit(0x80 | (point & 0x3f));
            } else if (point < 0x10000) {
                emit(0xe0 | (point >> 12));
                emit(0x80 | ((point >> 6) & 0x3f));
                emit(0x80 | (point & 0x3f));
            } else {
                emit(0xf0 | (point >> 18));
                emit(0x80 | ((point >> 12) & 0x3f));
                emit(0x80 | ((point >> 6) & 0x3f));
                emit(0x80 | (point & 0x3f));
            }
        }
    }
}
