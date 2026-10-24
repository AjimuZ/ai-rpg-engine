package org.rpgengine.campaign;

/**
 * Top-level scheduling mode of a campaign. The same mechanics apply in every mode;
 * only scheduling and timing differ (docs/10 section 1). Further modes are added by a
 * forward migration together with this enum, never by free-form text.
 */
public enum CampaignMode {
    /** Free exploration and action resolution. */
    EXPLORATION,
    /** An active encounter schedules activations and rounds (P10/P11). */
    ENCOUNTER
}
