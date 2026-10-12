package se.ltu.d7065e.diagnosis.control;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import se.ltu.d7065e.diagnosis.boundary.DiscardingJournal;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.shared.boundary.SimulatedClock;
import se.ltu.d7065e.shared.event.CommandIssued;
import se.ltu.d7065e.shared.event.FanPowerObserved;
import se.ltu.d7065e.shared.event.ReadingObserved;
import se.ltu.d7065e.ventilation.entity.AirHandlingUnit;
import se.ltu.d7065e.ventilation.entity.CommandId;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.FanPower;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.Ppm;
import se.ltu.d7065e.ventilation.entity.Quality;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * Drives {@link FaultMonitor} against {@link BuildingPhysics} on a {@link SimulatedClock}, so a
 * simulated week of six rooms runs in a unit test with no broker, no database and no wall clock.
 * This is the shape the evaluation harness should take for R-1, R-3, R-6 and R-7.
 */
final class MonitorHarness {

    private static final double COMFORT_OPEN_PPM = 800;
    private static final double COMFORT_CLOSE_PPM = 600;
    private static final double OPEN_POSITION = 0.80;

    private final SimulatedClock clock;
    private final BuildingPhysics physics;
    private final RecordingPublisher events = new RecordingPublisher();
    private final FaultMonitor monitor;
    private final MonitorParameters parameters;
    private final Map<ZoneId, Double> lastCommanded = new LinkedHashMap<>();

    private boolean innerLoopRunning = true;
    private boolean readingsFlowing = true;
    private Quality quality = Quality.GOOD;

    MonitorHarness(long seed, double sigmaPpm, MonitorParameters parameters) {
        this(seed, sigmaPpm, parameters, Topology.airHandler());
    }

    MonitorHarness(long seed, double sigmaPpm, MonitorParameters parameters, AirHandlingUnit ahu) {
        this.clock = new SimulatedClock(Instant.parse("2026-10-05T00:00:00Z"));
        this.physics = new BuildingPhysics(seed, sigmaPpm, ahu);
        this.parameters = parameters;
        this.monitor = new FaultMonitor(physics.zones(), ahu, parameters, clock,
                events, new DiscardingJournal());
        for (ZoneId zone : Topology.ZONE_IDS) {
            lastCommanded.put(zone, Topology.MINIMUM_POSITION.value());
        }
    }

    static MonitorHarness standard(long seed) {
        return new MonitorHarness(seed, 30.0, MonitorParameters.defaults());
    }

    /** The same building behind a differently sized air handler; see {@link BuildingPhysics}. */
    static MonitorHarness withPlant(long seed, AirHandlingUnit ahu) {
        return new MonitorHarness(seed, 30.0, MonitorParameters.defaults(), ahu);
    }

    /** The same building with the lower CUSUM arm set, which is the T-32 section 5 experiment. */
    static MonitorHarness withLowerArm(long seed, LowerArm arm) {
        return new MonitorHarness(seed, 30.0, MonitorParameters.defaults().withLowerArm(arm));
    }

    /** The same building with the classification ordering set, which is the T-35 experiment. */
    static MonitorHarness withClassifyWhen(long seed, ClassifyWhen when) {
        return new MonitorHarness(seed, 30.0, MonitorParameters.defaults().withClassifyWhen(when));
    }

    FaultMonitor monitor() {
        return monitor;
    }

    BuildingPhysics physics() {
        return physics;
    }

    RecordingPublisher events() {
        return events;
    }

    Instant now() {
        return clock.now();
    }

    void innerLoopRunning(boolean running) {
        this.innerLoopRunning = running;
    }

    /** Kills the reading stream, which is R-4's "fault injection: kill co2-sensor". */
    void readingsFlowing(boolean flowing) {
        this.readingsFlowing = flowing;
    }

    void quality(Quality quality) {
        this.quality = quality;
    }

    /** One monitor cycle: physics, readings, the inner loop's commands, then the cycle. */
    void tick() {
        clock.advance(parameters.cycleInterval());
        physics.advance(parameters.cycleInterval());
        Instant now = clock.now();
        if (readingsFlowing) {
            for (ZoneId zone : Topology.ZONE_IDS) {
                monitor.accept(new ReadingObserved(zone, Ppm.of(physics.measuredPpm(zone)),
                        ObservedAt.of(now), quality));
            }
            monitor.accept(new FanPowerObserved(Topology.AIR_HANDLER,
                    FanPower.ofWatts(physics.fanPowerWatts()), ObservedAt.of(now), quality));
        }
        if (innerLoopRunning) {
            runInnerLoop();
        }
        monitor.runCycle();
    }

    void tickFor(Duration duration) {
        long cycles = duration.getSeconds() / parameters.cycleInterval().getSeconds();
        for (long cycle = 0; cycle < cycles; cycle++) {
            tick();
        }
    }

    /** Ticks until the predicate holds or the budget runs out; returns the cycles it took. */
    int tickUntil(Duration budget, java.util.function.BooleanSupplier until) {
        long cycles = budget.getSeconds() / parameters.cycleInterval().getSeconds();
        for (int cycle = 1; cycle <= cycles; cycle++) {
            tick();
            if (until.getAsBoolean()) {
                return cycle;
            }
        }
        return -1;
    }

    /**
     * A command from outside the inner loop — the supervisor's compensation or probe, or a test's
     * deliberate step. The monitor sees exactly what it would see on {@code commands/+/damper}.
     */
    void command(ZoneId zone, double position, String reason) {
        physics.command(zone, position);
        lastCommanded.put(zone, position);
        monitor.accept(new CommandIssued(zone, DamperPosition.of(position),
                ObservedAt.of(clock.now()), reason, CommandId.newId()));
    }

    /**
     * The hysteresis inner loop of {@code dcv-controller}, reproduced here only so that the
     * monitor has commands to judge. It is not under test.
     */
    private void runInnerLoop() {
        for (ZoneId zone : Topology.ZONE_IDS) {
            if (monitor.stateOf(zone) == MonitorState.COMPENSATING) {
                continue;
            }
            double measured = physics.truePpm(zone);
            double current = lastCommanded.get(zone);
            double wanted = current;
            if (measured > COMFORT_OPEN_PPM) {
                wanted = OPEN_POSITION;
            } else if (measured < COMFORT_CLOSE_PPM) {
                wanted = Topology.MINIMUM_POSITION.value();
            }
            if (Math.abs(wanted - current) > 1e-9) {
                command(zone, wanted, "dcv hysteresis");
            }
        }
    }
}
