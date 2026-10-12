package se.ltu.d7065e.diagnosis.control;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import se.ltu.d7065e.ventilation.entity.AirHandlingUnit;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.VentilationZone;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * Ground truth for the tests: ADR-0008's six rooms on one shared air handler, with the three
 * injectable faults.
 *
 * <p>This is deliberately a second implementation of the physics rather than a reuse of
 * {@code physics-simulator}: the point of these tests is that the monitor's <em>believed</em>
 * model and the world disagree in specific ways, and sharing one model would make every residual
 * zero by construction. The equations are the ones ADR-0008 fixes — per room
 * {@code V*dC/dt = n*g*1e6 - Q*(C - C_out)}, fan flow {@code Q_nom*s*(1-beta)} split over
 * {@code a_i = position * damperAuthority}, power {@code P_nom*s^3*(1+beta)} — so the disagreement
 * is only ever the fault that was injected, the sensor noise, and the occupancy the observer
 * cannot see.
 */
final class BuildingPhysics {

    private static final double CUBIC_METRES_TO_PPM = 1e6;
    private static final double OUTDOOR = Topology.OUTDOOR.value();

    private final List<VentilationZone> zones = Topology.zones();
    private final Map<ZoneId, VentilationZone> byId = new LinkedHashMap<>();
    private final Map<ZoneId, Double> truePpm = new LinkedHashMap<>();
    private final Map<ZoneId, Double> commanded = new LinkedHashMap<>();
    private final Map<ZoneId, Double> stuckAt = new LinkedHashMap<>();
    private final Map<ZoneId, Double> sensorOffset = new LinkedHashMap<>();
    private final Map<ZoneId, Double> occupants = new LinkedHashMap<>();
    private final Random noise;
    private final double sigmaPpm;
    private final AirHandlingUnit ahu;

    private double fanSpeed;
    private double blockage;

    BuildingPhysics(long seed, double sigmaPpm) {
        this(seed, sigmaPpm, Topology.airHandler());
    }

    /**
     * The same six rooms behind an air handler of a given size, so that a measurement can be
     * repeated against the plant this project used to specify. Detection sensitivity to a blocked
     * filter is a function of how much margin the plant has, so "how small a blockage can the
     * monitor still find" is not a number about the monitor alone and cannot be reported without
     * naming the plant it was measured on.
     */
    BuildingPhysics(long seed, double sigmaPpm, AirHandlingUnit ahu) {
        this.noise = new Random(seed);
        this.sigmaPpm = sigmaPpm;
        this.ahu = ahu;
        this.fanSpeed = ahu.commandedSpeed().value();
        for (VentilationZone zone : zones) {
            byId.put(zone.id(), zone);
            truePpm.put(zone.id(), OUTDOOR);
            commanded.put(zone.id(), zone.commandedPosition().value());
            sensorOffset.put(zone.id(), 0.0);
            occupants.put(zone.id(), 0.0);
        }
    }

    List<VentilationZone> zones() {
        return zones;
    }

    void command(ZoneId zone, double position) {
        commanded.put(zone, position);
    }

    double commandedPosition(ZoneId zone) {
        return commanded.get(zone);
    }

    /** Injects a stuck damper: the command is accepted and the hardware does not move. */
    void stick(ZoneId zone, double at) {
        stuckAt.put(zone, at);
    }

    void unstick(ZoneId zone) {
        stuckAt.remove(zone);
    }

    /** Injects a drifting CO2 sensor: a constant offset on the reading, the room unaffected. */
    void drift(ZoneId zone, double offsetPpm) {
        sensorOffset.put(zone, offsetPpm);
    }

    /** Injects a blocking filter: less air for more power, for all six rooms at once. */
    void blockFilter(double beta) {
        blockage = beta;
    }

    void fanSpeed(double speed) {
        fanSpeed = speed;
    }

    void occupy(ZoneId zone, double people) {
        occupants.put(zone, people);
    }

    void occupyToDesign(ZoneId zone) {
        occupants.put(zone, ZoneObserver.designOccupancy(byId.get(zone)));
    }

    void advance(Duration duration) {
        for (long second = 0; second < duration.getSeconds(); second++) {
            for (VentilationZone zone : zones) {
                double ppm = truePpm.get(zone.id());
                double generation = occupants.get(zone.id())
                        * Topology.GENERATION_PER_PERSON * CUBIC_METRES_TO_PPM;
                double airflow = trueFlow(zone.id());
                double rate = (generation - airflow * (ppm - OUTDOOR)) / zone.volumeCubicMetres();
                truePpm.put(zone.id(), Math.max(OUTDOOR, ppm + rate));
            }
        }
    }

    /** Drives a room straight to the steady state of its current occupancy and airflow. */
    void settle(ZoneId zone) {
        double airflow = trueFlow(zone);
        double generation = occupants.get(zone) * Topology.GENERATION_PER_PERSON * CUBIC_METRES_TO_PPM;
        truePpm.put(zone, airflow <= 0 ? OUTDOOR : OUTDOOR + generation / airflow);
    }

    double truePpm(ZoneId zone) {
        return truePpm.get(zone);
    }

    double measuredPpm(ZoneId zone) {
        return Math.max(0, truePpm.get(zone) + sensorOffset.get(zone) + sigmaPpm * noise.nextGaussian());
    }

    double trueFlow(ZoneId zone) {
        double total = 0;
        for (VentilationZone other : zones) {
            total += actualPosition(other.id()) * other.damperAuthority();
        }
        if (total <= 0) {
            return 0;
        }
        double fanFlow = ahu.nominalFlow().cubicMetresPerSecond() * fanSpeed * (1 - blockage);
        return fanFlow * actualPosition(zone) * byId.get(zone).damperAuthority() / total;
    }

    double fanPowerWatts() {
        return ahu.nominalPowerWatts() * fanSpeed * fanSpeed * fanSpeed * (1 + blockage);
    }

    private double actualPosition(ZoneId zone) {
        Double stuck = stuckAt.get(zone);
        return stuck == null ? commanded.get(zone) : stuck;
    }
}
