package org.rpgengine.inference.codex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redacting stderr drain for an owned runtime child, ported from the P01.4 probe. It always
 * drains the stream so the child never blocks on a full pipe, classifies only the first
 * {@link #CLASSIFY_LIMIT} bytes, keeps at most a {@link #LINE_LIMIT}-byte prefix of a line in
 * memory and retains only {@code LEVEL module} labels: messages are discarded and every module
 * outside {@link #MODULES} becomes {@code unclassified}, because runtime messages can carry
 * paths, prompts or tokens. Thread-safe: one drain thread writes, any thread reads a summary.
 */
final class StderrDiagnostics {
    static final int CLASSIFY_LIMIT = 65_536;
    static final int LINE_LIMIT = 512;
    static final int TAIL = 16;
    static final Set<String> MODULES = Set.of("codex_app_server", "codex_core::config");
    private static final Pattern STRUCTURED = Pattern.compile(
            "\\d{4}-\\d\\d-\\d\\dT[0-9:.]+Z +(ERROR|WARN|INFO|DEBUG|TRACE) +"
                    + "([a-z][a-z0-9_]{0,47}(?:::[a-z][a-z0-9_]{0,47}){0,7}):.*", Pattern.DOTALL);

    private long bytes;
    private boolean truncated;
    private long lines;
    private final Map<String, Long> levels = new TreeMap<>();
    private final ArrayDeque<String> tail = new ArrayDeque<>();

    /**
     * Reduce one raw line (or its bounded prefix) to a fixed entry.
     *
     * @param line raw bytes without the newline
     * @return {@code LEVEL module} for the runtime's structured shape, else {@code unstructured}
     */
    static String classify(byte[] line) {
        Matcher match = STRUCTURED.matcher(new String(line, StandardCharsets.ISO_8859_1));
        if (!match.matches()) {
            return "unstructured";
        }
        String module = MODULES.contains(match.group(2)) ? match.group(2) : "unclassified";
        return match.group(1) + " " + module;
    }

    /**
     * Drain {@code stream} to its end, classifying within the budget. Read errors end the drain
     * quietly: they only happen while the child is being closed.
     *
     * @param stream the child's stderr
     */
    void drain(InputStream stream) {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        try {
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                synchronized (this) {
                    for (int i = 0; i < read; i++) {
                        if (bytes >= CLASSIFY_LIMIT) {
                            truncated = true;
                            break;
                        }
                        bytes++;
                        if (buffer[i] == '\n') {
                            record(line.toByteArray());
                            line.reset();
                        } else if (line.size() < LINE_LIMIT) {
                            line.write(buffer[i]);
                        }
                    }
                }
            }
        } catch (IOException e) {
            // The stream closes during shutdown; the counts gathered so far stand.
        }
        synchronized (this) {
            if (line.size() > 0) {
                record(line.toByteArray());
            }
        }
    }

    private void record(byte[] line) {
        String entry = classify(line);
        lines++;
        levels.merge(entry.split(" ", 2)[0], 1L, Long::sum);
        if (tail.size() == TAIL) {
            tail.removeFirst();
        }
        tail.addLast(entry);
    }

    /**
     * Bounded, redacted evidence.
     *
     * @param bytes classified bytes
     * @param truncated whether the classification budget was exceeded
     * @param lines classified lines
     * @param levels entries per level or {@code unstructured}
     * @param tail most recent entries, at most {@link #TAIL}
     */
    record Summary(long bytes, boolean truncated, long lines, Map<String, Long> levels, List<String> tail) {
    }

    /** @return a copy of the current counters; contains no message text */
    synchronized Summary summary() {
        return new Summary(bytes, truncated, lines, Map.copyOf(levels), List.copyOf(tail));
    }
}
