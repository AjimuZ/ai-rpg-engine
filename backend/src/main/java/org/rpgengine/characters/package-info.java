/**
 * Actor instances owned by one campaign, their bounded numeric resources and, since P10.1, the
 * rule-checked character state around them: the effective sheet, learned abilities, active
 * conditions and equipment. {@code CharacterRuleCatalog} projects the pinned pack rules and
 * {@code CharacterService} applies them inside the caller's transaction. The database keeps every
 * resource inside its declared bounds and every row on an actor of the same campaign.
 */
package org.rpgengine.characters;
