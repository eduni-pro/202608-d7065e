package se.ltu.d7065e.diagnosis.control;

import java.util.List;
import java.util.Map;
import java.util.Set;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.FanSpeed;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * Everything this monitor would otherwise lose to a restart, as one immutable value (ADR-0012).
 *
 * <p>The monitor acts on nothing, so what a restart costs here is not a mis-actuated plant but the
 * property the outer loop rests on: a confirmed fault stays confirmed until a technician clears it,
 * which is what stops the supervisor oscillating. Lost with the process, a restart became a second
 * exit from the latch — and the design reserves exactly one, for a human. Four things are therefore
 * here, and each is here for a specific failure rather than for completeness:
 *
 * <ul>
 *   <li>{@code cases} — the latch itself. Restarted empty, every confirmed room comes back HEALTHY
 *       and has to re-earn its diagnosis from a residual the supervisor's own compensation has
 *       already flattened; the diagnosis the technician was sent out on is simply gone.
 *   <li>{@code unresponsiveDampers} — the most safety-relevant item, and the one that is
 *       unrecoverable rather than merely slow. {@link AhuFlowModel} documents losing this pin as
 *       the measured cause of 411 spurious fault events in the five healthy rooms and of R-7
 *       failing in 80 of 270 observed rooms: a damper believed to be wherever it was last
 *       commanded is credited with air it is not getting, and ADR-0008's split is a ratio, so
 *       every ppm wrongly credited here is a ppm taken off the other five.
 *   <li>{@code believedPositions} — where that pin's value lives, and where the other five rooms'
 *       openings live with it. A pinned zone's belief never follows a command again, so its value
 *       cannot be re-derived at all; an unpinned one is corrected by its next command, but
 *       {@code DcvControlLoop} issues a command only when a position changes, and "on a typical
 *       tick is none" — so an unpinned belief reset to the topology minimum is wrong for as long
 *       as the hysteresis stays put, which is hours, not seconds.
 *   <li>{@code believedFanSpeed} — the same argument one level up. It resets to nominal and only
 *       refreshes when the supervisor next publishes a <em>changed</em> speed, and
 *       {@code commandFanSpeed} de-duplicates, so a restarted monitor can stay wrong indefinitely
 *       — and a fan believed lower than it is reads, through the affinity law, as the excess power
 *       of a blocked filter.
 * </ul>
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p><b>Everything transient is left to be re-earned, and a restart is treated as a BLIND gap.</b>
 * That is not a shortcut; it is what the state machine already does at every BLIND exit and every
 * service, and the first reading re-anchors {@link ZoneObserver} from one measurement through the
 * path {@code ZoneMonitor.readingObserved} already takes when it has never seen a reading.
 *
 * <ul>
 *   <li>The observer's trajectory, generation estimate and step window. Re-seeded from one
 *       reading, by design.
 *   <li>Both CUSUM arms. {@link Cusum} states that a run of healthy samples must not bank credit,
 *       so resetting the arms is the designed behaviour and not a loss — and the journal stores
 *       only {@code max(upper, lower)}, so they are not faithfully recoverable in any case.
 *   <li>The cycle counters — warmup, above-limit, quiet. They count towards decisions that only an
 *       unlatched room makes, and an unlatched room starts at WARMUP exactly as it did before.
 *   <li>{@link OvernightFloor}'s samples. See {@link FaultMonitor#recover} for why, and for what
 *       the 24 h of warm-up it costs does and does not affect.
 *   <li>{@code AhuFlowModel.believedBlockage}. Nothing in this system calls
 *       {@link AhuFlowModel#blockageDiagnosed}, so it is zero in every run there is, and storing a
 *       field that cannot be anything but its initial value would be a field to keep honest for
 *       nothing.
 * </ul>
 *
 * @param cases every latched case; the monitor keys each one by its own case's zone
 * @param believedFanSpeed the speed the monitor believes the supervisor has the fan at
 * @param believedPositions the opening the monitor believes every room's damper is at
 * @param unresponsiveDampers the rooms whose belief has stopped following their commands
 */
public record DiagnosisSnapshot(
        List<LatchedCase> cases,
        FanSpeed believedFanSpeed,
        Map<ZoneId, DamperPosition> believedPositions,
        Set<ZoneId> unresponsiveDampers) {

    public DiagnosisSnapshot {
        if (cases == null || believedFanSpeed == null || believedPositions == null
                || unresponsiveDampers == null) {
            throw new IllegalArgumentException(
                    "a diagnosis snapshot needs cases, a fan speed, positions and pins");
        }
        if (!believedPositions.keySet().containsAll(unresponsiveDampers)) {
            throw new IllegalArgumentException(
                    "a pinned damper has to have a believed position, which is the pin's value");
        }
        cases = List.copyOf(cases);
        believedPositions = Map.copyOf(believedPositions);
        unresponsiveDampers = Set.copyOf(unresponsiveDampers);
    }
}
