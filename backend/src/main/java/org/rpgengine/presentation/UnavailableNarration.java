package org.rpgengine.presentation;

import org.springframework.stereotype.Component;

/**
 * Default narration port while no narration runtime exists (before P08). It never contacts a
 * process or network and always reports {@link NarrationPort.Outcome#UNAVAILABLE}, so committed
 * operations end as "action saved; narration pending" with their mechanical receipt intact.
 * P08 replaces this bean with the runtime-neutral narration task adapter.
 */
@Component
public class UnavailableNarration implements NarrationPort {
    /** Stateless default. */
    public UnavailableNarration() {
    }

    /** @return always {@link NarrationPort.Outcome#UNAVAILABLE}; the request is not inspected */
    @Override
    public Outcome narrate(Request request) {
        return Outcome.UNAVAILABLE;
    }
}
