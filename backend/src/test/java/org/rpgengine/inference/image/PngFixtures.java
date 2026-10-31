package org.rpgengine.inference.image;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/** Builds small PNG files, valid or broken in one named way, for the image adapter's tests. */
final class PngFixtures {
    static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private PngFixtures() {
    }

    /** @return a valid 8-bit RGB PNG of the given size, carrying a {@code tEXt} chunk like a server's */
    static byte[] valid(int width, int height) {
        return assemble(header(width, height, 8, 2, 0), idat(width, height, 3, 0), true);
    }

    /** @return the {@code IHDR} data for a color type, depth and interlace method */
    static byte[] header(int width, int height, int depth, int colorType, int interlace) {
        return ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte) depth).put((byte) colorType)
                .put((byte) 0).put((byte) 0).put((byte) interlace).array();
    }

    /** @return a compressed pixel stream of black rows whose filter byte is {@code filter} */
    static byte[] idat(int width, int height, int bytesPerPixel, int filter) {
        byte[] raw = new byte[(width * bytesPerPixel + 1) * height];
        for (int row = 0; row < height; row++) {
            raw[row * (width * bytesPerPixel + 1)] = (byte) filter;
        }
        return deflate(raw);
    }

    static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater();
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }

    /** @return signature, {@code IHDR}, optional {@code tEXt}, {@code IDAT} and {@code IEND} */
    static byte[] assemble(byte[] header, byte[] compressed, boolean withText) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(SIGNATURE);
        out.writeBytes(chunk("IHDR", header));
        if (withText) {
            out.writeBytes(chunk("tEXt", "parameters\0a private prompt".getBytes(StandardCharsets.ISO_8859_1)));
        }
        out.writeBytes(chunk("IDAT", compressed));
        out.writeBytes(chunk("IEND", new byte[0]));
        return out.toByteArray();
    }

    /** @return a chunk with a correct length and CRC */
    static byte[] chunk(String type, byte[] data) {
        byte[] name = type.getBytes(StandardCharsets.ISO_8859_1);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        return ByteBuffer.allocate(12 + data.length).putInt(data.length).put(name).put(data).putInt((int) crc.getValue())
                .array();
    }

    static String base64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }
}
