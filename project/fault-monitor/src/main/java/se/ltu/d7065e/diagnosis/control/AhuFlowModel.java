package se.ltu.d7065e.diagnosis.control;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import se.ltu.d7065e.ventilation.entity.AirHandlingUnit;
import se.ltu.d7065e.ventilation.entity.AirflowRate;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.FanSpeed;
import se.ltu.d7065e.ventilation.entity.VentilationZone;
import se.ltu.d7065e.ventilation.control.FlowSplit;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * What the monitor <em>believes</em> the shared air handler is doing, which is ADR-0008's
 * price for a cross-room signal: because the six dampers share one fan, no room's airflow can
 * be worked out from its own command alone.
 *
 * <p>The believed flow follows ADR-0008 exactly: {@code Q_fan = Q_nom * s * (1 - beta)},
 * {@code a_i = position_i * damperAuthority_i}, {@code Q_i = Q_fan * a_i / sum(a_j)}. Two
 * consequences of that law matter to the observer and are easy to get wrong:
 *
 * <ul>
 * <li>Total flow does not depend on the dampers at all. Opening one damper does not make more
 *     air, it takes air from the other five. A command on one room therefore changes the
 *     believed flow of all six, which is why every zone observer reads this one model.
 * <li>{@code beta} is the monitor's <em>belief</em> about filter blockage, and it is zero
 *     until a blocked filter has been diagnosed. That belief gap is the blocked-filter signal:
 *     while the real filter is blocking, the monitor believes in air that is not arriving, so
 *     every room's predicted CO2 comes out too low and the fan draws more power than the
 *     believed speed can explain. Setting {@code beta} from the diagnosis closes the gap and
 *     stops the monitor re-reporting a filter it has already reported.
 * </ul>
 *
 * <h2>Why a damper's belief stops following its commands (R-7)</h2>
 *
 * The same belief gap exists one level down, per damper, and it is the defect that broke R-7.
 * This model's {@code a_i} used to be <em>wherever the controller last commanded it</em>, with no
 * exception for a damper the monitor had just diagnosed as not answering commands. The
 * consequence follows straight from the split being a ratio: a stuck damper goes on being
 * credited with air it is not getting — the evaluation measured A115 commanded up to 0.49 while
 * frozen at 0.15 — and because {@code Q_i = Q_fan * a_i / sum(a_j)} is normalised, every ppm of
 * air wrongly credited to the stuck room is a ppm taken off the <em>other five</em>. Their
 * predictions then run above their measurements, their residuals go negative, and the guarded
 * lower arm accumulates until whichever room's occupancy gets there first is confirmed
 * {@code STUCK_DAMPER} with no fault in it. Measured with the supervisor switched off entirely —
 * so nothing was acting on the diagnosis — the monitor raised 411 fault events in the five
 * healthy rooms on its own, and R-7 failed in 80 of 270 observed rooms while the compensation
 * airflow deviation was exactly 0.0000. The algebra was never the problem; the belief feeding it
 * was.
 *
 * <p>So {@link #damperUnresponsive(ZoneId, DamperPosition)} pins a zone's believed opening and
 * {@link #positionCommanded(ZoneId, DamperPosition)} stops writing to it. A command is evidence
 * about an actuator only while that actuator is believed to answer commands; once the monitor has
 * latched the opposite, continuing to believe the command is believing a proposition the monitor
 * itself has rejected. The pin is lifted only by {@link #damperResponsive(ZoneId)}, which the
 * service exit calls, because service is the one event that makes the commands meaningful again.
 *
 * <p>{@link #openingImpliedByFlow(ZoneId, double)} is the other half: it inverts the split so
 * that a flow inferred from a room's own CO2 can be turned back into the opening that would
 * produce it, which is how {@link ZoneObserver#inferredPosition(double)} arrives at the pinned
 * value instead of the monitor having to guess one.
 */
public final class AhuFlowModel {

    private final AirHandlingUnit ahu;
    private final Map<ZoneId, Double> authority = new LinkedHashMap<>();
    private final Map<ZoneId, DamperPosition> believed = new LinkedHashMap<>();
    private final Set<ZoneId> unresponsive = new LinkedHashSet<>();
    private FanSpeed believedSpeed;
    private double believedBlockage;

    public AhuFlowModel(AirHandlingUnit ahu, List<VentilationZone> zones) {
        this.ahu = ahu;
        this.believedSpeed = ahu.commandedSpeed();
        for (VentilationZone zone : zones) {
            authority.put(zone.id(), zone.damperAuthority());
            believed.put(zone.id(), zone.commandedPosition());
        }
    }

    /**
     * A command moves the belief, unless the monitor has latched this damper as one that does not
     * answer commands. See the class comment: that exception is R-7's fix, not an optimisation.
     */
    public void positionCommanded(ZoneId zone, DamperPosition position) {
        if (authority.containsKey(zone) && !unresponsive.contains(zone)) {
            believed.put(zone, position);
        }
    }

    public DamperPosition positionOf(ZoneId zone) {
        return believed.get(zone);
    }

    /**
     * Pins a zone's believed opening and stops it following commands, for a damper the monitor has
     * diagnosed as unresponsive. Idempotent on the flag and repeatable on the value, so a case that
     * re-argues its diagnosis may sharpen the estimate without having to clear the pin first.
     */
    public void damperUnresponsive(ZoneId zone, DamperPosition effective) {
        if (!authority.containsKey(zone)) {
            return;
        }
        unresponsive.add(zone);
        believed.put(zone, effective);
    }

    /** Lets a zone's belief follow its commands again, which only service may do. */
    public void damperResponsive(ZoneId zone) {
        unresponsive.remove(zone);
    }

    public boolean believesUnresponsive(ZoneId zone) {
        return unresponsive.contains(zone);
    }

    /**
     * The split law run backwards for one zone: the opening that would give it {@code flow}, with
     * the other zones believed where they are. From {@code Q_i = Q_fan * a_i / (a_i + T)}, where
     * {@code T} is the others' total opening, {@code a_i = Q_i * T / (Q_fan - Q_i)}.
     *
     * <p>{@code NaN} when the flow is not a share of the believed fan flow at all — zero or
     * negative, or at least the whole of it, which would need the other five dampers shut and is
     * not a position this building can be in ({@code MINIMUM_POSITION} is 0.15). The caller gets
     * "no opening explains this flow" rather than a number produced by dividing by nothing.
     */
    public double openingImpliedByFlow(ZoneId zone, double flowCubicMetresPerSecond) {
        Double zoneAuthority = authority.get(zone);
        double fanFlow = believedFanFlow().cubicMetresPerSecond();
        if (zoneAuthority == null || zoneAuthority <= 0
                || !(flowCubicMetresPerSecond > 0) || flowCubicMetresPerSecond >= fanFlow) {
            return Double.NaN;
        }
        double others = 0;
        for (ZoneId other : authority.keySet()) {
            if (!other.equals(zone)) {
                others += openingOf(other);
            }
        }
        double opening = flowCubicMetresPerSecond * others / (fanFlow - flowCubicMetresPerSecond);
        return opening / zoneAuthority;
    }

    /**
     * The supervisor owns {@code commands/{ahu}/fan-speed} (plan section 3), and this module
     * subscribes to the three topics the monitor is allowed to read, which do not include it.
     * The belief therefore stays at nominal unless something hands it a better value; this
     * setter exists so the AHU belief has exactly one home rather than being assumed in two
     * places. A fan the supervisor has raised looks like excess power here, which is why the
     * blocked-filter branch of {@link FaultClassifier} also demands the common-mode residual:
     * compensation lowers residuals, a blocking filter raises all six.
     */
    public void fanSpeedBelieved(FanSpeed speed) {
        believedSpeed = speed;
    }

    public FanSpeed believedFanSpeed() {
        return believedSpeed;
    }

    public void blockageDiagnosed(double blockage) {
        if (!(blockage >= 0 && blockage < 1)) {
            throw new IllegalArgumentException("believed blockage must be within [0,1), was: " + blockage);
        }
        believedBlockage = blockage;
    }

    public double believedBlockage() {
        return believedBlockage;
    }

    public AirflowRate believedFanFlow() {
        return AirflowRate.ofCubicMetresPerSecond(
                ahu.nominalFlow().cubicMetresPerSecond() * believedSpeed.value() * (1 - believedBlockage));
    }

    /** The power the affinity law predicts at the believed speed and blockage. */
    public double believedFanPowerWatts() {
        double s = believedSpeed.value();
        return ahu.nominalPowerWatts() * s * s * s * (1 + believedBlockage);
    }

    /**
     * The flow the monitor believes this room is getting, in m3/s.
     *
     * <p>The split law itself lives in {@link FlowSplit}, so the monitor's belief and the
     * simulator's physics cannot drift apart: a monitor computing the split differently from the
     * plant would produce a residual that measured the disagreement between two implementations
     * rather than the fault. This model only supplies the believed inputs.
     */
    public double believedFlowTo(ZoneId zone) {
        double total = 0;
        for (ZoneId other : authority.keySet()) {
            total += openingOf(other);
        }
        return FlowSplit.shareOf(openingOf(zone), total, believedFanFlow()).cubicMetresPerSecond();
    }

    private double openingOf(ZoneId zone) {
        return believed.get(zone).value() * authority.get(zone);
    }

    /**
     * Resumes the belief an earlier run of this process left behind (ADR-0012), which for this
     * class means the pins above all: a pinned damper's belief never follows a command again, so
     * {@link #damperUnresponsive} is the only thing that can ever have set it and a restart that
     * lost it would hand the other five rooms the belief error this class's comment measures.
     *
     * <p>Package-private and reached only through {@link FaultMonitor#recover}, which resolves
     * every zone named here against this plant before anything is mutated. So no zone check is
     * repeated here: by the time this runs the only way to arrive is with a zone set this model
     * already has.
     */
    void recover(FanSpeed speed, Map<ZoneId, DamperPosition> positions, Set<ZoneId> pinned) {
        believedSpeed = speed;
        believed.putAll(positions);
        unresponsive.addAll(pinned);
    }
}
