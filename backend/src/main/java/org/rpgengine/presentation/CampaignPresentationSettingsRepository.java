package org.rpgengine.presentation;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence port for {@link CampaignPresentationSettings}, keyed by campaign ID. */
public interface CampaignPresentationSettingsRepository
        extends JpaRepository<CampaignPresentationSettings, UUID> {
}
