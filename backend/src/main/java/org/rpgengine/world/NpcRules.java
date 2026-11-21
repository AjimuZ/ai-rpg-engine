package org.rpgengine.world;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import org.rpgengine.rules.WorldClock;

/**
 * Prose-free world rules of one NPC definition (P15.1-A), keyed by its canonical definition ID in the
 * pinned rule catalog. Every campaign actor created from that definition follows them.
 *
 * @param schedule daily routine that decides the actor's location, or null when the actor stays where it is
 * @param witness true when the actor remembers where it saw the acting player (an OBSERVED sighting)
 * @param reports routes through which a witness's own sightings reach other NPCs; empty unless {@code witness}
 */
public record NpcRules(NpcSchedule schedule, boolean witness, List<Report> reports) {
    /** Most routes one witness declares; equal to the pack compiler's bound. */
    public static final int MAX_REPORTS = 8;
    /** Rules of an NPC that has no schedule, witnesses nothing and reports to nobody. */
    public static final NpcRules NONE = new NpcRules(null, false, List.of());

    /**
     * One declared message route.
     *
     * @param recipient canonical definition ID of the NPC that is told
     * @param minutes in-world minutes between a sighting and its arrival, 0..{@link WorldClock#MAX_MINUTES}
     */
    public record Report(String recipient, int minutes) {
        /** Require a recipient and a delay the world clock can express. */
        public Report {
            Objects.requireNonNull(recipient, "recipient");
            if (minutes < 0 || minutes > WorldClock.MAX_MINUTES) {
                throw new IllegalArgumentException("report delay outside 0.." + WorldClock.MAX_MINUTES + " minutes");
            }
        }

        /** @return the delay in seconds of world time */
        public long delaySeconds() {
            return WorldClock.seconds(minutes);
        }
    }

    /** Freeze the routes; a route from a non-witness or a repeated recipient is a construction defect. */
    public NpcRules {
        reports = List.copyOf(reports);
        if (reports.size() > MAX_REPORTS || (!witness && !reports.isEmpty())) {
            throw new IllegalArgumentException("only a witness reports, along at most " + MAX_REPORTS + " routes");
        }
        var recipients = new HashSet<String>();
        for (Report report : reports) {
            if (!recipients.add(report.recipient())) {
                throw new IllegalArgumentException("one route per recipient");
            }
        }
    }

    /** @return true when these rules can change anything in a commit */
    public boolean active() {
        return schedule != null || witness;
    }
}
