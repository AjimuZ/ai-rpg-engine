package org.rpgengine.inference;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Enforces the P08.1 boundary: no Codex-specific type leaks into domain or application code.
 * Only the adapter package may name its own types, the adapter depends on nothing of the engine
 * except the neutral port package, every adapter type is package-private, and the neutral API
 * exposes only JDK and neutral types. The local adapter (P21.3-B) keeps the same rules.
 */
class InferenceBoundaryTest {
    static final Path MAIN = Path.of("src/main/java/org/rpgengine");
    static final Path ADAPTER = MAIN.resolve("inference/codex");
    static final Path LOCAL_ADAPTER = MAIN.resolve("inference/local");
    static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?(org\\.rpgengine\\.[\\w.]+);", Pattern.MULTILINE);
    static final Pattern CODEX_TYPE = Pattern.compile("\\b(Codex[A-Z]\\w*|StdioFrameWire|UnixSocketFrameWire|RpcGeneration"
            + "|TurnInbox|FrameWire)\\b");

    static List<Path> sources(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    @Test
    void noPackageOutsideTheAdapterReferencesIt() throws IOException {
        List<String> leaks = new ArrayList<>();
        for (Path source : sources(MAIN)) {
            if (source.startsWith(ADAPTER)) {
                continue;
            }
            String text = Files.readString(source);
            if (text.contains("org.rpgengine.inference.codex")) {
                leaks.add(source + ": imports the adapter package");
            }
            Matcher type = CODEX_TYPE.matcher(text);
            if (type.find()) {
                leaks.add(source + ": names " + type.group(1));
            }
        }
        assertThat(leaks).isEmpty();
    }

    @Test
    void adapterDependsOnlyOnTheNeutralPort() throws IOException {
        List<String> imports = new ArrayList<>();
        for (Path source : sources(ADAPTER)) {
            Matcher matcher = IMPORT.matcher(Files.readString(source));
            while (matcher.find()) {
                String imported = matcher.group(1);
                if (!imported.matches("org\\.rpgengine\\.inference\\.[A-Z]\\w*(\\.\\w+)*")) {
                    imports.add(source.getFileName() + " -> " + imported);
                }
            }
        }
        assertThat(imports).isEmpty();
    }

    @Test
    void adapterTypesArePackagePrivate() throws Exception {
        List<String> exposed = new ArrayList<>();
        for (Path source : sources(ADAPTER)) {
            String name = source.getFileName().toString().replace(".java", "");
            if (name.equals("package-info")) {
                continue;
            }
            Class<?> type = Class.forName("org.rpgengine.inference.codex." + name);
            if (Modifier.isPublic(type.getModifiers())) {
                exposed.add(name);
            }
        }
        assertThat(exposed).isEmpty();
    }

    @Test
    void theLocalAdapterKeepsTheSameBoundary() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Path source : sources(MAIN)) {
            if (!source.startsWith(LOCAL_ADAPTER) && Files.readString(source).contains("org.rpgengine.inference.local")) {
                violations.add(source + ": imports the adapter package");
            }
        }
        for (Path source : sources(LOCAL_ADAPTER)) {
            String name = source.getFileName().toString().replace(".java", "");
            Matcher matcher = IMPORT.matcher(Files.readString(source));
            while (matcher.find()) {
                if (!matcher.group(1).matches("org\\.rpgengine\\.inference\\.[A-Z]\\w*(\\.\\w+)*")) {
                    violations.add(name + " -> " + matcher.group(1));
                }
            }
            if (!name.equals("package-info")
                    && Modifier.isPublic(Class.forName("org.rpgengine.inference.local." + name).getModifiers())) {
                violations.add(name + " is public");
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void theImageAdapterKeepsTheSameBoundary() throws Exception {
        Path imageAdapter = MAIN.resolve("inference/image");
        List<String> violations = new ArrayList<>();
        for (Path source : sources(MAIN)) {
            if (!source.startsWith(imageAdapter) && Files.readString(source).contains("org.rpgengine.inference.image")) {
                violations.add(source + ": imports the adapter package");
            }
        }
        for (Path source : sources(imageAdapter)) {
            String name = source.getFileName().toString().replace(".java", "");
            Matcher matcher = IMPORT.matcher(Files.readString(source));
            while (matcher.find()) {
                if (!matcher.group(1).matches("org\\.rpgengine\\.inference\\.[A-Z]\\w*(\\.\\w+)*")) {
                    violations.add(name + " -> " + matcher.group(1));
                }
            }
            if (!name.equals("package-info")
                    && Modifier.isPublic(Class.forName("org.rpgengine.inference.image." + name).getModifiers())) {
                violations.add(name + " is public");
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void neutralApiUsesOnlyJdkAndNeutralTypes() {
        List<Class<?>> api = List.of(NarrationTaskPort.class, TaskHandle.class, TaskRequest.class, TaskResult.class,
                TaskResult.Provenance.class, RuntimeStatus.class, SlotAssignments.class, EffectiveSettings.class,
                InferenceSlot.class, SlotAssignment.class, ModelAllowlist.class, ProfileResolver.class,
                ProfileResolver.Resolved.class, ProfileResolver.Refused.class, ImageGenerationPort.class,
                ImageHandle.class, ImageRequest.class, ImageResult.class, ImageProvenance.class);
        List<String> foreign = new ArrayList<>();
        for (Class<?> type : api) {
            List<Class<?>> used = new ArrayList<>();
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers())) {
                    used.add(method.getReturnType());
                    used.addAll(List.of(method.getParameterTypes()));
                }
            }
            if (type.isRecord()) {
                for (RecordComponent component : type.getRecordComponents()) {
                    used.add(component.getType());
                }
            }
            for (Class<?> candidate : used) {
                Class<?> element = candidate.isArray() ? candidate.getComponentType() : candidate;
                String name = element.getName();
                if (!element.isPrimitive() && !name.startsWith("java.")
                        && !name.startsWith("org.rpgengine.inference.")) {
                    foreign.add(type.getSimpleName() + " uses " + name);
                }
                if (name.startsWith("org.rpgengine.inference.codex.")) {
                    foreign.add(type.getSimpleName() + " exposes " + name);
                }
            }
        }
        assertThat(foreign).isEmpty();
    }
}
