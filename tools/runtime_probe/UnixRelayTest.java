import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dependency-free checks for UnixRelay's handshake and framing rules.
 *
 * <p>Java 21's source launcher runs a single file only, so this class is
 * compiled together with UnixRelay.java inside the pinned JDK image (see
 * test_packaged.py) and run with {@code java -ea -cp OUT UnixRelayTest}. It
 * uses in-memory streams: the relay's frame logic never sees a real socket.
 */
public final class UnixRelayTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        acceptKeyMatchesRfcExample();
        handshakeAcceptsValidResponse();
        handshakeRejectsBadResponses();
        fragmentedTextBecomesOneLine();
        pingIsAnsweredWithMaskedPong();
        rejectsUnsafeServerFrames();
        clientFramesAreMaskedAndSized();
        stdinLimitsAreEnforced();
        System.out.println("UnixRelayTest: " + passed + " checks passed");
    }

    static void check(boolean condition, String name) {
        if (!condition) {
            throw new AssertionError(name);
        }
        passed++;
    }

    static void expectFailure(String code, ThrowingRunnable action) throws Exception {
        try {
            action.run();
        } catch (UnixRelay.RelayException e) {
            check(code.equals(e.getMessage()), code + " (got " + e.getMessage() + ")");
            return;
        }
        throw new AssertionError("expected " + code);
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** RFC 6455 section 1.3 worked example. */
    static void acceptKeyMatchesRfcExample() {
        check("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=".equals(
                UnixRelay.expectedAccept("dGhlIHNhbXBsZSBub25jZQ==")), "rfc-accept");
    }

    /**
     * A fake server stream: it renders its response only when first read, so
     * the response can echo the random key the relay has already written.
     */
    static final class Server extends InputStream {
        private final ByteArrayOutputStream request;
        private final java.util.function.Function<String, String> respond;
        private InputStream response;

        Server(ByteArrayOutputStream request, java.util.function.Function<String, String> respond) {
            this.request = request;
            this.respond = respond;
        }

        @Override
        public int read() throws IOException {
            if (response == null) {
                Matcher key = Pattern.compile("Sec-WebSocket-Key: (\\S+)\r\n")
                        .matcher(request.toString(StandardCharsets.US_ASCII));
                if (!key.find()) {
                    throw new AssertionError("no key in request");
                }
                response = new ByteArrayInputStream(respond.apply(UnixRelay.expectedAccept(key.group(1)))
                        .getBytes(StandardCharsets.ISO_8859_1));
            }
            return response.read();
        }
    }

    static String ok(String accept) {
        return "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n";
    }

    static void handshakeAcceptsValidResponse() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new UnixRelay(new Server(out, UnixRelayTest::ok), out).handshake();
        String request = out.toString(StandardCharsets.US_ASCII);
        check(request.startsWith("GET / HTTP/1.1\r\n"), "request-line");
        check(request.contains("Sec-WebSocket-Version: 13\r\n"), "request-version");
    }

    static void handshakeRejectsBadResponses() throws Exception {
        String[][] cases = {
            {"handshake-status", "HTTP/1.1 400 Bad Request\r\n\r\n"},
            {"handshake-accept", "HTTP/1.1 101 OK\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: wrong\r\n\r\n"},
            {"handshake-accept", "HTTP/1.1 101 OK\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: %s\r\n\r\n"},
            {"handshake-unrequested-extension", "HTTP/1.1 101 OK\r\nUpgrade: websocket\r\n"
                    + "Connection: Upgrade\r\nSec-WebSocket-Accept: %s\r\n"
                    + "Sec-WebSocket-Extensions: permessage-deflate\r\n\r\n"},
            {"handshake-eof", "HTTP/1.1 101 OK\r\n"},
            {"handshake-too-large", "HTTP/1.1 101 OK\r\nX: " + "a".repeat(9000) + "\r\n\r\n"},
        };
        for (String[] c : cases) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            expectFailure(c[0], () -> new UnixRelay(
                    new Server(out, accept -> c[1].replace("%s", accept)), out).handshake());
        }
    }

    /** Server-to-client frame: never masked (RFC 6455 section 5.1). */
    static byte[] frame(int first, byte[] payload) {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(first);
        if (payload.length < 126) {
            f.write(payload.length);
        } else {
            f.write(126);
            f.write(payload.length >>> 8);
            f.write(payload.length & 0xFF);
        }
        f.writeBytes(payload);
        return f.toByteArray();
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            all.writeBytes(part);
        }
        return all.toByteArray();
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static final byte[] CLOSE = frame(0x88, new byte[0]);

    static void fragmentedTextBecomesOneLine() throws Exception {
        byte[] wire = concat(frame(0x01, utf8("{\"id\":")), frame(0x80, utf8("1}")),
                frame(0x81, utf8("{\"m\":\"é\"}")), CLOSE);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        new UnixRelay(new ByteArrayInputStream(wire), new ByteArrayOutputStream()).pumpToStdout(stdout);
        check(stdout.toString(StandardCharsets.UTF_8).equals("{\"id\":1}\n{\"m\":\"é\"}\n"), "reassembly");
    }

    static void pingIsAnsweredWithMaskedPong() throws Exception {
        ByteArrayOutputStream socketOut = new ByteArrayOutputStream();
        byte[] wire = concat(frame(0x89, utf8("hi")), CLOSE);
        new UnixRelay(new ByteArrayInputStream(wire), socketOut).pumpToStdout(new ByteArrayOutputStream());
        byte[] pong = socketOut.toByteArray();
        check(pong.length == 8 && (pong[0] & 0xFF) == 0x8A && (pong[1] & 0xFF) == 0x82, "pong-header");
        check((pong[6] ^ pong[2]) == 'h' && (pong[7] ^ pong[3]) == 'i', "pong-payload");
    }

    static void rejectsUnsafeServerFrames() throws Exception {
        byte[] masked = {(byte) 0x81, (byte) 0x81, 1, 2, 3, 4, 'x'};
        Object[][] cases = {
            {"frame-masked-by-server", masked},
            {"frame-reserved-bits", frame(0xC1, utf8("{}"))},
            {"frame-opcode", frame(0x82, utf8("{}"))},
            {"frame-sequence", frame(0x80, utf8("{}"))},
            {"frame-sequence", concat(frame(0x01, utf8("{")), frame(0x81, utf8("}")))},
            {"frame-control", frame(0x09, new byte[0])},
            {"message-line-break", frame(0x81, utf8("{\n}"))},
            {"message-invalid-utf8", frame(0x81, new byte[] {(byte) 0xC3, (byte) 0x28})},
            {"message-too-large", new byte[] {(byte) 0x81, 127, 0, 0, 0, 0, 0, 0x10, 0, 1}},
            {"frame-length", new byte[] {(byte) 0x81, 127, (byte) 0x80, 0, 0, 0, 0, 0, 0, 0}},
            {"socket-eof", frame(0x81, utf8("{}"))},
        };
        for (Object[] c : cases) {
            expectFailure((String) c[0], () -> new UnixRelay(new ByteArrayInputStream((byte[]) c[1]),
                    new ByteArrayOutputStream()).pumpToStdout(new ByteArrayOutputStream()));
        }
    }

    static void clientFramesAreMaskedAndSized() throws Exception {
        for (int size : new int[] {0, 125, 126, 65535, 65536}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] payload = new byte[size];
            java.util.Arrays.fill(payload, (byte) 'a');
            new UnixRelay(new ByteArrayInputStream(new byte[0]), out).sendFrame(0x1, payload);
            byte[] f = out.toByteArray();
            int header = size < 126 ? 2 : size <= 0xFFFF ? 4 : 10;
            check(f.length == header + 4 + size, "frame-size-" + size);
            check((f[0] & 0xFF) == 0x81 && (f[1] & 0x80) != 0, "frame-masked-" + size);
            boolean roundTrip = true;
            for (int i = 0; i < size; i++) {
                roundTrip &= (f[header + 4 + i] ^ f[header + (i & 3)]) == 'a';
            }
            check(roundTrip, "frame-unmask-" + size);
        }
    }

    static void stdinLimitsAreEnforced() throws Exception {
        UnixRelay relay = new UnixRelay(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
        expectFailure("stdin-partial-line", () -> relay.pumpFromStdin(new ByteArrayInputStream(utf8("{}"))));
        byte[] huge = new byte[UnixRelay.MAX_MESSAGE + 1];
        java.util.Arrays.fill(huge, (byte) 'a');
        expectFailure("stdin-line-too-large", () -> relay.pumpFromStdin(new ByteArrayInputStream(huge)));
    }
}
