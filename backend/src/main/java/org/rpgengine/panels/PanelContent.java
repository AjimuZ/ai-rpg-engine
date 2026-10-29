package org.rpgengine.panels;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.rpgengine.characters.CharacterRuleCatalog;
import org.rpgengine.content.CampaignPackSnapshot;
import org.rpgengine.content.CompiledPack;
import org.rpgengine.content.Definition;
import org.rpgengine.content.PackLocalization;
import org.rpgengine.presentation.SupportedLocale;
import org.rpgengine.quests.QuestRules;

/**
 * Detached view of one pinned pack closure for panels (P10.5): the quest rules and character rules
 * that decide what a panel may show, and the root pack's localized text. It is built once per sealed
 * revision by {@link PanelContents} and holds no canonical state.
 *
 * <p>Text is resolved only for root-pack definitions (dependency references are qualified with
 * {@code pack#} and have no overlay here) and only when the source text is public; a secret text, a
 * missing field and a qualified reference all read as absent, so the caller shows the stable
 * identifier instead of guessing.
 *
 * @param quests prose-free quest projection, including which quests are hidden background content
 * @param characters rarity, equipment and ability facts of items, skills and conditions
 * @param localization compiled text of the root pack
 * @param ruleOwners definition that owns a nested name: {@code attributes/might} maps to its rule ID
 */
record PanelContent(QuestRules quests, CharacterRuleCatalog characters, PackLocalization localization,
        Map<String, String> ruleOwners) {
    /** Nested groups of a rule definition whose entries carry a localized name. */
    private static final java.util.List<String> GROUPS = java.util.List.of("attributes", "resources", "tiers");

    /** Freeze the owner index and require every part. */
    PanelContent {
        Objects.requireNonNull(quests, "quests");
        Objects.requireNonNull(characters, "characters");
        Objects.requireNonNull(localization, "localization");
        ruleOwners = Map.copyOf(ruleOwners);
    }

    /**
     * Derive the panel view of a sealed, recompiled closure.
     *
     * @param snapshot privileged compiled closure; it is not retained
     * @return the content
     * @throws IllegalArgumentException when the closure breaks a shape the compiler guarantees
     */
    static PanelContent from(CampaignPackSnapshot snapshot) {
        CompiledPack root = snapshot.root();
        var owners = new LinkedHashMap<String, String>();
        for (Definition definition : root.definitions()) {
            if (!definition.kind().equals("rule")) {
                continue;
            }
            for (String group : GROUPS) {
                if (definition.data().get(group) instanceof java.util.List<?> children) {
                    for (Object child : children) {
                        if (child instanceof Map<?, ?> entry && entry.get("id") instanceof String id) {
                            owners.putIfAbsent(group + "/" + id, definition.id());
                        }
                    }
                }
            }
        }
        return new PanelContent(QuestRules.from(snapshot), CharacterRuleCatalog.from(snapshot), root.localization(), owners);
    }

    /**
     * Resolve the public name of a root-pack definition.
     *
     * @return the name, or empty for a qualified, unknown, secret or unnamed definition
     */
    Optional<PlayPanels.Text> name(SupportedLocale locale, String definition) {
        return text(locale, definition, "/name");
    }

    /**
     * Resolve the public name of an attribute, resource or rarity tier declared inside a rule.
     *
     * @param group {@code attributes}, {@code resources} or {@code tiers}
     * @param id the entry ID
     * @return the name, or empty when the entry has no public name
     */
    Optional<PlayPanels.Text> nested(SupportedLocale locale, String group, String id) {
        String owner = ruleOwners.get(group + "/" + id);
        return owner == null ? Optional.empty() : text(locale, owner, "/" + group + "/" + id + "/name");
    }

    /**
     * Resolve any public text field of a root-pack definition.
     *
     * @param locale requested language
     * @param definition root-pack definition ID
     * @param field text field pointer such as {@code /summary} or {@code /objectives/reach/description}
     * @return the text; empty when the reference is qualified, the field is missing, blank or not public
     */
    Optional<PlayPanels.Text> text(SupportedLocale locale, String definition, String field) {
        if (definition == null || definition.contains("#")) {
            return Optional.empty();
        }
        try {
            var resolved = localization.resolve(definition, field, locale.tag());
            String text = resolved.text().strip();
            if (!"public".equals(resolved.visibility()) || text.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new PlayPanels.Text(text, resolved.locale(), resolved.fallback()));
        } catch (IllegalArgumentException missing) {
            // A field the pack does not declare simply has no text.
            return Optional.empty();
        }
    }
}
