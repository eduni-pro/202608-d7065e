package se.ltu.d7065e.ventilation.control;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import se.ltu.d7065e.ventilation.entity.AirHandlingUnit;
import se.ltu.d7065e.ventilation.entity.AirflowRate;
import se.ltu.d7065e.ventilation.entity.VentilationZone;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * The shared air handler's flow split (ADR-0008, option B): {@code Q_i = Q_fan * a_i / sum(a_j)}
 * over the six damper openings {@code a_i = position_i * authority_i}
 * ({@link VentilationZone#openingArea()}), with {@code Q_fan = Q_nom * s * (1 - beta)}.
 *
 * <p>This is the coupling the whole project rests on: six rooms behind one fan, so one damper
 * moving changes the other five rooms' flows. The split conserves air by construction — the six
 * flows always sum to what the fan delivered. Total flow does not depend on the dampers at all;
 * opening one damper does not make more air, it takes air from the other five.
 *
 * <p>It lives here, beside {@link VentilationZone} and {@link AirHandlingUnit}, because it is
 * arithmetic over those two types and nothing else. It was written twice — once in the plant that
 * applies the law and once in the supervisor that solves against it — and two classes of the same
 * name computing the same physics are two places for the law to drift apart, which would make the
 * supervisor's compensation a solution to a building the simulator is not running.
 *
 * <p>The filter blockage {@code beta} scales every room's flow by the same factor, so it cancels
 * out of every <em>ratio</em> the compensation solver works with; the supervisor cannot observe it
 * and never needs to. Absolute flows are quoted at the blockage the caller states.
 */
public interface FlowSplit {

    /** {@code sum(a_j)} — the denominator of the split, over whichever zones the caller passes. */
    /**
     * One zone's share of a stated fan flow, given its opening and the total of all openings.
     *
     * <p>The same law as {@link #across}, expressed over the opening areas themselves rather than
     * over zone records, for a caller that tracks believed positions instead of holding the zones.
     * It exists so the division and its zero guard are written once: with every damper shut there
     * is no air path, which is zero flow rather than a division by zero.
     */
    static AirflowRate shareOf(double opening, double openingTotal, AirflowRate fanFlow) {
        if (openingTotal <= 0 || opening <= 0) {
            return AirflowRate.ofCubicMetresPerSecond(0);
        }
        return AirflowRate.ofCubicMetresPerSecond(
                fanFlow.cubicMetresPerSecond() * opening / openingTotal);
    }

    static double openingSum(Collection<VentilationZone> zones) {
        return zones.stream().mapToDouble(VentilationZone::openingArea).sum();
    }

    /**
     * The split of a stated fan flow across the zones.
     *
     * <p>With every damper shut there is no air path at all, so every room gets zero rather than a
     * division by zero.
     */
    static Map<ZoneId, AirflowRate> across(Collection<VentilationZone> zones, AirflowRate fanFlow) {
        double totalOpening = openingSum(zones);
        Map<ZoneId, AirflowRate> perZone = new LinkedHashMap<>();
        for (VentilationZone zone : zones) {
            perZone.put(zone.id(), AirflowRate.ofCubicMetresPerSecond(totalOpening == 0
                    ? 0
                    : fanFlow.cubicMetresPerSecond() * zone.openingArea() / totalOpening));
        }
        return perZone;
    }

    /** The same split in m³/s, from the handler's commanded speed and a stated filter blockage. */
    static Map<ZoneId, Double> flowPerZone(
            Collection<VentilationZone> zones, AirHandlingUnit airHandler, double filterBlockage) {
        Map<ZoneId, AirflowRate> split = across(zones, airHandler.deliveredFlow(filterBlockage));
        Map<ZoneId, Double> flows = new LinkedHashMap<>();
        split.forEach((zone, rate) -> flows.put(zone, rate.cubicMetresPerSecond()));
        return flows;
    }
}
