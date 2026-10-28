package org.rpgengine.inference.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The role/audience matrix and the scope key: player-facing roles never run privileged, planning
 * roles never run public, character audiences name their character, and every scope component
 * distinguishes sessions.
 */
class SessionScopeTest {
    static final UUID ADVENTURE = UUID.fromString("00000000-0000-4000-8000-000000000002");
    static final UUID CAMPAIGN = UUID.fromString("00000000-0000-4000-8000-000000000003");
    static final String PACK = "a".repeat(64);
    static final String POLICY = "b".repeat(64);

    static SessionScope scope(AiRole role, Audience audience) {
        return new SessionScope(ADVENTURE, CAMPAIGN, 1, role, audience, PACK, POLICY);
    }

    @Test
    void noPlayerFacingRolePermitsThePrivilegedAudience() {
        List<AiRole> playerFacing = Arrays.stream(AiRole.values()).filter(AiRole::playerFacing).toList();
        assertThat(playerFacing).containsExactlyInAnyOrder(AiRole.INTERPRETER, AiRole.NARRATOR, AiRole.GUIDE);
        for (AiRole role : AiRole.values()) {
            if (role.playerFacing()) {
                assertThat(role.permits(Audience.PRIVILEGED)).as(role.wire()).isFalse();
                assertThatThrownBy(() -> scope(role, Audience.PRIVILEGED)).as(role.wire())
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Test
    void theMatrixKeepsPlanningPrivilegedAndNarrationPublic() {
        assertThat(scope(AiRole.NARRATOR, Audience.PUBLIC).audience()).isEqualTo(Audience.PUBLIC);
        assertThat(scope(AiRole.INTERPRETER, Audience.PUBLIC).role()).isEqualTo(AiRole.INTERPRETER);
        for (AiRole planning : List.of(AiRole.QUEST_PLANNER, AiRole.WORLD_DIRECTOR, AiRole.PLAN_REVIEWER,
                AiRole.CONTENT_PROPOSER, AiRole.ENCOUNTER_PROPOSER)) {
            assertThat(scope(planning, Audience.PRIVILEGED).role()).isEqualTo(planning);
            assertThatThrownBy(() -> scope(planning, Audience.PUBLIC)).as(planning.wire())
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> scope(planning, Audience.character("npc.mara"))).as(planning.wire())
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> scope(AiRole.NARRATOR, Audience.character("npc.mara")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scope(AiRole.TACTICIAN, Audience.PUBLIC)).isInstanceOf(IllegalArgumentException.class);
        assertThat(scope(AiRole.TACTICIAN, Audience.character("npc.mara")).audience().wire())
                .isEqualTo("character:npc.mara");
        assertThat(scope(AiRole.GUIDE, Audience.character("guide.lumen")).role().playerFacing()).isTrue();
        // A summary keeps the audience of its inputs, whichever it is.
        for (Audience audience : List.of(Audience.PUBLIC, Audience.character("npc.mara"), Audience.PRIVILEGED)) {
            assertThat(scope(AiRole.SUMMARIZER, audience).audience()).isEqualTo(audience);
        }
    }

    @Test
    void audiencesValidateTheirSubjectAndRenderStableWireNames() {
        assertThat(Audience.PUBLIC.wire()).isEqualTo("public");
        assertThat(Audience.PRIVILEGED.wire()).isEqualTo("privileged");
        assertThat(Audience.character("npc.mara")).isEqualTo(Audience.character("npc.mara"))
                .isNotEqualTo(Audience.character("npc.tullo"));
        assertThatThrownBy(() -> Audience.character(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Audience.character("npc mara")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Audience(Audience.Kind.PUBLIC, "npc.mara"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Audience(Audience.Kind.PRIVILEGED, "npc.mara"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Arrays.stream(AiRole.values()).map(AiRole::wire)).doesNotHaveDuplicates()
                .allMatch(name -> name.matches("[a-z][a-z-]*"));
    }

    @Test
    void everyComponentDistinguishesScopesAndBadValuesAreRejected() {
        SessionScope base = scope(AiRole.NARRATOR, Audience.PUBLIC);
        UUID other = UUID.fromString("00000000-0000-4000-8000-000000000009");
        List<SessionScope> variants = List.of(
                new SessionScope(other, CAMPAIGN, 1, AiRole.NARRATOR, Audience.PUBLIC, PACK, POLICY),
                new SessionScope(ADVENTURE, other, 1, AiRole.NARRATOR, Audience.PUBLIC, PACK, POLICY),
                new SessionScope(ADVENTURE, CAMPAIGN, 2, AiRole.NARRATOR, Audience.PUBLIC, PACK, POLICY),
                new SessionScope(ADVENTURE, CAMPAIGN, 1, AiRole.INTERPRETER, Audience.PUBLIC, PACK, POLICY),
                new SessionScope(ADVENTURE, CAMPAIGN, 1, AiRole.NARRATOR, Audience.PUBLIC, "c".repeat(64), POLICY),
                new SessionScope(ADVENTURE, CAMPAIGN, 1, AiRole.NARRATOR, Audience.PUBLIC, PACK, "c".repeat(64)));
        assertThat(variants).allSatisfy(variant -> assertThat(variant).isNotEqualTo(base));
        assertThat(scope(AiRole.NARRATOR, Audience.PUBLIC)).isEqualTo(base).hasSameHashCodeAs(base);

        assertThatThrownBy(() -> new SessionScope(ADVENTURE, CAMPAIGN, -1, AiRole.NARRATOR, Audience.PUBLIC, PACK,
                POLICY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionScope(ADVENTURE, CAMPAIGN, 1, AiRole.NARRATOR, Audience.PUBLIC, "A".repeat(64),
                POLICY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionScope(ADVENTURE, CAMPAIGN, 1, AiRole.NARRATOR, Audience.PUBLIC, PACK, "b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionScope(ADVENTURE, null, 1, AiRole.NARRATOR, Audience.PUBLIC, PACK, POLICY))
                .isInstanceOf(NullPointerException.class);
    }
}
