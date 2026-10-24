package org.rpgengine.preparation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Package-private helpers shared by the preparation services: transaction setup,
 * bounded database waits, argument checks mirroring V6 constraints and hashing.
 * Every check throws {@link IllegalArgumentException} or {@link IllegalStateException}
 * before any lock is taken; the database constraints remain the final authority.
 */
final class PreparationSupport {
    // Mirrors of the V6 CHECK patterns, so invalid values fail before a transaction opens.
    static final Pattern SCOPE = Pattern.compile("^[a-z][a-z0-9_.:-]{0,127}$");
    static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    static final Pattern PURPOSE = Pattern.compile("^[a-z][a-z0-9_-]{0,63}$");
    static final Pattern VERSION = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");
    // Leased job states; exactly these rows carry a holder, expiry and positive fence.
    static final String LEASED_STATES = "('LEASED', 'RUNNING', 'CANCEL_REQUESTED', 'RESULT_RECEIVED', 'VALIDATING')";
    static final String TERMINAL_STATES = "('COMPLETED', 'SUPERSEDED', 'CANCELLED', 'FAILED', 'EXPIRED')";

    /** Static helpers only. */
    private PreparationSupport() {
    }

    /** Build the READ COMMITTED, ten-second template used by every preparation transaction. */
    static TransactionTemplate template(PlatformTransactionManager manager) {
        var transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
        return transaction;
    }

    /**
     * Reject an ambient transaction: joining one could hold job or campaign locks across
     * later work, or report a claim/admission before the caller actually commits.
     */
    static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("preparation storage requires no ambient transaction");
        }
    }

    /** Require an existing transaction for atomic composition; never silently autocommit one statement. */
    static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("preparation composition requires a transaction");
        }
    }

    /** Bound lock waits and statement time inside the current transaction (same values as P03.5). */
    static void boundWaits(JdbcTemplate jdbc) {
        jdbc.execute("set local lock_timeout = '2s'");
        jdbc.execute("set local statement_timeout = '5s'");
    }

    /** Return value when it matches pattern; otherwise fail with the field name. */
    static String require(Pattern pattern, String value, String name) {
        Objects.requireNonNull(value, name);
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " is malformed");
        }
        return value;
    }

    /** Return a whole-second lease duration in 1..300, the same worker bound as mutation leases. */
    static long leaseSeconds(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.getNano() != 0 || duration.getSeconds() < 1 || duration.getSeconds() > 300) {
            throw new IllegalArgumentException("lease duration must be 1..300 whole seconds");
        }
        return duration.getSeconds();
    }

    /**
     * Light shape and byte-size check for a JSON array; the database parses and bounds
     * the stored rendering again.
     */
    static String requireJsonArray(String value, String name, int maxBytes) {
        Objects.requireNonNull(value, name);
        if (!value.strip().startsWith("[")) {
            throw new IllegalArgumentException(name + " must be a JSON array");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(name + " must not exceed " + maxBytes + " bytes");
        }
        return value;
    }

    /**
     * Lowercase SHA-256 over length-prefixed UTF-8 parts. The prefix makes the encoding
     * unambiguous, so ("ab", "c") and ("a", "bc") have distinct hash inputs.
     */
    static String sha256(Object... parts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (Object part : parts) {
                byte[] bytes = String.valueOf(Objects.requireNonNull(part, "part")).getBytes(StandardCharsets.UTF_8);
                digest.update((bytes.length + ":").getBytes(StandardCharsets.UTF_8));
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            // Every Java 21 runtime is required to provide SHA-256.
            throw new IllegalStateException(impossible);
        }
    }

    /** Read a campaign's current recovery generation; a missing campaign is an error. */
    static long generation(JdbcTemplate jdbc, UUID campaign) {
        var values = jdbc.queryForList("select recovery_generation from campaign where id = ?", Long.class, campaign);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("unknown campaign");
        }
        return values.getFirst();
    }
}
