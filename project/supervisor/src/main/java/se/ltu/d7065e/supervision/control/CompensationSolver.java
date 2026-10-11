package se.ltu.d7065e.supervision.control;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import se.ltu.d7065e.ventilation.control.FlowSplit;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.FanSpeed;
import se.ltu.d7065e.ventilation.entity.VentilationZone;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * Solves ADR-0008 option B's compensation: trim the healthy dampers so the proportional split
 * pushes flow to the frozen one, and raise the air handler so the healthy rooms' own flows stay
 * where they were (R-7 asserts on their airflow, not only their CO2 — ADR-0008 consequence 9).
 * Where the trim is exhausted or insufficient, fan speed carries what it can on its own, bounded
 * by what the healthy rooms may be disturbed by rather than by the plant's ceiling alone.
 *
 * <h2>The algebra, which closes exactly</h2>
 *
 * With {@code a_i = position_i * authority_i}, the split is {@code Q_i = Q_fan * a_i / A} where
 * {@code A = sum(a_j)} and {@code Q_fan = Q_nom * s * (1 - beta)}. Write {@code f} for the sum
 * of the frozen zones' openings, {@code h} for the sum of the healthy zones' openings at the
 * baseline, {@code A0 = h + f}, and {@code s0} for the baseline fan speed. Trim every healthy
 * damper by one common factor {@code t} in (0,1]; the frozen dampers do not move, so
 *
 * <pre>
 *   A(t) = t*h + f
 *   healthy flow ratio   r(t,s) = (s/s0) * t * A0 / A(t)
 *   faulted flow ratio   g(t,s) = (s/s0) *     A0 / A(t)  =  r(t,s) / t
 * </pre>
 *
 * Two facts fall straight out and they are the whole mechanism:
 *
 * <ol>
 *   <li>One speed holds <em>every</em> healthy room's flow at once — {@code r = 1} for all of
 *       them simultaneously at {@code s = s0 * A(t) / (t * A0)}. There is nothing per-room to
 *       balance, because the trim is common and the split is proportional.
 *   <li>At that speed the frozen zones' flow is multiplied by exactly {@code 1/t}. The trim
 *       <em>is</em> the gain.
 * </ol>
 *
 * The blockage {@code beta} and the nominal flow cancel out of both ratios, which is why the
 * supervisor can solve this without observing either.
 *
 * <h2>The second lever: fan speed on its own</h2>
 *
 * The trim is what makes a speed rise <em>selective</em>. Without one, {@code A(t) = A0} and both
 * ratios collapse to {@code r = g = s/s0}: raising the fan lifts the frozen room and the five
 * healthy rooms by one and the same factor, because the split's denominator has not moved. That
 * is still a lever on the room R-2 is about — {@code Q_faulted} is linear in {@code s}, so the
 * speed that delivers a wanted gain is read off directly as {@code s = wanted * t * s(t)} with no
 * search — and at {@code Topology.NORMAL_FAN_SPEED} = 0.7 against {@link FanSpeed#MAXIMUM} there
 * is 43 % of it unspent. It is not free in either of the two ways that matter:
 *
 * <ul>
 *   <li><b>The healthy rooms are disturbed, upwards.</b> They are over-ventilated by exactly the
 *       factor the frozen room gains, which is the same {@link #healthyStarveTolerance} R-7 is
 *       asserted against, read in the other direction. The solver therefore spends fan headroom
 *       beyond {@code s(t)} only up to {@code r = 1 + }{@link #healthyOversupplyTolerance};
 *       past that the air would be taken out of one requirement to put it into another. With
 *       <em>every</em> zone faulted there is no healthy room left to disturb and the only bound is
 *       the fan's ceiling.
 *   <li><b>Fan power goes as {@code s^3}.</b> The affinity law on
 *       {@link se.ltu.d7065e.ventilation.entity.AirHandlingUnit} charges {@code (s/s0)^3} for a
 *       raise: 0.70 to 0.735 costs 1.16 times the fan power, and 0.70 to 1.0 costs 2.92 times it.
 *       The supervisor states that multiple in the fan command's own reason, so the trade is on
 *       the record rather than silent.
 * </ul>
 *
 * <h2>Where it runs out</h2>
 *
 * Three hard limits bound the pair {@code (t, s)}, and all three are physical rather than
 * numerical:
 *
 * <ul>
 *   <li><b>The dampers' floor.</b> No healthy damper may be trimmed below its own minimum
 *       position, so {@code t >= max(minimum_i / position_i)}. With every healthy damper already
 *       at its floor there is no trim at all, the proportional split cannot be shifted, and what
 *       is left is the non-selective fan lever above — worth {@code 1 + tolerance} and no more,
 *       since with {@code t = 1} the frozen room's gain <em>is</em> the healthy rooms'
 *       disturbance.
 *   <li><b>The healthy rooms' tolerance</b>, in both directions and separately bounded: the trim
 *       may not take more than {@link #healthyStarveTolerance} from them and the fan may not give
 *       them more than {@link #healthyOversupplyTolerance}. The two sides are different
 *       quantities — taking air from a healthy room can cost comfort, giving it air costs only
 *       energy — so they are two numbers rather than one, even though ADR-0009 sets them equal.
 *   <li><b>The fan's ceiling.</b> {@code s(t) > s0} always, so holding the healthy flows is paid
 *       for out of fan headroom, and {@code s} is capped at {@link FanSpeed#MAXIMUM}. In the
 *       ADR-0008 topology the 43 % spare at 0.7 is enough to restore a room frozen at the damper
 *       floor to its full design flow with the other five rooms' flows unchanged to the last
 *       decimal. When the plant is <em>already</em> at full speed — an earlier compensation
 *       episode that has not been released, or a blocked filter this supervisor has itself
 *       already answered with speed; never the inner loop, which does not command the fan at
 *       all — there is no headroom, and the solver neither abandons compensation nor quietly
 *       starves the healthy rooms: it trims only as far as {@link #healthyStarveTolerance} allows,
 *       so each healthy room gives up at most that fraction of its airflow, and it reports the
 *       smaller gain it actually achieved. Flow is then redistributed rather than created, which
 *       is the honest description of a saturated fan.
 * </ul>
 *
 * With the fan saturated, solving {@code r(t, MAXIMUM) = 1 - tolerance} gives the trim floor
 * {@code t = (1-tol)*f / (q*A0 - (1-tol)*h)} with {@code q = MAXIMUM/s0}, whose denominator is
 * {@code f + tol*h > 0} at {@code q = 1}, so it is always defined.
 *
 * <p>A zone whose frozen opening is zero is a separate case and not a numerical one: the gain
 * multiplies an airflow of zero, so no trim and no fan speed reaches it. The solver returns
 * {@link CompensationPlan#none} with that stated, and the supervisor raises a critical work
 * order with occupancy advice instead of pretending (the stated bound on R-2).
 */
public final class CompensationSolver {

    /**
     * How far a healthy room's own airflow may be moved by the compensation, as a fraction, in
     * either direction: the trim may take this much from it when the fan is saturated, and the
     * fan may give it this much when the trim is spent. Zero while one preserving fan speed
     * holds every healthy flow exactly, which is the case whenever the trim alone covers the
     * request. It is the tolerance R-7's airflow assertion is read against — two-sided there
     * too — and the reason it is a constant here is so that the report can quote one number.
     *
     * <p>ADR-0009 asked whether the over-supply side should be loosened to buy R-2 the
     * design-occupancy case, and the answer measured in T-26 is no: no setting of it buys a
     * single occupied minute below 1000 ppm there, because the gain the regime needs is 2.0 to
     * 3.3 and the plant's own ceiling on a non-selective fan raise is 1/0.7 = 1.43. The two
     * sides are therefore separately named below and separately bounded, but set equal.
     */
    public static final double HEALTHY_FLOW_TOLERANCE = 0.05;

    /**
     * The most the supervisor will ask for. Not a tuning knob: with the healthy dampers
     * modulating around 60 % and their floor at 0.15, the trim bottoms out at 0.25 and the split
     * cannot deliver more than a fourfold increase anyway. Beyond that the room is not short of
     * air by an amount ventilation can make up, and chasing the number would mean trusting an
     * extreme reading from a room whose sensor may itself be the fault.
     */
    public static final double MAXIMUM_REQUESTED_GAIN = 4.0;

    private final double healthyStarveTolerance;
    private final double healthyOversupplyTolerance;

    public CompensationSolver() {
        this(HEALTHY_FLOW_TOLERANCE);
    }

    public CompensationSolver(double healthyFlowTolerance) {
        this(healthyFlowTolerance, healthyFlowTolerance);
    }

    /**
     * @param healthyStarveTolerance the fraction of its own airflow a healthy room may be made to
     *     give up, which is the side that can cost comfort and the side R-7 is asserted against
     * @param healthyOversupplyTolerance the fraction of its own airflow a healthy room may be
     *     given on top, which costs fan energy rather than comfort
     */
    public CompensationSolver(
            double healthyStarveTolerance, double healthyOversupplyTolerance) {
        this.healthyStarveTolerance = checked("starve", healthyStarveTolerance);
        this.healthyOversupplyTolerance = checked("over-supply", healthyOversupplyTolerance);
    }

    private static double checked(String side, double tolerance) {
        if (!(tolerance >= 0 && tolerance < 1)) {
            throw new IllegalArgumentException("healthy %s tolerance must be within [0,1), was: %s"
                    .formatted(side, tolerance));
        }
        return tolerance;
    }

    /** How much of its own airflow a healthy room may be made to give up. */
    public double healthyStarveTolerance() {
        return healthyStarveTolerance;
    }

    /** How much airflow a healthy room may be given on top of its own, while a fault is latched. */
    public double healthyOversupplyTolerance() {
        return healthyOversupplyTolerance;
    }

    /**
     * @param baseline the plant as it was before the fault was compensated — never the already
     *     trimmed plant, or a second solve would compound its own trim
     * @param faultedZones the zones whose dampers are frozen
     * @param requestedGain the airflow multiplier the faulted zones need, at least 1
     */
    public CompensationPlan solve(
            PlantSnapshot baseline, Set<ZoneId> faultedZones, double requestedGain) {
        FanSpeed baselineSpeed = baseline.airHandler().commandedSpeed();
        double wanted = Math.min(MAXIMUM_REQUESTED_GAIN, Math.max(1.0, requestedGain));

        List<VentilationZone> healthy = baseline.zonesOtherThan(faultedZones);
        double frozenOpening = faultedZones.stream()
                .mapToDouble(id -> baseline.zone(id).openingArea()).sum();
        double healthyOpening = FlowSplit.openingSum(healthy);

        if (frozenOpening <= CompensationPlan.MEANINGFUL_GAIN) {
            return CompensationPlan.none(faultedZones, baselineSpeed, wanted,
                    "the faulted damper is shut, so there is no air path into the room and no"
                            + " trim or fan speed can multiply a flow of zero");
        }

        // With no healthy room left there is nothing to trim and nothing to disturb, so the fan
        // lever runs all the way to the plant's ceiling.
        boolean noHealthyRoom = healthy.isEmpty() || healthyOpening <= 0;
        double baselineOpening = healthyOpening + frozenOpening;
        double trimFloor = noHealthyRoom ? 1.0 : trimFloor(healthy);
        double trim = trimFloor >= 1.0 ? 1.0 : Math.max(trimFloor, 1 / wanted);
        if (preservingSpeed(trim, healthyOpening, frozenOpening, baselineSpeed) > FanSpeed.MAXIMUM) {
            trim = Math.max(trim, toleratedTrimFloor(healthyOpening, frozenOpening, baselineSpeed));
            trim = Math.min(1.0, Math.max(trim, trimFloor));
        }

        double preserving = preservingSpeed(trim, healthyOpening, frozenOpening, baselineSpeed);
        // Both the speed that delivers the whole request and the speed the healthy rooms can
        // absorb are multiples of the preserving speed, because every ratio in this solver is
        // linear in s: the request needs s = (wanted * t) * s(t), and the healthy rooms tolerate
        // s = (1 + tolerance) * s(t). Writing them that way also means a request the trim alone
        // covers lands on s(t) exactly rather than on a rounding of it.
        double demanded = preserving * Math.max(1.0, wanted * trim);
        double tolerated = noHealthyRoom
                ? FanSpeed.MAXIMUM
                : Math.min(FanSpeed.MAXIMUM, preserving * (1 + healthyOversupplyTolerance));
        FanSpeed speed = FanSpeed.clamped(Math.min(tolerated, demanded));
        double speedRatio = speed.value() / baselineSpeed.value();
        double trimmedOpening = trim * healthyOpening + frozenOpening;
        double healthyRatio = speedRatio * trim * baselineOpening / trimmedOpening;
        double gain = healthyRatio / trim;

        // A trim of 1 moves no damper, and publishing it anyway would take five healthy rooms off
        // the inner loop's CO2 control to hold them exactly where they already are.
        Map<ZoneId, DamperPosition> trimmed = new LinkedHashMap<>();
        if (trim < 1 - CompensationPlan.MEANINGFUL_GAIN) {
            for (VentilationZone zone : healthy) {
                trimmed.put(zone.id(),
                        DamperPosition.of(trim * zone.commandedPosition().value())
                                .clampedTo(zone.minimumPosition()));
            }
        }
        return new CompensationPlan(faultedZones, trimmed, speed, trim, wanted, gain,
                noHealthyRoom ? 0.0 : Math.abs(1 - healthyRatio),
                limitation(gain, wanted, speed, lever(noHealthyRoom, trim, trimFloor)));
    }

    /**
     * The gain a room needs to come back under the comfort limit, read off its own measurement.
     * At steady state {@code n*g = Q*(C - C_out)}, so the flow that would hold a target
     * concentration scales as {@code (C_measured - C_out) / (C_target - C_out)} without the
     * supervisor ever knowing the occupancy or the generation rate.
     */
    public static double gainForComfort(double measuredPpm, double targetPpm, double outdoorPpm) {
        double excess = measuredPpm - outdoorPpm;
        double allowed = targetPpm - outdoorPpm;
        if (allowed <= 0 || excess <= allowed) {
            return 1.0;
        }
        return excess / allowed;
    }

    /** {@code s(t) = s0 * A(t) / (t * A0)}: the speed at which every healthy flow is unchanged. */
    private static double preservingSpeed(
            double trim, double healthyOpening, double frozenOpening, FanSpeed baselineSpeed) {
        return baselineSpeed.value() * (trim * healthyOpening + frozenOpening)
                / (trim * (healthyOpening + frozenOpening));
    }

    /** The smallest trim whose healthy-flow loss stays inside the tolerance at full fan speed. */
    private double toleratedTrimFloor(
            double healthyOpening, double frozenOpening, FanSpeed baselineSpeed) {
        double kept = 1 - healthyStarveTolerance;
        double speedRatio = FanSpeed.MAXIMUM / baselineSpeed.value();
        return kept * frozenOpening
                / (speedRatio * (healthyOpening + frozenOpening) - kept * healthyOpening);
    }

    /** No healthy damper may be driven below its own floor; the tightest one fixes the trim. */
    private static double trimFloor(List<VentilationZone> healthy) {
        double floor = 0;
        for (VentilationZone zone : healthy) {
            double position = zone.commandedPosition().value();
            if (position > 0) {
                floor = Math.max(floor, zone.minimumPosition().value() / position);
            }
        }
        return floor;
    }

    /** Why the trim is where it is, which is half of why a short plan is short. */
    private static String lever(boolean noHealthyRoom, double trim, double trimFloor) {
        if (noHealthyRoom) {
            return "every zone is faulted, so there is no healthy damper to trim and fan speed is"
                    + " the only lever";
        }
        if (trim >= 1 - CompensationPlan.MEANINGFUL_GAIN) {
            return "every healthy damper is already at its minimum position, so the proportional"
                    + " split cannot be shifted and fan speed is the only lever";
        }
        if (trim <= trimFloor + CompensationPlan.MEANINGFUL_GAIN) {
            return "the healthy dampers reached their minimum position, so the trim stops at"
                    + " %.0f%%".formatted(trim * 100);
        }
        return "the trim is at %.0f%%".formatted(trim * 100);
    }

    /** What a plan that fell short actually ran into, naming both levers rather than one. */
    private String limitation(double gain, double wanted, FanSpeed speed, String lever) {
        if (gain >= wanted - CompensationPlan.MEANINGFUL_GAIN) {
            return "";
        }
        String fan = speed.value() >= FanSpeed.MAXIMUM - CompensationPlan.MEANINGFUL_GAIN
                ? "the fan is at its ceiling %s".formatted(speed)
                : ("raising the fan above %s would move the healthy rooms' own airflow by more"
                        + " than %.0f%%").formatted(speed, healthyOversupplyTolerance * 100);
        return "%s and %s, so the gain stops at %.2f".formatted(lever, fan, gain);
    }
}
