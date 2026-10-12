package se.ltu.d7065e.supervision.control;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import se.ltu.d7065e.ventilation.entity.AirHandlingUnit;
import se.ltu.d7065e.ventilation.entity.ControlMode;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.FanSpeed;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.VentilationZone;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * The supervisor's running picture of the plant: one {@link VentilationZone} per room and the
 * one air handler, kept current from the commands it sees on the broker and from its own
 * supervisory output.
 *
 * <p>This lives in {@code control} and not in {@code shared}'s {@code entity} on purpose. The
 * zones and the air handler are shared domain types; <em>which position the supervisor
 * believes each damper is at</em> is the supervisor's own working memory, and no other
 * component reads it.
 *
 * <p>How the frozen position of a faulted damper is known, stated plainly because the
 * compensation gain depends on it: the supervisor has no direct damper feedback on the broker,
 * so it tracks the last position the inner loop <em>commanded</em> and takes that as the
 * position the damper stuck at. That is exactly the quantity ADR-0008's R-2 scenario pins
 * (the demand-controlled floor, 0.15). Once a zone is degraded the tracking stops, so a late
 * inner-loop command cannot move the supervisor's estimate of a damper that is not moving.
 * A deployment with BuildSim wired can correct the estimate through
 * {@link #observeAppliedPosition}, and so can the evaluation harness.
 */
public final class PlantState {

    private final Map<ZoneId, VentilationZone> zones = new LinkedHashMap<>();
    private AirHandlingUnit airHandler;

    public PlantState(List<VentilationZone> initialZones, AirHandlingUnit airHandler) {
        initialZones.forEach(zone -> zones.put(zone.id(), zone));
        this.airHandler = airHandler;
    }

    /** The six rooms of corridor A120 and AHU-A120, healthy, at the ADR-0008 parameters. */
    public static PlantState ofTopology() {
        return new PlantState(Topology.zones(), Topology.airHandler());
    }

    public PlantSnapshot snapshot() {
        return new PlantSnapshot(List.copyOf(zones.values()), airHandler);
    }

    public VentilationZone zone(ZoneId id) {
        VentilationZone zone = zones.get(id);
        if (zone == null) {
            throw new IllegalArgumentException("not a zone of this plant: " + id);
        }
        return zone;
    }

    public List<VentilationZone> zones() {
        return List.copyOf(zones.values());
    }

    public AirHandlingUnit airHandler() {
        return airHandler;
    }

    /**
     * An inner-loop damper command seen on the broker. Ignored for a zone the supervisor has
     * taken away from the inner loop: there the position is either frozen by the fault or
     * owned by the supervisor's own trim.
     */
    public void observeInnerLoopCommand(ZoneId id, DamperPosition position) {
        if (zone(id).mode() == ControlMode.NORMAL) {
            observeAppliedPosition(id, position);
        }
    }

    /** The position a damper is actually at, from feedback or from the supervisor's own command. */
    public void observeAppliedPosition(ZoneId id, DamperPosition position) {
        zones.put(id, zone(id).withSupervisoryCommand(position));
    }

    public void changeMode(ZoneId id, ControlMode mode) {
        zones.put(id, zone(id).inMode(mode));
    }

    public void observeFanSpeed(FanSpeed speed) {
        airHandler = airHandler.atSpeed(speed);
    }
}
