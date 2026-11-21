package org.rpgengine.assets;

import java.util.Set;

/**
 * The seven separate presentation asset categories of docs/23 section 3, each with the media it may hold and the
 * rule that decides which of two competing results is stale.
 *
 * <p>Always holds: categories never share a subject namespace, so a scene result can neither replace a map nor a
 * portrait, and every category accepts only media of one kind (images or audio). The wire form is the lower-case
 * name and is also the value stored in {@code asset_association.category}.
 */
public enum AssetCategory {
    /** Backdrop of a scene; the newest accepted request wins. */
    SCENE("scene", Kind.IMAGE, true),
    /** Map of a place; the newest accepted request wins. */
    MAP("map", Kind.IMAGE, true),
    /** Portrait of one character, reused across dialogue, shop, party and journal. */
    PORTRAIT("portrait", Kind.IMAGE, false),
    /** Identifying artwork of one item definition. */
    ITEM("item", Kind.IMAGE, false),
    /** Theme pieces such as frames, icons and rarity badges, stored once and not per entity. */
    UI("ui", Kind.IMAGE, false),
    /** A music loop. */
    MUSIC("music", Kind.AUDIO, false),
    /** A sound effect or spoken line. */
    EFFECT("effect", Kind.AUDIO, false);

    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
    private static final Set<String> AUDIO_TYPES = Set.of("audio/ogg", "audio/mpeg", "audio/wav");

    private final String wire;
    private final Kind kind;
    private final boolean orderedBySource;

    AssetCategory(String wire, Kind kind, boolean orderedBySource) {
        this.wire = wire;
        this.kind = kind;
        this.orderedBySource = orderedBySource;
    }

    /** The media family a category holds. */
    enum Kind {
        /** PNG, JPEG or WebP. */
        IMAGE,
        /** Ogg, MP3 or WAV. */
        AUDIO
    }

    /** @return the lower-case wire and storage name */
    public String wire() {
        return wire;
    }

    /**
     * Whether a result requested at an earlier campaign revision than the accepted one is stale even when its
     * appearance revision is higher. True for scenes and maps, which show "the current place", so a delayed
     * result cannot replace what the player now sees.
     *
     * @return true when the request's source revision also orders results
     */
    boolean orderedBySource() {
        return orderedBySource;
    }

    /**
     * Check a detected media type against this category.
     *
     * @param mediaType canonical media type from {@code PackMedia}
     * @return true when the category holds that kind of media
     */
    public boolean accepts(String mediaType) {
        return (kind == Kind.IMAGE ? IMAGE_TYPES : AUDIO_TYPES).contains(mediaType);
    }

    /**
     * Resolve a stored or requested category name.
     *
     * @param wire the exact lower-case name
     * @return the category
     * @throws IllegalArgumentException for any other text; the message never repeats it
     */
    public static AssetCategory fromWire(String wire) {
        for (AssetCategory category : values()) {
            if (category.wire.equals(wire)) {
                return category;
            }
        }
        throw new IllegalArgumentException("unknown asset category");
    }
}
