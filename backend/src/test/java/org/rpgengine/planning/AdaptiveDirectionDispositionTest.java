package org.rpgengine.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.rpgengine.inference.contract.TaskKind;
import org.rpgengine.inference.scheduling.WorkClass;
import org.rpgengine.inference.session.AiRole;

/**
 * P15.6-A's disposition of adaptive quest and world direction, checked structurally (docs/28 section 12): the capability
 * is disabled, not implemented, and the deterministic world rules are its fallback.
 *
 * <ul>
 *   <li>No task kind runs in the world-director or plan-reviewer role, and none is speculative work; quest planning keeps
 *       exactly its two P08.7 kinds, off by default, as background preparation.</li>
 *   <li>No main source other than {@code AiRole} names those two roles, and no main source or resource names their task
 *       kinds, so no code path can schedule a world-development proposal or a plan review.</li>
 *   <li>The world package (NPC schedules and witness reports, relationship causes, faction clock catch-up) names no
 *       inference, planning, preparation, narration or memory package, so the canonical commit that runs it never calls
 *       or waits for a director.</li>
 * </ul>
 *
 * Textual checks over this codebase's direct import and literal style; they see no reflection or names assembled from
 * parts. The database world suites check the same at run time through {@code DirectionFallback}.
 */
class AdaptiveDirectionDispositionTest {
    static final Path MAIN = Path.of("src/main/java/org/rpgengine");
    static final Path RESOURCES = Path.of("src/main/resources");
    static final Path ROLES = MAIN.resolve("inference/session/AiRole.java");
    // The disabled roles as Java constants and as wire names; only AiRole may declare them.
    static final List<String> ROLE_NAMES = List.of("WORLD_DIRECTOR", "PLAN_REVIEWER", "\"world-director\"",
            "\"plan-reviewer\"");
    // The docs/21 task kinds of the disabled roles; nothing in main code or resources may name them.
    static final List<String> KIND_NAMES = List.of("world-development-proposal", "\"plan-review\"",
            "plan-review.schema");

    static List<Path> files(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).sorted().toList();
        }
    }

    // Byte-preserving read, so a binary resource never fails the scan.
    static String text(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
    }

    @Test
    void noTaskKindDirectsTheWorldOrReviewsAPlan() {
        for (TaskKind kind : TaskKind.values()) {
            assertThat(kind.role()).as(kind.wire()).isNotIn(AiRole.WORLD_DIRECTOR, AiRole.PLAN_REVIEWER);
            assertThat(kind.workClass()).as(kind.wire()).isNotEqualTo(WorkClass.SPECULATIVE);
        }
        assertThat(Arrays.stream(TaskKind.values()).filter(kind -> kind.role() == AiRole.QUEST_PLANNER).toList())
                .containsExactly(TaskKind.QUEST_PLAN, TaskKind.QUEST_PLAN_REFINE)
                .allMatch(kind -> kind.workClass() == WorkClass.PREPARATION);
        assertThat(PlanningSettings.defaults().enabled()).isFalse();
    }

    @Test
    void noSourceOrResourceNamesTheDisabledTasks() throws IOException {
        var leaks = new ArrayList<String>();
        for (Path source : files(MAIN)) {
            String text = text(source);
            for (String name : ROLE_NAMES) {
                if (!source.equals(ROLES) && text.contains(name)) {
                    leaks.add(source + " -> " + name);
                }
            }
            for (String name : KIND_NAMES) {
                if (text.contains(name)) {
                    leaks.add(source + " -> " + name);
                }
            }
        }
        for (Path resource : files(RESOURCES)) {
            String text = text(resource);
            for (String name : Stream.concat(ROLE_NAMES.stream(), KIND_NAMES.stream()).toList()) {
                if (text.contains(name)) {
                    leaks.add(resource + " -> " + name);
                }
            }
        }
        assertThat(leaks).isEmpty();
        // The scan must see what it guards: the roles' declaration and the implemented contract schemas.
        assertThat(text(ROLES)).contains(ROLE_NAMES.toArray(String[]::new));
        assertThat(files(RESOURCES.resolve("ai-contract/1")).stream().map(path -> path.getFileName().toString()))
                .contains("quest-plan.schema.json", "narration.schema.json")
                .noneMatch(name -> name.startsWith("world-") || name.startsWith("plan-review"));
    }

    @Test
    void theDeterministicFallbackNamesNoInference() throws IOException {
        var world = files(MAIN.resolve("world"));
        assertThat(world.stream().map(path -> path.getFileName().toString()))
                .contains("NpcWorld.java", "Relationships.java", "FactionClocks.java");
        var leaks = new ArrayList<String>();
        for (Path source : world) {
            String text = text(source);
            for (String name : List.of("inference", "planning", "preparation", "narration", "memory")) {
                if (text.contains("org.rpgengine." + name)) {
                    leaks.add(source + " -> " + name);
                }
            }
        }
        assertThat(leaks).isEmpty();
    }
}
