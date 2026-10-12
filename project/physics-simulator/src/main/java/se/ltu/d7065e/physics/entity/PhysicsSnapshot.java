package se.ltu.d7065e.physics.entity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import se.ltu.d7065e.ventilation.entity.AirflowRate;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.FanPower;
import se.ltu.d7065e.ventilation.entity.FanSpeed;
import se.ltu.d7065e.ventilation.entity.Ppm;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * Everything one simulated tick produced: the building's true state, what the sensors would
 * publish of it, and the faults that were active while it was computed.
 *
 * <p>True and measured CO2 are separate fields on purpose. A drifting sensor moves only the
 * measured one, and overnight decay of the true one to outdoor is what lets the classifier
 * tell drift from a real rise (ADR-0008, T-15).
 *
 * @param appliedPosition the position the damper physically held, which a stuck damper holds
 *                        against the command
 */
public record PhysicsSnapshot(
        Instant at,
        Map<ZoneId, Ppm> trueCo2,
        Map<ZoneId, Ppm> measuredCo2,
        Map<ZoneId, AirflowRate> flow,
        Map<ZoneId, Integer> occupancy,
        Map<ZoneId, DamperPosition> appliedPosition,
        FanSpeed fanSpeed,
        AirflowRate fanFlow,
        FanPower fanPower,
        double filterBlockage,
        List<InjectedFault> activeFaults) {

    public PhysicsSnapshot {
        trueCo2 = Map.copyOf(trueCo2);
        measuredCo2 = Map.copyOf(measuredCo2);
        flow = Map.copyOf(flow);
        occupancy = Map.copyOf(occupancy);
        appliedPosition = Map.copyOf(appliedPosition);
        activeFaults = List.copyOf(activeFaults);
    }

    public Ppm trueCo2(ZoneId zone) {
        return trueCo2.get(zone);
    }

    public Ppm measuredCo2(ZoneId zone) {
        return measuredCo2.get(zone);
    }

    public AirflowRate flow(ZoneId zone) {
        return flow.get(zone);
    }

    public int occupancy(ZoneId zone) {
        return occupancy.get(zone);
    }

    public DamperPosition appliedPosition(ZoneId zone) {
        return appliedPosition.get(zone);
    }

    /** Sum of the six room flows, which the split keeps equal to the fan's delivered flow. */
    public AirflowRate totalRoomFlow() {
        return AirflowRate.ofCubicMetresPerSecond(
                flow.values().stream().mapToDouble(AirflowRate::cubicMetresPerSecond).sum());
    }
}
