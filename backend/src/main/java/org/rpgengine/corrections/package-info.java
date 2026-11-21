/**
 * Owner canon corrections (P16.3-A): previewed, backed-up and audited corrections of campaign canon that never
 * rewrite history.
 *
 * <p>{@link org.rpgengine.corrections.CanonCorrections} corrects one knowledge claim as a committed operation with an
 * internal audit event, a superseding claim, carried beliefs, query-epoch bumps, plan revalidation and a summary
 * invalidation. Its preview runs the same transaction and rolls it back, so the reported impact is exact; applying
 * requires that preview's digest and stores it with a full-owner backup taken at the corrected revision. Content
 * revision migration previews are not part of this package yet.
 */
package org.rpgengine.corrections;
