package se.ltu.d7065e.physics.control;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import se.ltu.d7065e.physics.entity.FaultKind;
import se.ltu.d7065e.physics.entity.InjectedFault;
import se.ltu.d7065e.physics.entity.PhysicsSnapshot;
import se.ltu.d7065e.shared.control.ClockPort;
import se.ltu.d7065e.ventilation.control.FlowSplit;
import se.ltu.d7065e.ventilation.entity.AirHandlingUnit;
import se.ltu.d7065e.ventilation.entity.AirflowRate;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.FanSpeed;
import se.ltu.d7065e.ventilation.entity.Ppm;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.VentilationZone;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * The simulated building: six rooms of air behind one air handler (T-07), with faults injected
 * between a command and its physical effect (T-14).
 *
 * <p>One tick spans {@code tick} of <em>simulated</em> time and is integrated in forward-Euler
 * steps of one simulated second. Each step, in order:
 * <ol>
 *   <li>the commanded damper positions and fan speed, as last set by the actuators;</li>
 *   <li>the faults active at this instant — a stuck damper replaces its room's command, a
 *       blocked filter sets beta;</li>
 *   <li>the fan's delivered flow {@code Q_nom * s * (1 - beta)} split over the six dampers;</li>
 *   <li>occupancy from the schedules;</li>
 *   <li>one forward-Euler step of each room's CO2 balance;</li>
 * </ol>
 * and the last step of the tick also produces what the sensors publish: true CO2 plus any sensor
 * drift plus 30 ppm of noise. One published sample per tick, whatever the tick spans.
 *
 * <p>Sub-stepping is what makes a 60x demo cost nothing in fidelity: a tick of 60 simulated
 * seconds is sixty one-second steps with the faults, the flow split and the schedules re-evaluated
 * at every one of them, so its true state is bit-for-bit what sixty one-second ticks would have
 * produced. The tick length only decides how often the plant is sampled, never how it behaves,
 * and neither does the length of any one tick: {@link #advance()} integrates whatever span
 * the clock actually moved, so a tick that carried more simulated time than the configured
 * one is that many one-second steps rather than a jump.
 *
 * <p>Nothing here reads the wall clock or sleeps: time arrives through {@link ClockPort}, so
 * the evaluation harness can drive a simulated week through this class in milliseconds with a
 * {@code SimulatedClock}, the container drives it in real time with a {@code SystemClock}, and the
 * demo drives it faster than real time with a {@code ScaledClock}.
 *
 * <p>Damper positions below a room's minimum are clamped to it, by the invariant on
 * {@link VentilationZone}: ADR-0008 fixes 0.15 as the position a demand-controlled damper holds
 * during occupied hours, and therefore also the position a damper stuck shut sticks at.
 */
public final class BuildingSimulation {

    private final ClockPort clock;
    private final Duration tick;
    private final OccupancySource occupancy;
    private final SensorModel sensors;
    private final FaultInjector faults = new FaultInjector();
    private final Map<ZoneId, VentilationZone> zones = new LinkedHashMap<>();
    private final Map<ZoneId, Double> trueCo2 = new LinkedHashMap<>();
    private AirHandlingUnit airHandler = Topology.airHandler();

    /** The simulated instant the plant's state has been integrated to. */
    private Instant advancedTo;

    /**
     * @param tick the simulated span one {@link #advance()} covers. How much real time that costs
     *             is the clock's business, not this class's.
     */
    public BuildingSimulation(ClockPort clock, Duration tick, long seed) {
        this(clock, tick, seed, new OccupancyScheduler(seed));
    }

    /**
     * With an occupancy source of the caller's choosing, for a scenario that needs a known
     * occupant step rather than the schedules' plausible day.
     */
    public BuildingSimulation(
            ClockPort clock, Duration tick, long seed, OccupancySource occupancy) {
        this.clock = clock;
        this.tick = tick;
        this.advancedTo = clock.now();
        this.occupancy = occupancy;
        // A stream of its own, so changing a schedule does not reshape the measurement noise.
        this.sensors = new SensorModel(seed + 1);
        for (VentilationZone zone : Topology.zones()) {
            zones.put(zone.id(), zone);
            trueCo2.put(zone.id(), Topology.OUTDOOR.value());
        }
    }

    /** What the damper actuator was last told to do, fault or no fault. */
    public void commandDamper(ZoneId zone, DamperPosition position) {
        zones.put(zone, zone(zone).withSupervisoryCommand(position));
    }

    public void commandFanSpeed(FanSpeed speed) {
        airHandler = airHandler.atSpeed(speed);
    }

    /** Schedules a fault. The evaluation harness injects through here (T-14, R-1). */
    public void inject(InjectedFault fault) {
        faults.inject(fault);
    }

    public void injectAll(Collection<InjectedFault> injected) {
        faults.injectAll(injected);
    }

    /** Every scheduled fault, for an operator asking what the plant has been told to do. */
    public List<InjectedFault> injectedFaults() {
        return faults.scheduled();
    }

    /** Drops every scheduled fault and reports how many there were to drop. */
    public int clearFaults() {
        return faults.clear();
    }

    /**
     * Drops the scheduled faults of one kind on one target and reports how many went — the
     * physical repair behind an operator marking one case serviced, which leaves the other rooms'
     * faults running.
     *
     * @param zone the room to clear, or {@code null} for a plant-wide kind
     */
    public int clearFaults(FaultKind kind, ZoneId zone) {
        return faults.remove(kind, zone);
    }

    /**
     * Advances the building by exactly one tick of simulated time and reports what happened, true
     * state and measured state separately. This is the method the evaluation harness steps in a
     * loop.
     */
    public PhysicsSnapshot advance() throws InterruptedException {
        clock.waitFor(tick);
        Instant at = clock.now();
        // The span integrated is the span the CLOCK moved, not the configured tick. On a clock
        // that advances by exactly one tick per wait — a SimulatedClock, or a ScaledClock keeping
        // up — the two are the same number and this is the fixed tick it always was. They differ
        // only where the clock moved by something else: a demo container joining a shared
        // simulated timeline some seconds after its epoch finds its first wait has already
        // carried it minutes of the building's day, and integrating the configured tick instead
        // would leave the plant permanently behind the instant it stamps its own state with.
        Duration span = Duration.between(advancedTo, at);

        long steps = Math.max(1, span.toSeconds());
        Duration step = span.dividedBy(steps);
        double stepSeconds = step.toNanos() / 1e9;
        for (long index = 1; index < steps; index++) {
            integrate(advancedTo.plus(step.multipliedBy(index)), stepSeconds);
        }
        advancedTo = at;
        // The last step lands exactly on the tick's instant, and its state is the snapshot.
        return integrate(at, stepSeconds);
    }

    private PhysicsSnapshot integrate(Instant at, double stepSeconds) {
        double blockage = faults.filterBlockage(at);
        Map<ZoneId, DamperPosition> applied = new LinkedHashMap<>();
        List<VentilationZone> effective = new ArrayList<>();
        for (ZoneId id : Topology.ZONE_IDS) {
            VentilationZone commanded = zone(id);
            VentilationZone zone = commanded.withSupervisoryCommand(
                    faults.appliedPosition(id, commanded.commandedPosition(), at));
            applied.put(id, zone.commandedPosition());
            effective.add(zone);
        }

        AirflowRate fanFlow = airHandler.deliveredFlow(blockage);
        Map<ZoneId, AirflowRate> flows = FlowSplit.across(effective, fanFlow);
        Map<ZoneId, Integer> headCounts = occupancy.headCounts(at);

        Map<ZoneId, Ppm> trueValues = new LinkedHashMap<>();
        Map<ZoneId, Ppm> measuredValues = new LinkedHashMap<>();
        for (VentilationZone zone : effective) {
            ZoneId id = zone.id();
            double next = Co2MassBalance.nextPpm(
                    trueCo2.get(id),
                    zone.volumeCubicMetres(),
                    headCounts.get(id),
                    flows.get(id).cubicMetresPerSecond(),
                    Topology.OUTDOOR.value(),
                    stepSeconds);
            trueCo2.put(id, next);
            trueValues.put(id, Ppm.of(next));
            measuredValues.put(id, sensors.publish(next, faults.sensorErrorPpm(id, at)));
        }

        return new PhysicsSnapshot(at, trueValues, measuredValues, flows, headCounts, applied,
                airHandler.commandedSpeed(), fanFlow,
                sensors.publishFanPower(airHandler.power(blockage).watts()), blockage,
                faults.activeAt(at));
    }

    private VentilationZone zone(ZoneId id) {
        VentilationZone zone = zones.get(id);
        if (zone == null) {
            throw new IllegalArgumentException("not one of the six simulated rooms: " + id);
        }
        return zone;
    }
}
