package se.ltu.d7065e.supervision.control;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * Everything the supervisor would otherwise lose to a restart, as one immutable value (ADR-0012).
 *
 * <p>The supervisor is the only component in this system that acts, so its lost state is the only
 * lost state that leaves a plant mis-actuated. Three things are therefore here, and each is here
 * for a specific failure rather than for completeness:
 *
 * <ul>
 *   <li>{@code cases} — the sole gate on everything the supervisor does. Restarted empty it
 *       compensates nothing and raises no work order, and worse: the release path returns early
 *       for a {@code SERVICED} on a case it no longer knows, so the technician's service exit
 *       silently fails to put the plant back to NORMAL. Each case carries its
 *       {@linkplain SupervisedCase#frozenZone() frozen zone}, which is the quantity the
 *       compensation gain is computed from, so a wrong one is a wrong trim for every healthy room.
 *   <li>{@code baseline} — the one that can actively make the plant worse. Restarted empty, the
 *       first republished latched event re-snapshots a plant that is <em>already trimmed</em> and
 *       the next solve compounds on top of its own trim.
 *   <li>{@code lastProbes} — {@link ProbeLimiter}'s history. Restarted empty, the first recovered
 *       republish may command an active probe inside the guard interval: a real physical
 *       disturbance of a room already in trouble, which is exactly what the limiter exists to
 *       prevent.
 * </ul>
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p>The compensation plan is not here because the next compensation cycle recomputes it from the
 * baseline and the cases — and the work orders with it, since {@link WorkOrderNarrative} is a pure
 * function of the frozen zone, the classification and the plan. An operator's alert set therefore
 * comes back without a byte of it ever having been stored. The last reading per zone is a
 * transient the next reading replaces within a second, and it has a designed fallback for being
 * missing. The running plant picture is rebuilt by {@link SupervisionService#recover} and the
 * cycle that follows it. Writing state that the next message overwrites anyway buys nothing and
 * costs a document to keep honest.
 *
 * @param cases every open case; the service keys each one by its own frozen zone's id
 * @param baseline the plant as it stood before this episode's first trim, or {@code null} while
 *     nothing is latched, which is what {@link SupervisionService#episodeBaseline()} also answers
 * @param lastProbes when each zone was last probed, on the clock the writing process ran
 */
public record SupervisionSnapshot(
        List<SupervisedCase> cases, PlantSnapshot baseline, Map<ZoneId, Instant> lastProbes) {

    public SupervisionSnapshot {
        if (cases == null || lastProbes == null) {
            throw new IllegalArgumentException("a supervision snapshot needs cases and probes");
        }
        if (cases.isEmpty() != (baseline == null)) {
            throw new IllegalArgumentException(
                    "an open case and an episode baseline exist together or not at all");
        }
        cases = List.copyOf(cases);
        lastProbes = Map.copyOf(lastProbes);
    }
}
