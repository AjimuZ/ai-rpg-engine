package org.rpgengine.inference.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.rpgengine.inference.NarrationTaskPort;
import org.rpgengine.inference.contract.ContractRunner;

/**
 * Enforces the P08.3 boundary: {@link ScopedSessions} is the only main-code submitter of the task
 * port, so no engine code can choose a raw logical session and mix scopes. The source checks are
 * textual (they look for {@code new TaskRequest(} and for port calls in files that name the port),
 * which is enough for this codebase's direct style; the reflection check pins the runner's public
 * API so a caller-chosen session cannot come back.
 */
class SessionBoundaryTest {
    static final Path MAIN = Path.of("src/main/java/org/rpgengine");
    static final Path INFERENCE = MAIN.resolve("inference");
    static final Path SESSION = INFERENCE.resolve("session");
    static final Path ADAPTER = INFERENCE.resolve("codex");
    /** The fair scheduler holds the port for readiness and reconciliation only; SchedulerBoundaryTest pins that. */
    static final Path SCHEDULING = INFERENCE.resolve("scheduling");

    static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    /** @return true for the port's own package (definition and implementations) and the adapter */
    static boolean portSide(Path source) {
        return source.getParent().equals(INFERENCE) || source.startsWith(ADAPTER);
    }

    @Test
    void onlyTheScopedRegistryBuildsTaskRequests() throws IOException {
        List<String> builders = new ArrayList<>();
        for (Path source : sources()) {
            if (Files.readString(source).contains("new TaskRequest(")) {
                builders.add(MAIN.relativize(source).toString().replace('\\', '/'));
            }
        }
        assertThat(builders).containsExactly("inference/session/ScopedSessions.java");
    }

    @Test
    void noOtherCodeSubmitsToOrRetiresSessionsOfThePort() throws IOException {
        List<String> callers = new ArrayList<>();
        for (Path source : sources()) {
            if (portSide(source) || source.startsWith(SESSION) || source.startsWith(SCHEDULING)) {
                continue;
            }
            String text = Files.readString(source);
            if (text.contains("NarrationTaskPort") && (text.contains(".submit(") || text.contains(".retireSession("))) {
                callers.add(MAIN.relativize(source).toString());
            }
        }
        assertThat(callers).isEmpty();
    }

    @Test
    void theContractRunnerAcceptsNeitherAPortNorASessionIdentifier() {
        List<String> exposed = new ArrayList<>();
        List<Executable> members = new ArrayList<>();
        for (Constructor<?> constructor : ContractRunner.class.getConstructors()) {
            members.add(constructor);
        }
        for (Method method : ContractRunner.class.getMethods()) {
            if (method.getDeclaringClass() == ContractRunner.class && Modifier.isPublic(method.getModifiers())) {
                members.add(method);
            }
        }
        for (Executable member : members) {
            for (Class<?> parameter : member.getParameterTypes()) {
                if (parameter == UUID.class || NarrationTaskPort.class.isAssignableFrom(parameter)) {
                    exposed.add(member.getName() + " takes " + parameter.getSimpleName());
                }
            }
        }
        assertThat(exposed).isEmpty();
        assertThat(members).isNotEmpty();
    }
}
