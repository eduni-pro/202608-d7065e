package se.ltu.d7065e.supervision.control;

import java.util.List;
import java.util.Map;
import java.util.Set;
import se.ltu.d7065e.ventilation.entity.AirHandlingUnit;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.FanSpeed;
import se.ltu.d7065e.ventilation.entity.VentilationZone;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * What the supervisor will do about a latched fault: a trim for every healthy damper, one fan
 * speed, and the honest accounting of what that buys.
 *
 * @param faultedZones the zones whose dampers are frozen; they are not trimmed
 * @param trimmedPositions the new position for each healthy zone
 * @param fanSpeed the speed to command on the air handler
 * @param trim the common factor applied to the healthy positions, {@code t} in the solver
 * @param requestedFaultedFlowGain the gain the supervisor asked for, from measured CO2
 * @param achievedFaultedFlowGain the gain this plan actually delivers to the faulted zones
 * @param worstHealthyFlowDeviation the largest relative airflow change forced on a healthy
 *     zone, which is the number R-7 is asserted against
 * @param limitation why the plan falls short of the request, empty when it does not
 */
public record CompensationPlan(
        Set<ZoneId> faultedZones,
        Map<ZoneId, DamperPosition> trimmedPositions,
        FanSpeed fanSpeed,
        double trim,
        double requestedFaultedFlowGain,
        double achievedFaultedFlowGain,
        double worstHealthyFlowDeviation,
        String limitation) {

    /** Below this the plan moves no air into the faulted zones and must not claim to. */
    public static final double MEANINGFUL_GAIN = 1e-6;

    public CompensationPlan {
        faultedZones = Set.copyOf(faultedZones);
        trimmedPositions = Map.copyOf(trimmedPositions);
        limitation = limitation == null ? "" : limitation;
    }

    /** No trim, no speed change, no promise. */
    public static CompensationPlan none(
            Set<ZoneId> faultedZones, FanSpeed currentSpeed, double requestedGain,
            String limitation) {
        return new CompensationPlan(faultedZones, Map.of(), currentSpeed, 1.0,
                requestedGain, 1.0, 0.0, limitation);
    }

    /** True when the plan actually raises the faulted zones' airflow. */
    public boolean compensates() {
        return achievedFaultedFlowGain > 1 + MEANINGFUL_GAIN;
    }

    /** True when the plan delivers the whole gain the supervisor asked for. */
    public boolean meetsRequest() {
        return achievedFaultedFlowGain >= requestedFaultedFlowGain - MEANINGFUL_GAIN;
    }

    /**
     * The plant as it will be once this plan is applied. The faulted dampers keep their frozen
     * position, so this is also what the R-7 airflow assertion is computed over.
     */
    public PlantSnapshot appliedTo(PlantSnapshot baseline) {
        List<VentilationZone> moved = baseline.zones().stream()
                .map(zone -> trimmedPositions.containsKey(zone.id())
                        ? zone.withSupervisoryCommand(trimmedPositions.get(zone.id()))
                        : zone)
                .toList();
        AirHandlingUnit airHandler = baseline.airHandler().atSpeed(fanSpeed);
        return new PlantSnapshot(moved, airHandler);
    }
}
