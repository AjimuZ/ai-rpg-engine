package org.rpgengine.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * P15.6-A's run-time fallback check for the database world suites. Adaptive quest planning, world direction and plan
 * review are disabled (docs/28 section 12), so the deterministic world rules a committed command runs (NPC schedules and
 * witness reports, relationship causes, faction clock catch-up) must have queued no planning job and no inference task
 * for the quest planner, the world director or the plan reviewer, nor any preparation or speculative task.
 */
final class DirectionFallback {
    private DirectionFallback() { }

    /**
     * Require that a campaign holds no planning job and no planning, direction or review task.
     *
     * @param jdbc the test database
     * @param campaign the campaign whose commands ran
     */
    static void assertNoDirectionWork(JdbcTemplate jdbc, UUID campaign) {
        assertEquals(0, jdbc.queryForObject("select count(*) from ai_job where campaign_id = ?", Integer.class,
                campaign), "planning jobs");
        assertEquals(0, jdbc.queryForObject("select count(*) from inference_task where campaign_id = ? and (role in "
                + "('quest-planner', 'world-director', 'plan-reviewer') or work_class in ('PREPARATION', 'SPECULATIVE'))",
                Integer.class, campaign), "planning, direction or review tasks");
    }
}
