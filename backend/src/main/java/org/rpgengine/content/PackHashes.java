package org.rpgengine.content;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Portable raw-byte digest protocol; framing avoids filename/content concatenation ambiguity. */
public final class PackHashes {
    private static final byte[] DOMAIN = "rpgengine.pack.raw.v1\0".getBytes(StandardCharsets.UTF_8);

    /** Prevent instances of the stateless digest utility. */
    private PackHashes() {
    }

    /**
     * Hash an already validated stable inventory in ASCII path order. Returns a lower-case aggregate
     * SHA-256 and per-file raw SHA-256; timestamps, host roots and parser serialization never enter it.
     * The caller owns path/size validation and must not mutate the arrays during this internal operation.
     */
    static Inventory calculate(Map<String, byte[]> files) {
        MessageDigest digest = sha256();
        digest.update(DOMAIN);
        digest.update(ByteBuffer.allocate(4).putInt(files.size()).array());
        Map<String, String> hashes = new TreeMap<>();
        for (var entry : new TreeMap<>(files).entrySet()) {
            byte[] path = entry.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] fileHash = sha256().digest(entry.getValue());
            digest.update(ByteBuffer.allocate(4).putInt(path.length).array());
            digest.update(path);
            digest.update(ByteBuffer.allocate(8).putLong(entry.getValue().length).array());
            digest.update(fileHash);
            hashes.put(entry.getKey(), HexFormat.of().formatHex(fileHash));
        }
        return new Inventory(HexFormat.of().formatHex(digest.digest()), hashes);
    }

    /** Obtain the JDK-mandated digest algorithm; a broken JDK fails explicitly rather than changing protocol. */
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required", failure);
        }
    }

    /** Aggregate hash plus an immutable sorted path-to-raw-hash inventory, with no host metadata or mutable bytes. */
    record Inventory(String contentHash, Map<String, String> fileHashes) {
        /** Copy the sorted inventory so later caller mutation cannot change a parsed revision's identity. */
        Inventory {
            fileHashes = Collections.unmodifiableMap(new TreeMap<>(fileHashes));
        }
    }
}
