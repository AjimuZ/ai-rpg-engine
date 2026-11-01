package org.rpgengine.inference.admission;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Conditional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Finds the campaign that scopes the {@link AdmissionProbeController}'s text task, behind the same switch. It reads
 * one row and writes nothing; the choice is the lowest campaign identifier, which carries no meaning beyond being
 * deterministic while the set of campaigns does not change.
 *
 * <p>The database access is resolved at the first call, not at construction, so that a context without a data source
 * still starts; the probe then ends its text request {@code submit-failed}.
 */
@Component
@Conditional(AdmissionConfiguration.Enabled.class)
final class JdbcProbeCampaigns implements AdmissionProbeController.Campaigns {
    private final ObjectProvider<JdbcTemplate> jdbc;

    /** @param jdbc the application's database access, looked up when a campaign is asked for */
    JdbcProbeCampaigns(ObjectProvider<JdbcTemplate> jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** {@inheritDoc} A database failure propagates to the caller, which reports a fixed code. */
    @Override
    public Optional<AdmissionProbeController.CampaignRef> first() {
        // CampaignRef is (adventureId, campaignId, generation), so the adventure column comes first.
        return jdbc.getObject().query("select adventure_id, id, recovery_generation from campaign order by id limit 1",
                rows -> rows.next()
                        ? Optional.of(new AdmissionProbeController.CampaignRef(rows.getObject(1, UUID.class),
                                rows.getObject(2, UUID.class), rows.getLong(3)))
                        : Optional.<AdmissionProbeController.CampaignRef>empty());
    }
}
