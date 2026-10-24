/**
 * Shared persistence support for canonical entities: engine-assigned identities,
 * explicit optimistic-locking row versions for mutable rows, and argument checks that
 * mirror the Flyway schema constraints. It contains no gameplay rules and is never
 * serialized to players.
 */
package org.rpgengine.persistence;
