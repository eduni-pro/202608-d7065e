package se.ltu.d7065e.ventilation.control;

import java.time.Instant;
import se.ltu.d7065e.shared.event.CommandIssued;
import se.ltu.d7065e.shared.event.ReadingObserved;
import se.ltu.d7065e.shared.event.StrategyChanged;
import se.ltu.d7065e.ventilation.entity.CommandId;
import se.ltu.d7065e.ventilation.entity.ControlMode;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.VentilationZone;

/**
 * One room's inner-loop state: the newest trusted reading, the strategy the supervisor has set
 * for the room, and the position last commanded. Mutable by design — it is the loop's working
 * memory for one zone — and guarded by {@link DcvControlLoop}, which owns the lock.
 */
public final class ZoneControl {

    public static final String TRIM_HONOURED = "honouring the supervisory trim";

    private VentilationZone zone;

    /** Null until the first usable reading arrives; a zone with no reading is not commanded. */
    private ReadingObserved latest;

    /** The supervisor's trim while COMPENSATING, null otherwise. */
    private DamperPosition trim;

    ZoneControl(VentilationZone zone) {
        this.zone = zone;
    }

    /**
     * Keeps the reading if it is usable and not older than the one already held.
     *
     * <p>A reading the sensor boundary already stamped STALE, MISSING or DUPLICATE is dropped
     * rather than stored: refusing it here is the same refusal as the age check in
     * {@link #target}, just made one step earlier. Dropping the out-of-order redelivery that
     * QoS 1 permits matters for the same reason — a replayed old reading must not make the data
     * look fresher than it is.
     */
    void observe(ReadingObserved reading) {
        if (!reading.quality().isUsable()) {
            return;
        }
        if (latest != null && reading.at().value().isBefore(latest.at().value())) {
            return;
        }
        latest = reading;
    }

    /**
     * Takes the supervisor's strategy for this room. Returning to NORMAL clears the trim, which
     * is what "resume normal control" means.
     */
    void apply(StrategyChanged strategy) {
        zone = zone.inMode(strategy.mode());
        trim = strategy.mode() == ControlMode.COMPENSATING && strategy.trimmedPosition() != null
                ? DamperPosition.of(strategy.trimmedPosition())
                : null;
    }

    /**
     * The command this room needs now, or {@code null} when it needs none — which is the normal
     * case on most ticks.
     */
    CommandIssued decide(Instant now, ControlSettings settings) {
        if (zone.mode() == ControlMode.DEGRADED) {
            // The supervisor has taken this damper away: the fault is latched here and the
            // compensation that keeps R-2's comfort claim true, and the isolation R-7 claims
            // for the other five rooms, both rest on the inner loop not touching it.
            // VentilationZone.withControlCommand below would throw on this zone anyway; this
            // guard states the refusal, and the record's invariant is the backstop that makes
            // it impossible to get wrong.
            return null;
        }
        DamperDemand demand = target(now, settings);
        if (demand == null) {
            return null;
        }
        if (!demand.position().differsFrom(zone.commandedPosition(), settings.publishDeadband())) {
            return null;
        }
        zone = zone.withControlCommand(demand.position());
        return new CommandIssued(zone.id(), zone.commandedPosition(), ObservedAt.of(now),
                demand.reason(), CommandId.newId());
    }

    /**
     * What this room should be commanded to, or {@code null} to hold the last position.
     *
     * <p>The trim is checked first: while the supervisor is pushing flow to a faulted neighbour,
     * the trim is this room's commanded position and CO2 does not get a vote, because undoing
     * the trim would take the flow back out of the room R-2 is about.
     */
    private DamperDemand target(Instant now, ControlSettings settings) {
        if (zone.mode() == ControlMode.COMPENSATING && trim != null) {
            return new DamperDemand(trim, TRIM_HONOURED);
        }
        if (latest == null || latest.at().isStalerThan(settings.freshnessLimit(), now)) {
            return null;
        }
        return DamperDemand.from(latest.ppm(), zone.commandedPosition(), settings);
    }

    boolean hasFreshReading(Instant now, ControlSettings settings) {
        return latest != null && !latest.at().isStalerThan(settings.freshnessLimit(), now);
    }

    public VentilationZone zone() {
        return zone;
    }

    public ControlMode mode() {
        return zone.mode();
    }

    public DamperPosition commandedPosition() {
        return zone.commandedPosition();
    }
}
