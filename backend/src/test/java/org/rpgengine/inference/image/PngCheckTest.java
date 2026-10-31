package org.rpgengine.inference.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The structural PNG rules, each broken in one named way. */
class PngCheckTest {
    private static final int SIDE = 16;

    private static void assertInvalid(byte[] data, boolean wrongSize) {
        assertThatThrownBy(() -> PngCheck.validate(data, SIDE, SIDE)).isInstanceOfSatisfying(PngCheck.Invalid.class,
                invalid -> assertThat(invalid.wrongSize()).isEqualTo(wrongSize));
    }

    private static byte[] join(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static byte[] header(int depth, int colorType, int interlace) {
        return PngFixtures.header(SIDE, SIDE, depth, colorType, interlace);
    }

    @Test
    void aValidFileKeepsOnlyItsRenderingChunks() throws Exception {
        byte[] file = PngFixtures.valid(SIDE, SIDE);

        byte[] kept = PngCheck.validate(file, SIDE, SIDE);

        assertThat(file.length).isGreaterThan(kept.length);
        assertThat(new String(kept, StandardCharsets.ISO_8859_1)).doesNotContain("tEXt", "private prompt");
        assertThat(PngCheck.validate(kept, SIDE, SIDE)).isEqualTo(kept);
    }

    @Test
    void aFileOfAnotherSizeIsAWrongSizeAndNotAStructuralFault() {
        assertInvalid(PngFixtures.valid(SIDE + 8, SIDE), true);
        assertInvalid(PngFixtures.valid(SIDE, SIDE + 8), true);
    }

    @Test
    void structuralFaultsAreRefused() {
        byte[] good = PngFixtures.valid(SIDE, SIDE);
        byte[] wrongSignature = good.clone();
        wrongSignature[1] = 'X';
        byte[] corrupted = good.clone();
        corrupted[20] ^= 1;
        byte[] trailing = join(good, new byte[] {0});
        byte[] noEnd = Arrays.copyOf(good, good.length - 12);
        byte[] twoEnds = join(good, PngFixtures.chunk("IEND", new byte[0]));
        byte[] idat = PngFixtures.idat(SIDE, SIDE, 3, 0);
        byte[] splitIdat = join(PngFixtures.SIGNATURE, PngFixtures.chunk("IHDR", header(8, 2, 0)),
                PngFixtures.chunk("IDAT", Arrays.copyOf(idat, 5)),
                PngFixtures.chunk("tEXt", "a\0b".getBytes(StandardCharsets.ISO_8859_1)),
                PngFixtures.chunk("IDAT", Arrays.copyOfRange(idat, 5, idat.length)), PngFixtures.chunk("IEND", new byte[0]));
        byte[] unknownCritical = join(PngFixtures.SIGNATURE, PngFixtures.chunk("IHDR", header(8, 2, 0)),
                PngFixtures.chunk("ABCD", new byte[2]), PngFixtures.chunk("IDAT", idat), PngFixtures.chunk("IEND", new byte[0]));
        byte[] noData = join(PngFixtures.SIGNATURE, PngFixtures.chunk("IHDR", header(8, 2, 0)),
                PngFixtures.chunk("IEND", new byte[0]));
        byte[] missingPalette = join(PngFixtures.SIGNATURE, PngFixtures.chunk("IHDR", header(8, 3, 0)),
                PngFixtures.chunk("IDAT", PngFixtures.idat(SIDE, SIDE, 1, 0)), PngFixtures.chunk("IEND", new byte[0]));
        byte[][] files = {new byte[0], new byte[100], wrongSignature, corrupted, trailing, noEnd, twoEnds, splitIdat,
                unknownCritical, noData, missingPalette};
        for (byte[] file : files) {
            assertInvalid(file, false);
        }
    }

    @Test
    void headerFieldsOutsideTheSupportedSetAreRefused() {
        byte[] idat = PngFixtures.idat(SIDE, SIDE, 3, 0);
        int[][] headers = {{8, 2, 1}, {16, 3, 0}, {8, 1, 0}, {4, 2, 0}, {8, 5, 0}};
        for (int[] fields : headers) {
            assertInvalid(PngFixtures.assemble(header(fields[0], fields[1], fields[2]), idat, false), false);
        }
    }

    @Test
    void thePixelStreamMustInflateToExactlyTheImpliedSizeWithLegalFilters() {
        byte[] header = header(8, 2, 0);
        byte[] shortRows = PngFixtures.deflate(new byte[(SIDE * 3 + 1) * (SIDE - 1)]);
        byte[] longRows = PngFixtures.deflate(new byte[(SIDE * 3 + 1) * (SIDE + 1)]);
        byte[] filter = PngFixtures.idat(SIDE, SIDE, 3, 9);
        byte[] notDeflate = new byte[40];
        byte[] truncated = Arrays.copyOf(PngFixtures.idat(SIDE, SIDE, 3, 0), 8);
        for (byte[] stream : new byte[][] {shortRows, longRows, filter, notDeflate, truncated}) {
            assertInvalid(PngFixtures.assemble(header, stream, false), false);
        }
    }

    @Test
    void aBombIsStoppedAtTheImpliedSizeInsteadOfBeingInflated() {
        byte[] bomb = PngFixtures.deflate(new byte[64 * 1024 * 1024]);

        assertInvalid(PngFixtures.assemble(header(8, 2, 0), bomb, false), false);
    }

    @Test
    void aPaletteImageNeedsItsPalette() throws Exception {
        byte[] palette = new byte[6];
        byte[] file = join(PngFixtures.SIGNATURE, PngFixtures.chunk("IHDR", header(8, 3, 0)),
                PngFixtures.chunk("PLTE", palette), PngFixtures.chunk("IDAT", PngFixtures.idat(SIDE, SIDE, 1, 0)),
                PngFixtures.chunk("IEND", new byte[0]));

        assertThat(PngCheck.validate(file, SIDE, SIDE)).isEqualTo(file);
    }
}
