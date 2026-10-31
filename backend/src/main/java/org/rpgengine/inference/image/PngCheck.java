package org.rpgengine.inference.image;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Validates a PNG received from an image backend and reduces it to its rendering chunks. Stateless.
 *
 * <p>A file passes only if it has the PNG signature, an {@code IHDR} of the requested size with a legal
 * color type and depth and no interlacing, the CRC of every chunk, consecutive {@code IDAT} chunks whose
 * stream inflates to exactly the size the header implies with legal filter bytes, and {@code IEND} as
 * the last chunk with nothing after it. The result keeps only {@code IHDR}, {@code PLTE}, {@code tRNS},
 * {@code IDAT} and {@code IEND}: text, time, EXIF and color-management chunks are dropped, because a
 * server writes the prompt and its own settings into them and they would travel with the image.
 * This is a structural check; it does not decode pixels.
 */
final class PngCheck {
    /** Largest accepted file, in bytes. */
    static final int MAX_BYTES = 24 * 1024 * 1024;

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    private static final int HEADER_LENGTH = 13;
    private static final int CHUNK_OVERHEAD = 12;
    private static final int MAX_PALETTE_BYTES = 768;

    private PngCheck() {
    }

    /** The file is not a well-formed PNG of the requested size. */
    static final class Invalid extends Exception {
        private static final long serialVersionUID = 1L;
        private final boolean size;

        Invalid(boolean size) {
            super(size ? "wrong size" : "not a valid png");
            this.size = size;
        }

        /** @return true when the file is a valid header of another size, false for a structural fault */
        boolean wrongSize() {
            return size;
        }
    }

    /**
     * @param data the decoded file
     * @param width requested width
     * @param height requested height
     * @return the same image reduced to its rendering chunks, with the original CRCs
     * @throws Invalid when the file breaks any rule above
     */
    static byte[] validate(byte[] data, int width, int height) throws Invalid {
        if (data.length > MAX_BYTES || data.length < SIGNATURE.length + 2 * CHUNK_OVERHEAD + HEADER_LENGTH
                || !Arrays.equals(data, 0, SIGNATURE.length, SIGNATURE, 0, SIGNATURE.length)) {
            throw new Invalid(false);
        }
        ByteArrayOutputStream kept = new ByteArrayOutputStream(data.length);
        kept.writeBytes(SIGNATURE);
        ByteArrayOutputStream pixels = new ByteArrayOutputStream();
        int position = SIGNATURE.length;
        int colorType = -1;
        int depth = 0;
        boolean palette = false;
        boolean imageData = false;
        boolean imageDataEnded = false;
        boolean first = true;
        while (true) {
            if (position > data.length - CHUNK_OVERHEAD) {
                throw new Invalid(false);
            }
            long length = unsigned(data, position);
            if (length > data.length - position - CHUNK_OVERHEAD) {
                throw new Invalid(false);
            }
            int end = position + CHUNK_OVERHEAD + (int) length;
            String type = new String(data, position + 4, 4, StandardCharsets.ISO_8859_1);
            CRC32 crc = new CRC32();
            crc.update(data, position + 4, 4 + (int) length);
            if (crc.getValue() != unsigned(data, end - 4)) {
                throw new Invalid(false);
            }
            int body = position + 8;
            if (first != type.equals("IHDR")) {
                throw new Invalid(false);
            }
            switch (type) {
                case "IHDR" -> {
                    if (length != HEADER_LENGTH) {
                        throw new Invalid(false);
                    }
                    if (unsigned(data, body) != width || unsigned(data, body + 4) != height) {
                        throw new Invalid(isHeaderOfAnotherSize(data, body));
                    }
                    depth = data[body + 8] & 0xff;
                    colorType = data[body + 9] & 0xff;
                    if (!legal(colorType, depth) || data[body + 10] != 0 || data[body + 11] != 0 || data[body + 12] != 0) {
                        throw new Invalid(false);
                    }
                }
                case "PLTE" -> {
                    if (palette || imageData || length == 0 || length % 3 != 0 || length > MAX_PALETTE_BYTES
                            || colorType == 0 || colorType == 4) {
                        throw new Invalid(false);
                    }
                    palette = true;
                }
                case "tRNS" -> {
                    if (imageData) {
                        throw new Invalid(false);
                    }
                }
                case "IDAT" -> {
                    if (imageDataEnded) {
                        throw new Invalid(false);
                    }
                    imageData = true;
                    pixels.write(data, body, (int) length);
                }
                case "IEND" -> {
                    if (length != 0 || !imageData || (colorType == 3 && !palette) || end != data.length) {
                        throw new Invalid(false);
                    }
                    kept.write(data, position, end - position);
                    inflatesToExactly(pixels.toByteArray(), width, height, colorType, depth);
                    return kept.toByteArray();
                }
                default -> {
                    // A critical chunk this check does not know cannot be vouched for.
                    if (Character.isUpperCase(type.charAt(0)) || !type.chars().allMatch(Character::isLetter)) {
                        throw new Invalid(false);
                    }
                }
            }
            if (imageData && !type.equals("IDAT")) {
                imageDataEnded = true;
            }
            if (isKept(type)) {
                kept.write(data, position, end - position);
            }
            first = false;
            position = end;
        }
    }

    private static boolean isKept(String type) {
        return switch (type) {
            case "IHDR", "PLTE", "tRNS", "IDAT" -> true;
            default -> false;
        };
    }

    /** The header is intact and only differs in size when its own fields are otherwise acceptable. */
    private static boolean isHeaderOfAnotherSize(byte[] data, int body) {
        return legal(data[body + 9] & 0xff, data[body + 8] & 0xff) && unsigned(data, body) > 0 && unsigned(data, body + 4) > 0;
    }

    private static boolean legal(int colorType, int depth) {
        return switch (colorType) {
            case 0 -> depth == 1 || depth == 2 || depth == 4 || depth == 8 || depth == 16;
            case 2, 4, 6 -> depth == 8 || depth == 16;
            case 3 -> depth == 1 || depth == 2 || depth == 4 || depth == 8;
            default -> false;
        };
    }

    private static int channels(int colorType) {
        return switch (colorType) {
            case 2 -> 3;
            case 4 -> 2;
            case 6 -> 4;
            default -> 1;
        };
    }

    /** Inflate the joined IDAT stream with a bound, and check its length and each row's filter byte. */
    private static void inflatesToExactly(byte[] compressed, int width, int height, int colorType, int depth)
            throws Invalid {
        long rowBytes = ((long) width * channels(colorType) * depth + 7) / 8;
        long expected = (rowBytes + 1) * height;
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new Invalid(false);
                }
                for (int index = 0; index < count; index++) {
                    if ((total + index) % (rowBytes + 1) == 0 && (buffer[index] < 0 || buffer[index] > 4)) {
                        throw new Invalid(false);
                    }
                }
                total += count;
                if (total > expected) {
                    throw new Invalid(false);
                }
            }
            if (total != expected || inflater.getRemaining() != 0) {
                throw new Invalid(false);
            }
        } catch (DataFormatException corrupt) {
            throw new Invalid(false);
        } finally {
            inflater.end();
        }
    }

    private static long unsigned(byte[] data, int at) {
        return ((long) (data[at] & 0xff) << 24) | ((data[at + 1] & 0xff) << 16) | ((data[at + 2] & 0xff) << 8)
                | (data[at + 3] & 0xff);
    }
}
