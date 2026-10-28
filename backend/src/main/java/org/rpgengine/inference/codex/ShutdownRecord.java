package org.rpgengine.inference.codex;

/**
 * Outcome of closing one wire (docs/06 section 5, P01.4 measurements).
 *
 * @param stage {@code exited}, {@code input-closed}, {@code terminated}, {@code killed} or
 *        {@code unreaped} for an owned child; {@code socket-closed} for a socket connection
 * @param exitCode child exit code, or null for sockets and unreaped children
 * @param millis elapsed shutdown time
 */
record ShutdownRecord(String stage, Integer exitCode, long millis) {
}
