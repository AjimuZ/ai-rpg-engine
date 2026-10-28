package org.rpgengine.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Enforces the P08.7 baseline and disclosure boundaries structurally (docs/28 sections 4 and 8,
 * AP-07 and AP-09): no other package depends on optional planning, so a player action can never
 * wait for or require it; plan storage is read only by the preparation and planning packages; and
 * public narration and presentation code never reads preparation records at all, so privileged
 * plan text cannot reach a public context through them. Textual checks over this codebase's
 * direct import and SQL style.
 */
class PlanningBoundaryTest {
    static final Path MAIN = Path.of("src/main/java/org/rpgengine");
    // Every table holding plan bodies, private snapshots or their dependency records.
    static final List<String> PLAN_TABLES = List.of("quest_plan_artifact", "planning_snapshot", "plan_dependency",
            "plan_admission");

    static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }

    static boolean within(Path source, String... packages) {
        for (String name : packages) {
            if (source.startsWith(MAIN.resolve(name))) {
                return true;
            }
        }
        return false;
    }

    @Test
    void noOtherPackageDependsOnOptionalPlanning() throws IOException {
        var leaks = new ArrayList<String>();
        for (Path source : sources()) {
            if (!within(source, "planning") && Files.readString(source).contains("org.rpgengine.planning")) {
                leaks.add(source.toString());
            }
        }
        assertThat(leaks).isEmpty();
    }

    @Test
    void onlyPreparationAndPlanningReadPlanStorage() throws IOException {
        var leaks = new ArrayList<String>();
        for (Path source : sources()) {
            if (within(source, "preparation", "planning")) {
                continue;
            }
            String text = Files.readString(source);
            for (String table : PLAN_TABLES) {
                if (text.contains(table)) {
                    leaks.add(source + " -> " + table);
                }
            }
        }
        assertThat(leaks).isEmpty();
    }

    @Test
    void publicNarrationAndPresentationNeverReadPreparation() throws IOException {
        var leaks = new ArrayList<String>();
        for (Path source : sources()) {
            if (within(source, "narration", "presentation")
                    && Files.readString(source).contains("org.rpgengine.preparation")) {
                leaks.add(source.toString());
            }
        }
        assertThat(leaks).isEmpty();
        // The scan must actually see the packages it guards.
        assertThat(sources()).anyMatch(source -> within(source, "narration"))
                .anyMatch(source -> within(source, "presentation")).anyMatch(source -> within(source, "planning"));
    }
}
