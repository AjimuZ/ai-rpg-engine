package org.rpgengine.content;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Semantic version values and the small, closed range grammar packs use for engine and ruleset
 * compatibility (docs/09 section 4).
 *
 * <p>Grammar: a range is one to four whitespace-separated comparators, every one of which must hold
 * (logical AND). A comparator is an optional operator ({@code >= <= > < =}; absent means {@code =})
 * followed by a full {@code MAJOR.MINOR.PATCH[-prerelease]} version. Caret, tilde, wildcard,
 * hyphen and {@code ||} forms are deliberately unsupported so compatibility stays obvious to
 * authors and reviewers. Pre-releases compare with plain SemVer precedence (a range such as
 * {@code >=1.0.0 <2.0.0} therefore admits {@code 2.0.0-rc.1}); this simplification is recorded in
 * ADR 0017. Build metadata is ignored for precedence, as SemVer requires.
 *
 * <p>Failure behavior: malformed input throws {@link IllegalArgumentException}; callers convert it to
 * a located {@code VERSION_SYNTAX} PackException. Nothing here performs I/O.
 */
final class Versions {
    /** Full SemVer 2.0.0 grammar with capture groups for the core, pre-release and build parts. */
    private static final Pattern SEMVER = Pattern.compile(
            "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
                    + "(?:-((?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)(?:\\.(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*))*))?"
                    + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");
    private static final Pattern COMPARATOR = Pattern.compile("(>=|<=|>|<|=)?(.+)");
    private static final int MAX_COMPARATORS = 4;

    private Versions() {
    }

    /**
     * One parsed semantic version. Numeric parts use BigInteger so absurdly long but grammatical
     * numbers compare exactly instead of overflowing.
     */
    record Version(BigInteger major, BigInteger minor, BigInteger patch, List<String> prerelease)
            implements Comparable<Version> {
        /** Order by SemVer 2.0.0 precedence: core numbers, then "no pre-release" above any pre-release. */
        @Override
        public int compareTo(Version other) {
            int core = major.compareTo(other.major);
            core = core != 0 ? core : minor.compareTo(other.minor);
            core = core != 0 ? core : patch.compareTo(other.patch);
            if (core != 0) {
                return core;
            }
            if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
                // A release outranks its own pre-releases; two releases are equal.
                return Boolean.compare(prerelease.isEmpty(), other.prerelease.isEmpty());
            }
            for (int index = 0; index < Math.min(prerelease.size(), other.prerelease.size()); index++) {
                int part = comparePart(prerelease.get(index), other.prerelease.get(index));
                if (part != 0) {
                    return part;
                }
            }
            return Integer.compare(prerelease.size(), other.prerelease.size());
        }

        /** Numeric identifiers compare numerically and rank below alphanumeric ones, which compare in ASCII order. */
        private static int comparePart(String left, String right) {
            boolean leftNumeric = left.chars().allMatch(Character::isDigit);
            boolean rightNumeric = right.chars().allMatch(Character::isDigit);
            if (leftNumeric && rightNumeric) {
                return new BigInteger(left).compareTo(new BigInteger(right));
            }
            if (leftNumeric != rightNumeric) {
                return leftNumeric ? -1 : 1;
            }
            return left.compareTo(right);
        }

        /** Render the version without build metadata, for diagnostics. */
        @Override
        public String toString() {
            return major + "." + minor + "." + patch + (prerelease.isEmpty() ? "" : "-" + String.join(".", prerelease));
        }
    }

    /** One comparator of a range; {@code operator} is one of {@code >= <= > < =}. */
    record Comparator(String operator, Version version) {
        /** True when {@code candidate} satisfies this comparator. */
        boolean test(Version candidate) {
            int order = candidate.compareTo(version);
            return switch (operator) {
                case ">=" -> order >= 0;
                case "<=" -> order <= 0;
                case ">" -> order > 0;
                case "<" -> order < 0;
                default -> order == 0;
            };
        }
    }

    /** Conjunction of comparators; a version matches only when every comparator holds. */
    record Range(List<Comparator> comparators) {
        /** True when {@code candidate} satisfies all comparators. */
        boolean contains(Version candidate) {
            return comparators.stream().allMatch(comparator -> comparator.test(candidate));
        }
    }

    /**
     * Parse an exact semantic version.
     *
     * @throws IllegalArgumentException when the text is not a complete SemVer 2.0.0 version
     */
    static Version version(String text) {
        Matcher matcher = SEMVER.matcher(text == null ? "" : text);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Expected an exact semantic version such as 1.2.3, found '" + text + "'");
        }
        List<String> prerelease = matcher.group(4) == null ? List.of() : List.of(matcher.group(4).split("\\."));
        return new Version(new BigInteger(matcher.group(1)), new BigInteger(matcher.group(2)),
                new BigInteger(matcher.group(3)), prerelease);
    }

    /**
     * Parse a range of 1..4 whitespace-separated comparators.
     *
     * @throws IllegalArgumentException for an empty range, too many comparators or any malformed comparator
     */
    static Range range(String text) {
        String[] parts = text == null ? new String[0] : text.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty() || parts.length > MAX_COMPARATORS) {
            throw new IllegalArgumentException("Expected 1.." + MAX_COMPARATORS
                    + " comparators such as '>=1.0.0 <2.0.0', found '" + text + "'");
        }
        List<Comparator> comparators = new ArrayList<>();
        for (String part : parts) {
            Matcher matcher = COMPARATOR.matcher(part);
            // The pattern always matches non-empty text; the version check below rejects the rest.
            matcher.matches();
            String operator = matcher.group(1) == null ? "=" : matcher.group(1);
            comparators.add(new Comparator(operator, version(matcher.group(2))));
        }
        return new Range(List.copyOf(comparators));
    }
}
