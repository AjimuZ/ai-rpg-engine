package org.rpgengine.inference.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rpgengine.inference.EffectiveSettings;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.RuntimeStatus;
import org.rpgengine.inference.TaskResult;

/**
 * The packaged-profile WebSocket client against an in-test Unix-socket server that speaks
 * RFC 6455 like the pinned runtime (P01.1): opening handshake, unmasked server frames,
 * fragmented text messages, pings, and the fail-closed handshake and frame rules.
 */
class UnixSocketFrameWireTest {
    @TempDir
    Path directory;
    private ServerSocketChannel listener;
    private final List<Integer> clientOpcodes = new CopyOnWriteArrayList<>();

    @AfterEach
    void close() throws IOException {
        if (listener != null) {
            listener.close();
        }
    }

    /** Serve one connection: handshake with {@code head} (given the client key), then {@code body}. */
    private Path serve(Function<String, String> head, ConnectionBody body) throws IOException {
        Path socket = directory.resolve("rt.sock");
        listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        listener.bind(UnixDomainSocketAddress.of(socket));
        Thread.ofPlatform().daemon().start(() -> {
            try (SocketChannel channel = listener.accept()) {
                InputStream in = Channels.newInputStream(channel);
                String request = readHead(in);
                String key = request.lines().filter(l -> l.startsWith("Sec-WebSocket-Key:"))
                        .map(l -> l.substring(l.indexOf(':') + 1).trim()).findFirst().orElseThrow();
                String response = head.apply(key);
                if (response == null) {
                    Thread.sleep(5_000);
                    return;
                }
                write(channel, response.getBytes(StandardCharsets.US_ASCII));
                body.run(channel, in);
            } catch (IOException | InterruptedException e) {
                // The client closed the connection.
            }
        });
        return socket;
    }

    @FunctionalInterface
    interface ConnectionBody {
        void run(SocketChannel channel, InputStream in) throws IOException, InterruptedException;
    }

    static String accepting(String key) {
        return "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + UnixSocketFrameWire.expectedAccept(key) + "\r\n\r\n";
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("eof");
            }
            head.write(b);
        }
        return head.toString(StandardCharsets.ISO_8859_1);
    }

    private static void write(SocketChannel channel, byte[] bytes) throws IOException {
        synchronized (channel) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        }
    }

    /** One unmasked server frame. */
    static byte[] frame(boolean fin, int opcode, byte[] payload, boolean masked) {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write((fin ? 0x80 : 0) | opcode);
        int mask = masked ? 0x80 : 0;
        if (payload.length < 126) {
            frame.write(mask | payload.length);
        } else {
            frame.write(mask | 126);
            frame.write(payload.length >>> 8);
            frame.write(payload.length & 0xFF);
        }
        if (masked) {
            frame.writeBytes(new byte[4]);
        }
        frame.writeBytes(payload);
        return frame.toByteArray();
    }

    /** Read one masked client frame; return opcode and unmasked payload. */
    private static Object[] readClientFrame(InputStream in) throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        if (b0 < 0 || b1 < 0) {
            throw new IOException("eof");
        }
        assertThat(b1 & 0x80).as("client frames are masked").isNotZero();
        long length = b1 & 0x7F;
        if (length == 126) {
            length = (in.read() << 8) | in.read();
        } else if (length == 127) {
            length = ByteBuffer.wrap(in.readNBytes(8)).getLong();
        }
        byte[] mask = in.readNBytes(4);
        byte[] payload = in.readNBytes((int) length);
        for (int i = 0; i < payload.length; i++) {
            payload[i] ^= mask[i & 3];
        }
        return new Object[] {b0 & 0x0F, payload};
    }

    /** Run the scripted server over WebSocket frames, fragmenting long messages after a ping. */
    private ConnectionBody scripted(ScriptedAppServer server) {
        return (channel, in) -> {
            write(channel, frame(true, 0x9, "ping".getBytes(StandardCharsets.US_ASCII), false));
            ScriptedAppServer.Connection connection = server.new Connection(message -> {
                try {
                    if (message.length > 64) {
                        int half = message.length / 2;
                        write(channel, frame(false, 0x1, java.util.Arrays.copyOfRange(message, 0, half), false));
                        write(channel, frame(true, 0x0, java.util.Arrays.copyOfRange(message, half, message.length),
                                false));
                    } else {
                        write(channel, frame(true, 0x1, message, false));
                    }
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }, () -> {
            });
            while (true) {
                Object[] received = readClientFrame(in);
                int opcode = (int) received[0];
                clientOpcodes.add(opcode);
                if (opcode == 0x8) {
                    return;
                }
                if (opcode == 0x1) {
                    connection.handle((byte[]) received[1]);
                }
            }
        };
    }

    @Test
    void adapterCompletesATaskOverThePackagedSocket() throws Exception {
        ScriptedAppServer server = new ScriptedAppServer();
        Path socket = serve(UnixSocketFrameWireTest::accepting, scripted(server));
        CodexSettings base = CodexTaskAdapterTest.settings(2);
        CodexSettings settings = new CodexSettings(CodexSettings.Transport.UNIX, socket, List.of(), null,
                base.runtimeCwd(), 2, base.requestTimeout(), base.handshakeTimeout(), base.interruptTimeout(),
                base.reconnectInitial(), base.reconnectMax(), base.stdioGrace(), base.maxSessions());
        CodexTaskAdapter adapter = new CodexTaskAdapter(settings,
                CodexTaskAdapterTest.resolver(slot -> new EffectiveSettings("model-a", "medium")), settings.wireFactory());
        adapter.start();
        try {
            ScriptedAppServer.await(() -> adapter.status().state() == RuntimeStatus.State.READY);
            TaskResult result = CodexTaskAdapterTest.result(adapter.submit(CodexTaskAdapterTest.request(
                    UUID.randomUUID(), InferenceSlot.STANDARD, "Narrate.", Duration.ofSeconds(5))));
            assertThat(result.status()).isEqualTo(TaskResult.Status.COMPLETED);
            assertThat(result.output()).isEqualTo(ScriptedAppServer.OUTPUT);
        } finally {
            adapter.stop();
        }
        assertThat(adapter.lastShutdown().stage()).isEqualTo("socket-closed");
        ScriptedAppServer.await(() -> clientOpcodes.contains(0x8));
        assertThat(clientOpcodes).contains(0xA, 0x1, 0x8);
    }

    @Test
    void handshakeIsStrict() throws Exception {
        Path socket = serve(key -> "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: wrong\r\n\r\n", (c, in) -> { });
        assertThatThrownBy(() -> new UnixSocketFrameWire(socket, Duration.ofSeconds(2))).hasMessage("handshake-accept");
        listener.close();
        java.nio.file.Files.delete(socket);
        serve(key -> accepting(key).replace("\r\n\r\n", "\r\nSec-WebSocket-Extensions: permessage-deflate\r\n\r\n"),
                (c, in) -> { });
        assertThatThrownBy(() -> new UnixSocketFrameWire(socket, Duration.ofSeconds(2)))
                .hasMessage("handshake-unrequested-extension");
        listener.close();
        java.nio.file.Files.delete(socket);
        serve(key -> "HTTP/1.1 403 Forbidden\r\n\r\n", (c, in) -> { });
        assertThatThrownBy(() -> new UnixSocketFrameWire(socket, Duration.ofSeconds(2))).hasMessage("handshake-status");
    }

    @Test
    void silentServerHitsTheHandshakeTimeoutAndMissingSocketIsUnreachable() throws Exception {
        Path socket = serve(key -> null, (c, in) -> { });
        assertThatThrownBy(() -> new UnixSocketFrameWire(socket, Duration.ofMillis(300))).hasMessage("handshake-timeout");
        assertThatThrownBy(() -> new UnixSocketFrameWire(directory.resolve("absent.sock"), Duration.ofSeconds(1)))
                .hasMessage("runtime-unreachable");
    }

    @Test
    void binaryAndMaskedServerFramesFailTheWire() throws Exception {
        Path socket = serve(UnixSocketFrameWireTest::accepting, (channel, in) -> {
            write(channel, frame(true, 0x1, "{\"a\":1}".getBytes(StandardCharsets.UTF_8), false));
            write(channel, frame(true, 0x2, new byte[] {1}, false));
            Thread.sleep(2_000);
        });
        UnixSocketFrameWire wire = new UnixSocketFrameWire(socket, Duration.ofSeconds(2));
        assertThat(new String(wire.read(), StandardCharsets.UTF_8)).isEqualTo("{\"a\":1}");
        assertThatThrownBy(wire::read).hasMessage("frame-opcode");
        assertThatThrownBy(wire::read).as("sticky").hasMessage("frame-opcode");
        wire.close();
        listener.close();
        java.nio.file.Files.delete(socket);

        serve(UnixSocketFrameWireTest::accepting, (channel, in) -> {
            write(channel, frame(true, 0x1, "{}".getBytes(StandardCharsets.UTF_8), true));
            Thread.sleep(2_000);
        });
        UnixSocketFrameWire masked = new UnixSocketFrameWire(socket, Duration.ofSeconds(2));
        assertThatThrownBy(masked::read).hasMessage("frame-masked-by-server");
        masked.close();
    }

    @Test
    void closeFrameIsAnOrderlyEnd() throws Exception {
        Path socket = serve(UnixSocketFrameWireTest::accepting, (channel, in) -> {
            write(channel, frame(true, 0x8, new byte[] {0x03, (byte) 0xE8}, false));
            Thread.sleep(2_000);
        });
        UnixSocketFrameWire wire = new UnixSocketFrameWire(socket, Duration.ofSeconds(2));
        assertThatThrownBy(wire::read).hasMessage("transport-eof");
        assertThat(wire.close().stage()).isEqualTo("socket-closed");
    }
}
