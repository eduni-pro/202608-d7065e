package se.ltu.d7065e.physics.boundary;

import se.ltu.d7065e.physics.control.BuildingSimulation;
import se.ltu.d7065e.physics.entity.PhysicsSnapshot;
import se.ltu.d7065e.shared.control.BuildingStatePort;
import se.ltu.d7065e.shared.control.OccupancyPort;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * Couples the simulation to BuildSim, which is the single source of building state (ADR-0006):
 * each tick it reads the six damper positions and the fan speed the actuators have applied,
 * advances the physics, and writes back what the sensors should read.
 *
 * <p>It writes the *measured* CO2, not the true value, and it does not write the damper
 * positions back. A stuck damper therefore leaves BuildSim showing the position that was
 * commanded while the air behaves as if it were frozen — which is exactly the disagreement
 * the monitor has to notice.
 */
public final class BuildSimStateAdapter {

    private final BuildingStatePort state;
    private final OccupancyPort occupancy;
    private final BuildingSimulation simulation;

    public BuildSimStateAdapter(
            BuildingStatePort state, OccupancyPort occupancy, BuildingSimulation simulation) {
        this.state = state;
        this.occupancy = occupancy;
        this.simulation = simulation;
    }

    /** One read-advance-write exchange with BuildSim. */
    public PhysicsSnapshot exchange() throws InterruptedException {
        for (ZoneId zone : Topology.ZONE_IDS) {
            simulation.commandDamper(zone, state.readDamperPosition(zone));
        }
        simulation.commandFanSpeed(state.readFanSpeed());

        PhysicsSnapshot snapshot = simulation.advance();

        for (ZoneId zone : Topology.ZONE_IDS) {
            state.writeCo2(zone, snapshot.measuredCo2(zone));
        }
        state.writeFanPower(snapshot.fanPower());
        occupancy.publishOccupancy(snapshot.occupancy());
        return snapshot;
    }
}
