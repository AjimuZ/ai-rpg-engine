/**
 * Managed presentation assets (P17.1-A): the seven separate asset categories, a content-addressed registry of
 * validated media with per-campaign associations, stale-result protection for late image or audio jobs, and the
 * hash-addressed serving route. Nothing here runs a generation job or chooses art during play (P17.6), advances a
 * canonical world counter or depends on inference types; a job result reaches the registry only through {@link
 * org.rpgengine.assets.ManagedAssets#publish}.
 */
package org.rpgengine.assets;
