import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/**
 * Packaged-profile relay for the P01.1 discovery proof (Java 21, JDK only).
 *
 * <p>The pinned runtime's {@code app-server --listen unix://PATH} speaks
 * WebSocket (RFC 6455) over the Unix-domain socket. This relay connects to
 * that socket, performs the client opening handshake, and bridges one JSON-RPC
 * message per WebSocket text message to one line on its own stdin/stdout. The
 * Python probe drives it exactly like the stdio runtime, so both profiles share
 * one discovery contract.
 *
 * <p>It is deliberately written in Java with only JDK classes because the
 * future engine adapter is Java 21: the evidence shows that the standard
 * library ({@link UnixDomainSocketAddress}, JEP 380) reaches the socket with
 * no native code or third-party WebSocket library. {@code java.net.http}
 * cannot be used because it has no Unix-domain transport.
 *
 * <p>Fail-closed rules: a bad handshake, a masked or reserved-bit server frame,
 * a binary message, invalid UTF-8, a message containing a raw line break (it
 * would corrupt line framing), or a message above {@link #MAX_MESSAGE} ends the
 * relay with a non-zero status. Stderr receives a short code only, never frame
 * contents, because runtime messages can contain account data.
 */
public final class UnixRelay {
    /** Same bound as contract.MAX_MESSAGE on the Python side. */
    static final int MAX_MESSAGE = 1024 * 1024;
    /** Upper bound for the HTTP 101 response head. */
    static final int MAX_HANDSHAKE = 8192;
    /** RFC 6455 section 1.3 key-derivation constant. */
    static final String ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final InputStream in;
    private final OutputStream out;
    private final SecureRandom random = new SecureRandom();

    UnixRelay(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    /** A protocol violation; the message is a fixed diagnostic code. */
    static final class RelayException extends IOException {
        private static final long serialVersionUID = 1L;

        RelayException(String code) {
            super(code);
        }
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            fail("usage");
        }
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(args[0]));
            UnixRelay relay = new UnixRelay(new BufferedInputStream(input(channel)),
                    output(channel));
            relay.handshake();
            Thread reader = new Thread(() -> {
                try {
                    relay.pumpToStdout(System.out);
                    // Orderly close from the runtime: end the relay normally.
                    System.exit(0);
                } catch (IOException e) {
                    fail(code(e));
                }
            }, "socket-reader");
            reader.setDaemon(true);
            reader.start();
            relay.pumpFromStdin(System.in);
            // Stdin EOF is the probe's shutdown signal, as with the stdio
            // runtime: send a normal close and give the runtime a moment to
            // answer before the JVM exits.
            relay.sendFrame(0x8, new byte[] {0x03, (byte) 0xE8});
            reader.join(2000);
            System.exit(0);
        } catch (IOException e) {
            fail(code(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted");
        }
    }

    /*
     * Direct stream adapters. Channels.newInputStream/newOutputStream lock the
     * channel's blocking lock on some JDK lines, so a reader blocked on the
     * socket could stall a concurrent pong or request write. SocketChannel
     * itself allows one concurrent reader and one concurrent writer.
     */
    static InputStream input(SocketChannel channel) {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) {
                    return 0;
                }
                return channel.read(ByteBuffer.wrap(b, off, len));
            }
        };
    }

    static OutputStream output(SocketChannel channel) {
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                ByteBuffer buffer = ByteBuffer.wrap(b, off, len);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            }
        };
    }

    /** Map exceptions to codes; only our own codes are passed through verbatim. */
    static String code(IOException e) {
        return e instanceof RelayException ? e.getMessage() : "socket-io";
    }

    static void fail(String code) {
        System.err.println("relay-error: " + code);
        System.exit(2);
    }

    /** Client opening handshake (RFC 6455 section 4.1) with strict 101 checks. */
    void handshake() throws IOException {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);
        String request = "GET / HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        String head = readResponseHead();
        String[] lines = head.split("\r\n");
        if (!lines[0].startsWith("HTTP/1.1 101")) {
            throw new RelayException("handshake-status");
        }
        boolean upgrade = false;
        boolean connection = false;
        String accept = null;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                throw new RelayException("handshake-header");
            }
            String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1).trim();
            switch (name) {
                case "upgrade" -> upgrade = value.equalsIgnoreCase("websocket");
                case "connection" -> connection = value.toLowerCase(Locale.ROOT).contains("upgrade");
                case "sec-websocket-accept" -> accept = value;
                // No extension or subprotocol was offered, so the server must
                // not select one; accepting it would change the frame format.
                case "sec-websocket-extensions", "sec-websocket-protocol" ->
                        throw new RelayException("handshake-unrequested-extension");
                default -> { }
            }
        }
        if (!upgrade || !connection || !expectedAccept(key).equals(accept)) {
            throw new RelayException("handshake-accept");
        }
    }

    static String expectedAccept(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest((key + ACCEPT_GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Read byte by byte up to the blank line. The head parser never reads
     * past that line, so any frame bytes that follow stay in the shared input
     * stream (main() wraps the socket in a BufferedInputStream to make these
     * single-byte reads cheap) for pumpToStdout to read.
     */
    private String readResponseHead() throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (head.size() < MAX_HANDSHAKE) {
            int b = in.read();
            if (b < 0) {
                throw new RelayException("handshake-eof");
            }
            head.write(b);
            byte[] bytes = head.toByteArray();
            int n = bytes.length;
            if (n >= 4 && bytes[n - 4] == '\r' && bytes[n - 3] == '\n'
                    && bytes[n - 2] == '\r' && bytes[n - 1] == '\n') {
                return new String(bytes, 0, n - 4, StandardCharsets.ISO_8859_1);
            }
        }
        throw new RelayException("handshake-too-large");
    }

    /** Forward stdin lines as masked text frames until EOF. */
    void pumpFromStdin(InputStream stdin) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = stdin.read()) >= 0) {
            if (b == '\n') {
                sendFrame(0x1, line.toByteArray());
                line.reset();
            } else if (line.size() >= MAX_MESSAGE) {
                throw new RelayException("stdin-line-too-large");
            } else {
                line.write(b);
            }
        }
        if (line.size() > 0) {
            throw new RelayException("stdin-partial-line");
        }
    }

    /**
     * Client frames must be masked (RFC 6455 section 5.3). Synchronized
     * because the reader thread answers pings while the main thread sends
     * requests, and frames must not interleave on the socket.
     */
    synchronized void sendFrame(int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 14);
        frame.write(0x80 | opcode);
        if (payload.length < 126) {
            frame.write(0x80 | payload.length);
        } else if (payload.length <= 0xFFFF) {
            frame.write(0x80 | 126);
            frame.write(payload.length >>> 8);
            frame.write(payload.length & 0xFF);
        } else {
            frame.write(0x80 | 127);
            frame.write(ByteBuffer.allocate(8).putLong(payload.length).array());
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        frame.write(mask);
        for (int i = 0; i < payload.length; i++) {
            frame.write(payload[i] ^ mask[i & 3]);
        }
        out.write(frame.toByteArray());
        out.flush();
    }

    /**
     * Reassemble text messages and print each as one line. Returns on an
     * orderly close frame; throws on any protocol violation or EOF.
     */
    void pumpToStdout(OutputStream stdout) throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        boolean inMessage = false;
        while (true) {
            int b0 = readByte();
            int b1 = readByte();
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            if ((b0 & 0x70) != 0) {
                throw new RelayException("frame-reserved-bits");
            }
            if ((b1 & 0x80) != 0) {
                throw new RelayException("frame-masked-by-server");
            }
            long length = b1 & 0x7F;
            if (length == 126) {
                length = (readByte() << 8) | readByte();
            } else if (length == 127) {
                length = ByteBuffer.wrap(readExact(8)).getLong();
                if (length < 0) {
                    throw new RelayException("frame-length");
                }
            }
            boolean control = (opcode & 0x8) != 0;
            if (control && (!fin || length > 125)) {
                throw new RelayException("frame-control");
            }
            if (!control && message.size() + length > MAX_MESSAGE) {
                throw new RelayException("message-too-large");
            }
            byte[] payload = readExact((int) length);
            switch (opcode) {
                case 0x1, 0x0 -> {
                    // A text frame starts a message; a continuation needs one.
                    if ((opcode == 0x1) == inMessage) {
                        throw new RelayException("frame-sequence");
                    }
                    message.write(payload);
                    inMessage = !fin;
                    if (fin) {
                        writeLine(stdout, message.toByteArray());
                        message.reset();
                    }
                }
                case 0x8 -> {
                    return;
                }
                case 0x9 -> sendFrame(0xA, payload);
                case 0xA -> { }
                default -> throw new RelayException("frame-opcode");
            }
        }
    }

    private static void writeLine(OutputStream stdout, byte[] text) throws IOException {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(text));
        } catch (CharacterCodingException e) {
            throw new RelayException("message-invalid-utf8");
        }
        for (byte b : text) {
            if (b == '\n' || b == '\r') {
                throw new RelayException("message-line-break");
            }
        }
        stdout.write(text);
        stdout.write('\n');
        stdout.flush();
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new RelayException("socket-eof");
        }
        return b;
    }

    private byte[] readExact(int length) throws IOException {
        byte[] data = in.readNBytes(length);
        if (data.length != length) {
            throw new RelayException("socket-eof");
        }
        return data;
    }
}
