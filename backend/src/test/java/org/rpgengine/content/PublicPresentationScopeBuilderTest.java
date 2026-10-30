package org.rpgengine.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.rpgengine.content.PublicPresentationScopeBuilder.VisibleActor;
import org.rpgengine.presentation.PublicPresentationScope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicPresentationScopeBuilderTest {
    private static final PackPresentation DECLARED = new PackPresentation(Set.of("dock"), Set.of("greeting"),
            Set.of("dock-bg", "hello-audio"), Map.of("dock", "dock-bg"), Map.of("greeting", "hello-audio"),
            Map.of("harbor-master", new PackPresentation.Looks(Set.of("bow", "stand"), Set.of("calm")),
                    "secret-spy", new PackPresentation.Looks(Set.of("lurk"), Set.of("smirk"))));

    @Test
    void visibleActorGetsExactlyItsDeclaredLooks() {
        UUID id = UUID.randomUUID();
        var scope = PublicPresentationScopeBuilder.build(List.of(new VisibleActor(id, "harbor-master")), DECLARED);

        assertEquals(Set.of(id), scope.actors().keySet());
        assertEquals(Set.of("bow", "stand"), scope.actors().get(id).poses());
        assertEquals(Set.of("calm"), scope.actors().get(id).expressions());
        assertEquals(Set.of("dock"), scope.scenes());
        assertEquals(Set.of("greeting"), scope.lines());
        assertEquals(Set.of("dock-bg", "hello-audio"), scope.assets());
    }

    @Test
    void hiddenActorIsAbsentWhenTheProjectionDoesNotExposeIt() {
        UUID visible = UUID.randomUUID();
        UUID hidden = UUID.randomUUID();
        // The spy is declared by the pack but the projection only exposes the harbor master.
        var scope = PublicPresentationScopeBuilder.build(List.of(new VisibleActor(visible, "harbor-master")),
                DECLARED);

        assertFalse(scope.actors().containsKey(hidden));
        assertEquals(1, scope.actors().size());
    }

    @Test
    void actorWithoutDefinitionOrDeclarationIsAbsent() {
        var noDefinition = new VisibleActor(UUID.randomUUID(), null);
        var undeclared = new VisibleActor(UUID.randomUUID(), "baker");

        var scope = PublicPresentationScopeBuilder.build(List.of(noDefinition, undeclared), DECLARED);

        assertTrue(scope.actors().isEmpty());
    }

    @Test
    void undeclaredReferencesNeverAppear() {
        UUID id = UUID.randomUUID();
        var scope = PublicPresentationScopeBuilder.build(List.of(new VisibleActor(id, "harbor-master")), DECLARED);

        assertFalse(scope.scenes().contains("tavern"));
        assertFalse(scope.lines().contains("farewell"));
        assertFalse(scope.assets().contains("tavern-bg"));
        assertFalse(scope.actors().get(id).poses().contains("lurk"));
        assertFalse(scope.actors().get(id).expressions().contains("smirk"));
    }

    @Test
    void noDeclarationGivesAnEmptyScope() {
        var scope = PublicPresentationScopeBuilder.build(List.of(new VisibleActor(UUID.randomUUID(), "harbor-master")),
                PackPresentation.NONE);

        assertEquals(new PublicPresentationScope(Set.of(), Map.of(), Set.of(), Set.of()), scope);
    }

    @Test
    void duplicateVisibleActorsCollapse() {
        UUID id = UUID.randomUUID();
        var scope = PublicPresentationScopeBuilder.build(
                List.of(new VisibleActor(id, "harbor-master"), new VisibleActor(id, "harbor-master")), DECLARED);

        assertEquals(1, scope.actors().size());
    }

    @Test
    void moreDeclaredVisibleActorsThanTheBoundAreRejectedNotTruncated() {
        var visible = new ArrayList<VisibleActor>();
        for (int i = 0; i <= PublicPresentationScope.MAX_ENTRIES; i++) {
            visible.add(new VisibleActor(UUID.randomUUID(), "harbor-master"));
        }

        assertThrows(IllegalArgumentException.class, () -> PublicPresentationScopeBuilder.build(visible, DECLARED));
        assertEquals(PublicPresentationScope.MAX_ENTRIES,
                PublicPresentationScopeBuilder.build(visible.subList(0, PublicPresentationScope.MAX_ENTRIES), DECLARED)
                        .actors().size());
    }
}
