package org.rpgengine.inference.admission;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The probe's campaign lookup against the real {@code campaign} table on PostgreSQL. The test inserts an owner, an
 * adventure, a content revision and two campaigns inside a transaction it rolls back, so it shows that the adventure,
 * campaign and generation columns land in the right fields of the reference and that the lowest campaign identifier is
 * the one chosen, without leaving rows behind.
 */
@SpringBootTest
class JdbcProbeCampaignsTest {
    private static final UUID LOWEST_CAMPAIGN = new UUID(0L, 0L);
    private static final UUID HIGHEST_CAMPAIGN = new UUID(-1L, -1L);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;

    /** Construct the injected fixture; missing PostgreSQL fails application startup. */
    JdbcProbeCampaignsTest() {
    }

    @Test
    void theLookupMapsAdventureCampaignAndGenerationFromTheLowestCampaignRow() {
        UUID owner = UUID.randomUUID();
        UUID adventure = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            status.setRollbackOnly();
            jdbc.update("insert into identity_owner_profile (id, display_name, ui_locale, created_at, updated_at) "
                    + "values (?, 'Probe owner', 'en', now(), now())", owner);
            jdbc.update("insert into adventure_workspace (id, owner_id, title, genre, source_locale, created_at, "
                    + "updated_at) values (?, ?, 'Probe', 'neutral', 'en', now(), now())", adventure, owner);
            jdbc.update("insert into content_revision (id, adventure_id, pack_id, pack_version, content_hash, "
                    + "schema_version, ruleset_id, ruleset_version, ruleset_hash, source_locale, admitted_at) "
                    + "values (?, ?, 'probe', '1.0.0', ?, 1, 'probe', '1.0.0', ?, 'en', now())", revision, adventure,
                    "a".repeat(64), "b".repeat(64));
            insertCampaign(HIGHEST_CAMPAIGN, adventure, revision, 7L);
            insertCampaign(LOWEST_CAMPAIGN, adventure, revision, 3L);

            DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
            factory.registerSingleton("jdbc", jdbc);
            Optional<AdmissionProbeController.CampaignRef> found = new JdbcProbeCampaigns(
                    factory.getBeanProvider(JdbcTemplate.class)).first();

            assertThat(found).isPresent();
            assertThat(found.get().adventureId()).isEqualTo(adventure);
            assertThat(found.get().campaignId()).isEqualTo(LOWEST_CAMPAIGN);
            assertThat(found.get().generation()).isEqualTo(3L);
        });
    }

    private void insertCampaign(UUID campaign, UUID adventure, UUID revision, long generation) {
        jdbc.update("insert into campaign (id, adventure_id, content_revision_id, title, seed_identity, mode, "
                + "recovery_generation, created_at, updated_at) values (?, ?, ?, 'Probe', 'probe-seed', 'EXPLORATION', "
                + "?, now(), now())", campaign, adventure, revision, generation);
    }
}
