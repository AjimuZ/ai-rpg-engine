package org.rpgengine.content;

import java.util.Map;
import java.util.Set;

/**
 * Allowlist for files in the opaque {@code asset} category (docs/09 section 9, docs/14 section 5).
 *
 * <p>An extension alone is never trusted: the leading bytes must carry the signature of the same
 * format, and raster images must declare dimensions within fixed bounds before a renderer or
 * decoder ever sees them. Only passive formats are listed. SVG (which can carry scripts), HTML,
 * fonts, archives and every executable or unknown format are rejected. This class reads headers
 * only and never decodes pixels or audio frames.
 */
public final class PackMedia {
    /** Largest accepted asset; text categories keep the parser's 1 MiB limit. */
    public static final int MAX_ASSET_BYTES = 32 * 1024 * 1024;
    /** Longest accepted image side in pixels. */
    static final int MAX_SIDE = 8192;
    /** Largest declared image area; a future decoder must independently enforce actual allocation limits. */
    static final long MAX_PIXELS = 32L * 1024 * 1024;
    // Extension to canonical media type. Two JPEG spellings share one type.
    private static final Map<String, String> TYPES = Map.of(
            "png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg", "webp", "image/webp",
            "ogg", "audio/ogg", "mp3", "audio/mpeg", "wav", "audio/wav");
    private static final Set<String> IMAGES = Set.of("image/png", "image/jpeg", "image/webp");

    /** Prevent instances of the stateless checker. */
    private PackMedia() {
    }

    /**
     * Validate one asset and describe it.
     *
     * @param path portable logical path, used for the extension and diagnostics
     * @param bytes complete asset bytes
     * @return the canonical media type and, for images, the declared dimensions (0 for audio)
     * @throws PackException {@code MEDIA_TYPE} for unlisted extensions or mismatched signatures,
     *         {@code MEDIA_LIMIT} for oversized files or images
     */
    public static Media inspect(String path, byte[] bytes) {
        String type = TYPES.get(PackPaths.extension(path));
        if (type == null) {
            throw error("MEDIA_TYPE", path, "Asset extension is not an allowlisted media type");
        }
        if (bytes.length > MAX_ASSET_BYTES) {
            throw error("MEDIA_LIMIT", path, "Asset exceeds 32 MiB");
        }
        int[] size = switch (type) {
            case "image/png" -> png(path, bytes);
            case "image/jpeg" -> jpeg(path, bytes);
            case "image/webp" -> webp(path, bytes);
            case "audio/ogg" -> signature(path, bytes, startsWith(bytes, 0, "OggS"));
            // MP3 starts with an ID3v2 tag or directly with an MPEG audio frame sync (11 set bits).
            case "audio/mpeg" -> signature(path, bytes, startsWith(bytes, 0, "ID3")
                    || (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xE0) == 0xE0));
            default -> signature(path, bytes, startsWith(bytes, 0, "RIFF") && startsWith(bytes, 8, "WAVE"));
        };
        if (IMAGES.contains(type)) {
            // Zero sides are malformed; large sides or areas would let a small file demand huge decode buffers.
            if (size[0] < 1 || size[1] < 1 || size[0] > MAX_SIDE || size[1] > MAX_SIDE
                    || (long) size[0] * size[1] > MAX_PIXELS) {
                throw error("MEDIA_LIMIT", path, "Image dimensions must be 1..8192 per side and at most 32 Mi pixels");
            }
        }
        return new Media(type, size[0], size[1]);
    }

    /** Return no dimensions when the signature matches, otherwise a MEDIA_TYPE error. */
    private static int[] signature(String path, byte[] bytes, boolean matches) {
        if (!matches) {
            throw error("MEDIA_TYPE", path, "Asset bytes do not match the extension's media type");
        }
        return new int[] {0, 0};
    }

    /** PNG: fixed 8-byte signature followed immediately by the IHDR chunk with big-endian width/height. */
    private static int[] png(String path, byte[] bytes) {
        byte[] magic = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        if (bytes.length < 33 || !java.util.Arrays.equals(bytes, 0, 8, magic, 0, 8)
                || bigEndian(bytes, 8, 4) != 13 || !startsWith(bytes, 12, "IHDR")) {
            throw error("MEDIA_TYPE", path, "Asset bytes do not match the extension's media type");
        }
        // Read as int: a negative value (top bit set) fails the 1..8192 range check later.
        return new int[] {bigEndian(bytes, 16, 4), bigEndian(bytes, 20, 4)};
    }

    /**
     * JPEG: walk marker segments from SOI to the first start-of-frame, which carries the size.
     * Every segment length is bounds-checked, so a truncated or looping file ends the walk.
     */
    private static int[] jpeg(String path, byte[] bytes) {
        if (bytes.length < 4 || (bytes[0] & 0xFF) != 0xFF || (bytes[1] & 0xFF) != 0xD8) {
            throw error("MEDIA_TYPE", path, "Asset bytes do not match the extension's media type");
        }
        int offset = 2;
        while (offset + 4 <= bytes.length) {
            if ((bytes[offset] & 0xFF) != 0xFF) {
                break;
            }
            int marker = bytes[offset + 1] & 0xFF;
            if (marker == 0xFF) {
                // Fill byte before a marker; advance one byte.
                offset++;
                continue;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) {
                // Standalone markers carry no length field.
                offset += 2;
                continue;
            }
            if (marker == 0xD9 || marker == 0xDA) {
                // End of image or start of scan before any frame header: no dimensions available.
                break;
            }
            int length = bigEndian(bytes, offset + 2, 2);
            if (length < 2 || offset + 2 + length > bytes.length) {
                break;
            }
            // SOF0..SOF15 except DHT (C4), JPG (C8) and DAC (CC) declare precision, height, width.
            if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                if (length < 8) {
                    break;
                }
                return new int[] {bigEndian(bytes, offset + 7, 2), bigEndian(bytes, offset + 5, 2)};
            }
            offset += 2 + length;
        }
        throw error("MEDIA_TYPE", path, "JPEG frame header is missing or truncated");
    }

    /** WebP: RIFF container whose first chunk is lossy VP8, lossless VP8L or extended VP8X. */
    private static int[] webp(String path, byte[] bytes) {
        if (bytes.length < 30 || !startsWith(bytes, 0, "RIFF") || !startsWith(bytes, 8, "WEBP")) {
            throw error("MEDIA_TYPE", path, "Asset bytes do not match the extension's media type");
        }
        if (startsWith(bytes, 12, "VP8X")) {
            // Canvas width-1 and height-1 as 24-bit little-endian values.
            return new int[] {littleEndian(bytes, 24, 3) + 1, littleEndian(bytes, 27, 3) + 1};
        }
        if (startsWith(bytes, 12, "VP8L") && (bytes[20] & 0xFF) == 0x2F) {
            // 14-bit width-1 and height-1 packed little-endian after the 0x2F signature byte.
            int bits = littleEndian(bytes, 21, 4);
            return new int[] {(bits & 0x3FFF) + 1, ((bits >>> 14) & 0x3FFF) + 1};
        }
        if (startsWith(bytes, 12, "VP8 ") && (bytes[23] & 0xFF) == 0x9D && (bytes[24] & 0xFF) == 0x01
                && (bytes[25] & 0xFF) == 0x2A) {
            // Key frame start code, then 14-bit width and height (top two bits are scaling).
            return new int[] {littleEndian(bytes, 26, 2) & 0x3FFF, littleEndian(bytes, 28, 2) & 0x3FFF};
        }
        throw error("MEDIA_TYPE", path, "WebP image header is missing or unsupported");
    }

    /** True when the ASCII text occurs at offset; out-of-range offsets are simply false. */
    private static boolean startsWith(byte[] bytes, int offset, String text) {
        if (offset + text.length() > bytes.length) {
            return false;
        }
        for (int index = 0; index < text.length(); index++) {
            if (bytes[offset + index] != (byte) text.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    /** Unsigned big-endian value of 1..4 bytes; callers guarantee the range is inside the array. */
    private static int bigEndian(byte[] bytes, int offset, int count) {
        int value = 0;
        for (int index = 0; index < count; index++) {
            value = (value << 8) | (bytes[offset + index] & 0xFF);
        }
        return value;
    }

    /** Little-endian value of 1..4 bytes; callers guarantee the range is inside the array. */
    private static int littleEndian(byte[] bytes, int offset, int count) {
        int value = 0;
        for (int index = count - 1; index >= 0; index--) {
            value = (value << 8) | (bytes[offset + index] & 0xFF);
        }
        return value;
    }

    /** Asset-level diagnostic; the file is a logical path, never a host location. */
    private static PackException error(String code, String path, String message) {
        return new PackException(code, new SourceLocation(path, 1, 1, ""), message);
    }

    /**
     * Description of an accepted asset, recorded in the import report.
     *
     * @param mediaType canonical media type later used for serving with {@code nosniff}
     * @param width declared image width, or 0 for audio
     * @param height declared image height, or 0 for audio
     */
    public record Media(String mediaType, int width, int height) {
    }
}
