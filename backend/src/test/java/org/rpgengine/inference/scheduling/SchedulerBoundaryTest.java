package org.rpgengine.inference.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.rpgengine.inference.NarrationTaskPort;
import org.rpgengine.inference.contract.ContractRunner;
import org.rpgengine.inference.session.ScopedSessions;
import org.rpgengine.narration.GroundedNarration;

/**
 * Enforces the P08.6 boundary: every engine task waits in the one fair queue. Only the scheduler
 * calls {@link ScopedSessions#submit}, the scheduler never submits to the port directly (it reads
 * the port's readiness and hands it references to reconcile, nothing else), and the callers that
 * run contracts accept the scheduler, never the session registry or the port, as their submitter.
 * The source checks are textual over this codebase's direct style: they resolve each file's
 * variables declared with the registry or port type and look for {@code name.submit(} calls.
 */
class SchedulerBoundaryTest {
    static final Path MAIN = Path.of("src/main/java/org/rpgengine");
    static final Path SESSION = MAIN.resolve("inference/session");

    static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    /** @return names declared with {@code type} in {@code text} (fields, parameters, locals) */
    static List<String> variables(String text, String type) {
        List<String> names = new ArrayList<>();
        Matcher declaration = Pattern.compile("\\b" + type + "\\s+([a-z][A-Za-z0-9]*)\\b").matcher(text);
        while (declaration.find()) {
            names.add(declaration.group(1));
        }
        return names;
    }

    static boolean calls(String text, List<String> names, String method) {
        return names.stream().anyMatch(name -> text.contains(name + "." + method + "("));
    }

    @Test
    void onlyTheSchedulerSubmitsThroughTheSessionRegistry() throws IOException {
        List<String> callers = new ArrayList<>();
        for (Path source : sources()) {
            if (source.startsWith(SESSION)) {
                continue;
            }
            String text = Files.readString(source);
            if (calls(text, variables(text, "ScopedSessions"), "submit")) {
                callers.add(MAIN.relativize(source).toString().replace('\\', '/'));
            }
        }
        assertThat(callers).containsExactly("inference/scheduling/InferenceScheduler.java");
    }

    @Test
    void theSchedulerNeverSubmitsToOrRetiresSessionsOfThePortItself() throws IOException {
        String scheduler = Files.readString(MAIN.resolve("inference/scheduling/InferenceScheduler.java"));
        List<String> ports = variables(scheduler, "NarrationTaskPort");
        assertThat(ports).isNotEmpty();
        assertThat(calls(scheduler, ports, "submit")).isFalse();
        assertThat(calls(scheduler, ports, "retireSession")).isFalse();
        assertThat(calls(scheduler, variables(scheduler, "ScopedSessions"), "submit")).isTrue();
    }

    @Test
    void contractCallersTakeTheSchedulerNotTheRegistryOrThePort() {
        List<String> exposed = new ArrayList<>();
        for (Class<?> caller : List.of(ContractRunner.class, GroundedNarration.class)) {
            for (Constructor<?> constructor : caller.getDeclaredConstructors()) {
                for (Class<?> parameter : constructor.getParameterTypes()) {
                    if (parameter == ScopedSessions.class) {
                        exposed.add(caller.getSimpleName() + " takes ScopedSessions");
                    }
                }
            }
        }
        for (Constructor<?> constructor : ContractRunner.class.getConstructors()) {
            assertThat(constructor.getParameterTypes()).contains(InferenceScheduler.class)
                    .doesNotContain(NarrationTaskPort.class);
        }
        assertThat(exposed).isEmpty();
    }
}
