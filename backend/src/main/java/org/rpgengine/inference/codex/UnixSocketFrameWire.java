package org.rpgengine.inference.codex;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Packaged-profile transport: a WebSocket (RFC 6455) client over the runtime's private
 * Unix-domain socket, one JSON-RPC message per text message (docs/22 section 3). Ported from
 * the P01.1 reference relay {@code tools/runtime_probe/UnixRelay.java}; only JDK classes are
 * used because {@code java.net.http} has no Unix-domain transport.
 *
 * <p>Fail-closed rules: a bad opening handshake (status, accept key, unrequested extension or
 * subprotocol), a masked or reserved-bit server frame, a binary or unknown opcode, a bad
 * fragment sequence, an oversized control frame or a message above {@link #MAX_FRAME} fails
 * the wire with a fixed code. Pings are answered with pongs; a close frame is an orderly
 * {@code transport-eof}. The handshake is bounded by a watchdog that closes the channel, since
 * blocking socket channels have no read timeout.
 */
final class UnixSocketFrameWire implements FrameWire {
    /** Upper bound for the HTTP 101 response head. */
    static final int MAX_HANDSHAKE = 8192;
    /** RFC 6455 section 1.3 key-derivation constant. */
    static final String ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final SocketChannel channel;
    private final InputStream in;
    private final SecureRandom random = new SecureRandom();
    private final Object writeLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private WireException failure;
    private ShutdownRecord shutdown;

    /**
     * Connect and complete the opening handshake.
     *
     * @param socket path of the runtime socket (the real socket file, not its symlink, in the
     *        packaged IPC volume)
     * @param handshakeTimeout bound for connect plus handshake
     * @throws WireException {@code runtime-unreachable} or a {@code handshake-*} code
     */
    UnixSocketFrameWire(Path socket, Duration handshakeTimeout) throws WireException {
        try {
            channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        } catch (IOException e) {
            throw new WireException("runtime-unreachable");
        }
        in = new BufferedInputStream(input(channel));
        AtomicBoolean done = new AtomicBoolean();
        AtomicBoolean timedOut = new AtomicBoolean();
        Thread watchdog = Thread.ofPlatform().daemon().name("codex-handshake-watchdog").start(() -> {
            try {
                Thread.sleep(handshakeTimeout.toMillis());
                if (!done.get()) {
                    timedOut.set(true);
                    channel.close();
                }
            } catch (InterruptedException | IOException e) {
                // Interrupted means the handshake finished in time.
            }
        });
        try {
            try {
                channel.connect(UnixDomainSocketAddress.of(socket));
            } catch (IOException | RuntimeException e) {
                throw new WireException("runtime-unreachable");
            }
            handshake();
            done.set(true);
        } catch (WireException e) {
            closeQuietly();
            throw timedOut.get() ? new WireException("handshake-timeout") : e;
        } finally {
            done.set(true);
            watchdog.interrupt();
        }
    }

    /*
     * Direct stream adapter. Channels.newInputStream locks the channel's blocking lock on some
     * JDK lines, which would stall a concurrent pong or request write behind a blocked read;
     * a SocketChannel itself allows one concurrent reader and one concurrent writer.
     */
    private static InputStream input(SocketChannel channel) {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return len == 0 ? 0 : channel.read(ByteBuffer.wrap(b, off, len));
            }
        };
    }

    private void handshake() throws WireException {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);
        String request = "GET / HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
        writeRaw(request.getBytes(StandardCharsets.US_ASCII), "handshake-write");
        String[] lines = readResponseHead().split("\r\n");
        if (!lines[0].startsWith("HTTP/1.1 101")) {
            throw new WireException("handshake-status");
        }
        boolean upgrade = false;
        boolean connection = false;
        String accept = null;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                throw new WireException("handshake-header");
            }
            String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1).trim();
            switch (name) {
                case "upgrade" -> upgrade = value.equalsIgnoreCase("websocket");
                case "connection" -> connection = value.toLowerCase(Locale.ROOT).contains("upgrade");
                case "sec-websocket-accept" -> accept = value;
                // Nothing was offered, so a selected extension would change the frame format.
                case "sec-websocket-extensions", "sec-websocket-protocol" ->
                        throw new WireException("handshake-unrequested-extension");
                default -> {
                }
            }
        }
        if (!upgrade || !connection || !expectedAccept(key).equals(accept)) {
            throw new WireException("handshake-accept");
        }
    }

    /**
     * @param key client nonce in base64
     * @return the RFC 6455 accept value the server must return for {@code key}
     */
    static String expectedAccept(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest((key + ACCEPT_GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Read byte by byte up to the blank line, never past it, so frame bytes stay buffered. */
    private String readResponseHead() throws WireException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        try {
            while (head.size() < MAX_HANDSHAKE) {
                int b = in.read();
                if (b < 0) {
                    throw new WireException("handshake-eof");
                }
                head.write(b);
                byte[] bytes = head.toByteArray();
                int n = bytes.length;
                if (n >= 4 && bytes[n - 4] == '\r' && bytes[n - 3] == '\n' && bytes[n - 2] == '\r'
                        && bytes[n - 1] == '\n') {
                    return new String(bytes, 0, n - 4, StandardCharsets.ISO_8859_1);
                }
            }
        } catch (WireException e) {
            throw e;
        } catch (IOException e) {
            throw new WireException("handshake-eof");
        }
        throw new WireException("handshake-too-large");
    }

    /** Reassemble the next text message; answer pings on the way. */
    @Override
    public byte[] read() throws WireException {
        if (failure != null) {
            throw failure;
        }
        try {
            ByteArrayOutputStream message = new ByteArrayOutputStream();
            boolean inMessage = false;
            while (true) {
                int b0 = readByte();
                int b1 = readByte();
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                if ((b0 & 0x70) != 0) {
                    throw new WireException("frame-reserved-bits");
                }
                if ((b1 & 0x80) != 0) {
                    throw new WireException("frame-masked-by-server");
                }
                long length = b1 & 0x7F;
                if (length == 126) {
                    length = ((long) readByte() << 8) | readByte();
                } else if (length == 127) {
                    length = ByteBuffer.wrap(readExact(8)).getLong();
                    if (length < 0) {
                        throw new WireException("frame-length");
                    }
                }
                boolean control = (opcode & 0x8) != 0;
                if (control && (!fin || length > 125)) {
                    throw new WireException("frame-control");
                }
                if (!control && message.size() + length > MAX_FRAME) {
                    throw new WireException("message-too-large");
                }
                byte[] payload = readExact((int) length);
                switch (opcode) {
                    case 0x1, 0x0 -> {
                        // A text frame starts a message; a continuation needs one in progress.
                        if ((opcode == 0x1) == inMessage) {
                            throw new WireException("frame-sequence");
                        }
                        message.writeBytes(payload);
                        inMessage = !fin;
                        if (fin) {
                            return message.toByteArray();
                        }
                    }
                    case 0x8 -> throw new WireException("transport-eof");
                    case 0x9 -> sendFrame(0xA, payload);
                    case 0xA -> {
                    }
                    default -> throw new WireException("frame-opcode");
                }
            }
        } catch (WireException e) {
            failure = e;
            throw e;
        }
    }

    private int readByte() throws WireException {
        try {
            int b = in.read();
            if (b < 0) {
                throw new WireException("transport-eof");
            }
            return b;
        } catch (WireException e) {
            throw e;
        } catch (IOException e) {
            throw new WireException(closed.get() ? "transport-eof" : "transport-read");
        }
    }

    private byte[] readExact(int length) throws WireException {
        try {
            byte[] data = in.readNBytes(length);
            if (data.length != length) {
                throw new WireException("transport-eof");
            }
            return data;
        } catch (WireException e) {
            throw e;
        } catch (IOException e) {
            throw new WireException(closed.get() ? "transport-eof" : "transport-read");
        }
    }

    /** Send one masked text frame. */
    @Override
    public void write(byte[] frame) throws WireException {
        if (frame.length > MAX_FRAME) {
            throw new WireException("invalid-frame");
        }
        sendFrame(0x1, frame);
    }

    /** Client frames are always masked (RFC 6455 section 5.3); writes never interleave. */
    private void sendFrame(int opcode, byte[] payload) throws WireException {
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
            frame.writeBytes(ByteBuffer.allocate(8).putLong(payload.length).array());
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        frame.writeBytes(mask);
        for (int i = 0; i < payload.length; i++) {
            frame.write(payload[i] ^ mask[i & 3]);
        }
        writeRaw(frame.toByteArray(), "transport-write");
    }

    private void writeRaw(byte[] bytes, String code) throws WireException {
        synchronized (writeLock) {
            try {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            } catch (IOException e) {
                throw new WireException(code);
            }
        }
    }

    /** Send a normal close frame when possible and close the channel; never throws. */
    @Override
    public synchronized ShutdownRecord close() {
        if (shutdown != null) {
            return shutdown;
        }
        long started = System.nanoTime();
        if (channel.isOpen() && channel.isConnected()) {
            try {
                sendFrame(0x8, new byte[] {0x03, (byte) 0xE8});
            } catch (WireException e) {
                // The peer is already gone; closing the channel is enough.
            }
        }
        closeQuietly();
        shutdown = new ShutdownRecord("socket-closed", null, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        return shutdown;
    }

    private void closeQuietly() {
        closed.set(true);
        try {
            channel.close();
        } catch (IOException e) {
            // Closing an already broken channel cannot fail meaningfully.
        }
    }
}
