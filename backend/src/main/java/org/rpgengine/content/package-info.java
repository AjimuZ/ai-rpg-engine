/**
 * Adventure-owned immutable content revisions. Bounded filesystem/archive staging,
 * structural validation and semantic compilation turn untrusted pack bytes into sealed
 * database records; invalid inputs fail before admission. Optional private filesystem
 * projections copy verified originals after commit or at startup. Projection failure
 * preserves canonical records and fails closed until the private storage is repaired.
 *
 * <p>Since P09.5 {@link org.rpgengine.content.PackRegistry#diagnose} and
 * {@link org.rpgengine.content.CampaignDependencies} report whether this engine build can still
 * load a campaign's pinned closure ({@link org.rpgengine.content.ClosureDiagnosis}); a missing,
 * corrupt or unusable closure pauses the campaign's mutation and is never replaced by another pack.
 */
package org.rpgengine.content;
