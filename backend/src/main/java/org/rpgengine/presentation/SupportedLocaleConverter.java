package org.rpgengine.presentation;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Stores {@link SupportedLocale} as its lowercase tag ({@code en}, {@code it}) to match
 * the database CHECK constraints, instead of the enum constant name.
 * Reading an unknown stored tag fails loudly rather than defaulting silently.
 */
@Converter
public class SupportedLocaleConverter implements AttributeConverter<SupportedLocale, String> {
    /** Instantiated by the persistence provider. */
    public SupportedLocaleConverter() {
    }

    /** @return the stored tag, or null for a null attribute */
    @Override
    public String convertToDatabaseColumn(SupportedLocale attribute) {
        return attribute == null ? null : attribute.tag();
    }

    /**
     * @return the locale for a stored tag, or null for SQL NULL
     * @throws IllegalArgumentException if the stored tag is not on the allowlist
     */
    @Override
    public SupportedLocale convertToEntityAttribute(String dbData) {
        return dbData == null ? null : SupportedLocale.fromTag(dbData);
    }
}
