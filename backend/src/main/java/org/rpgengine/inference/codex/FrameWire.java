package org.rpgengine.inference.codex;

/**
 * One bidirectional message transport to the runtime: one UTF-8 JSON-RPC message per frame
 * (a line on stdio, a WebSocket text message on the packaged Unix socket).
 *
 * <p>Threading contract: exactly one thread calls {@link #read()}; {@link #write(byte[])} is
 * serialized by the caller; {@link #close()} may be called from any thread, at most once
 * effectively, and unblocks a pending read.
 */
interface FrameWire {
    /** Largest accepted frame in either direction (P01 contract.MAX_MESSAGE). */
    int MAX_FRAME = 1024 * 1024;

    /**
     * Block until the next complete frame arrives.
     *
     * @return frame bytes, never null and never above {@link #MAX_FRAME}
     * @throws WireException with {@code transport-eof} on orderly end, or a framing/read code;
     *         after the first failure every later call fails too
     */
    byte[] read() throws WireException;

    /**
     * Send one frame.
     *
     * @param frame UTF-8 JSON text without line breaks, at most {@link #MAX_FRAME} bytes
     * @throws WireException {@code invalid-frame} or {@code transport-write}
     */
    void write(byte[] frame) throws WireException;

    /** @return how the wire ended; repeated calls return the first record */
    ShutdownRecord close();
}
