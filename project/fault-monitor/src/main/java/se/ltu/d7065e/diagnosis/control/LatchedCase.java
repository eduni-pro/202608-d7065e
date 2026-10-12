package se.ltu.d7065e.diagnosis.control;

import java.time.Instant;
import se.ltu.d7065e.diagnosis.entity.FaultCase;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * One room's latched diagnosis as it crosses a restart (ADR-0012) — the case itself, plus the
 * instant it latched.
 *
 * <p>The case already carries its zone, its monitor state, its classification and its evidence, so
 * nothing of it is restated here. {@link #confirmedAt} is the one quantity the case does not hold:
 * {@link FaultCase#openedAt()} is when suspicion opened, which is earlier and is not the same
 * number, and the confirmation instant is what a deferred diagnosis reports itself to have waited
 * from.
 *
 * <p>Only a latched case is ever one of these, which is the invariant below. A SUSPECT case is
 * deliberately not carried across a restart: it is reversible by design, it decays to HEALTHY on
 * its own, and the CUSUM it was argued on is not restored — so a resumed suspicion would be a
 * shell that cleared itself a few cycles later while claiming to be evidence.
 *
 * @param openCase the latched case, whole, so the diagnosis comes back explainable and not merely
 *     named
 * @param confirmedAt when the case latched, on the clock the writing process ran
 */
public record LatchedCase(FaultCase openCase, Instant confirmedAt) {

    public LatchedCase {
        if (openCase == null || confirmedAt == null) {
            throw new IllegalArgumentException("a latched case needs a case and a confirmation instant");
        }
        if (!openCase.isLatched()) {
            throw new IllegalArgumentException(
                    "only a latched case survives a restart, state was " + openCase.state());
        }
    }

    public ZoneId zone() {
        return openCase.zone();
    }
}
