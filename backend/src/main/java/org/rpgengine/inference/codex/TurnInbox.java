package org.rpgengine.inference.codex;

import java.util.ArrayDeque;

/**
 * Bounded, ordered event queue of one registered turn, filled by the generation's reader
 * thread and drained by the dispatcher. Overflow or a generation failure fails the inbox
 * explicitly; once failed, buffered events are discarded, as the P01.4 client discards a
 * closed generation's notifications, and every poll raises the failure code.
 */
final class TurnInbox {
    /** Events retained for one turn before it fails with {@code turn-event-limit}. */
    static final int MAX_EVENTS = 1024;

    private final TurnKey key;
    private final ArrayDeque<RpcEvent> events = new ArrayDeque<>();
    private String failure;
    private long discarded;

    /** @param key exact turn identity */
    TurnInbox(TurnKey key) {
        this.key = key;
    }

    /** @return the turn identity */
    TurnKey key() {
        return key;
    }

    /** Append one correlated event; overflow fails the inbox instead of dropping silently. */
    synchronized void offer(RpcEvent event) {
        if (failure != null) {
            discarded++;
            return;
        }
        if (events.size() >= MAX_EVENTS) {
            fail("turn-event-limit");
            return;
        }
        events.addLast(event);
        notifyAll();
    }

    /** Fail with {@code code} unless already failed; buffered events are discarded. */
    synchronized void fail(String code) {
        if (failure == null) {
            failure = code;
            discarded += events.size();
            events.clear();
        }
        notifyAll();
    }

    /**
     * Wait for the next event.
     *
     * @param timeoutNanos longest wait; non-positive means do not wait
     * @return the next event, or null when none arrived in time
     * @throws ProtocolException with the failure code once failed
     * @throws InterruptedException when the waiting thread is interrupted
     */
    synchronized RpcEvent poll(long timeoutNanos) throws ProtocolException, InterruptedException {
        long deadline = System.nanoTime() + timeoutNanos;
        while (events.isEmpty()) {
            if (failure != null) {
                throw new ProtocolException(failure);
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return null;
            }
            wait(Math.max(1, remaining / 1_000_000));
        }
        return events.removeFirst();
    }

    /** @return events discarded after failure */
    synchronized long discarded() {
        return discarded;
    }
}
