package se.ltu.d7065e.physics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import se.ltu.d7065e.physics.boundary.BuildSimStateAdapter;
import se.ltu.d7065e.physics.boundary.FaultControlSurface;
import se.ltu.d7065e.shared.boundary.ScaledClock;
import se.ltu.d7065e.physics.control.BuildingSimulation;
import se.ltu.d7065e.physics.control.FaultSpecParser;
import se.ltu.d7065e.physics.entity.InjectedFault;
import se.ltu.d7065e.physics.entity.PhysicsSnapshot;
import se.ltu.d7065e.shared.boundary.BuildSimAdapter;
import se.ltu.d7065e.shared.boundary.SystemClock;
import se.ltu.d7065e.shared.control.ClockPort;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * Composition root: wires the clock, BuildSim and the simulation, and runs the tick loop. It
 * sits at the component root rather than in {@code boundary} because the module's manifest
 * names {@code se.ltu.d7065e.physics.Main}, which is the convention the other plain-Java
 * containers follow.
 *
 * <p>Environment: {@code BUILDSIM_URL}, {@code TICK_SECONDS} (default 1), {@code SEED}
 * (default 42), and either {@code FAULT_SPEC} or {@code FAULT_SPEC_FILE} to inject faults into
 * the containerised demo — the format is on {@link FaultSpecParser}.
 *
 * <p>{@code CONTROL_PORT} (default 8081) is the port the fault control surface listens on, which
 * is the same scenario grammar reachable at runtime: a fault can be injected into, and taken out
 * of, a plant that is already running, so a repair can be watched instead of restarted. See
 * {@link FaultControlSurface}.
 *
 * <p>Two more make the demo watchable, and both default to present behaviour:
 * <ul>
 *   <li>{@code TIME_SCALE} (default 1) — simulated seconds per real second. Each real tick of
 *       {@code TICK_SECONDS} then advances the building by {@code TICK_SECONDS x TIME_SCALE} of
 *       simulated time, integrated in one-second steps, with the schedules and the fault onsets
 *       read at those simulated instants. See {@link ScaledClock}.</li>
 *   <li>{@code START_AT} (default: now) — the ISO-8601 instant of the first tick, so a demo can
 *       begin just before the morning arrivals instead of in an empty building at whatever hour
 *       the container happened to start.</li>
 *   <li>{@code REAL_EPOCH} (default: this process's start) — the real instant the simulated
 *       timeline is anchored on, supplied identically to each of the seven project-authored
 *       containers so that they agree on what time it is. The other three compose services --
 *       BuildSim, the broker and the journal -- hold no simulated clock and are not given one. Simulated time passing between the epoch and this container's first
 *       tick is a backlog the loop traverses rather than skips, so {@code START_AT} is honoured
 *       exactly whenever the container starts.</li>
 * </ul>
 *
 * <p>Both change what {@code T+<seconds>} in a {@code FAULT_SPEC} means: the onset is counted in
 * simulated seconds from {@code START_AT}, not in real seconds from container start. That is the
 * only reading of it that lets a scenario be aimed at a point in the occupancy schedule.
 */
public final class Main {

    private static final Duration HEALTH_RETRY = Duration.ofSeconds(2);
    private static final int LOG_EVERY_TICKS = 60;

    public static void main(String[] args) throws InterruptedException, IOException {
        String url = env("BUILDSIM_URL", "http://localhost:9090");
        Duration realTick = Duration.ofSeconds(Long.parseLong(env("TICK_SECONDS", "1")));
        long timeScale = ScaledClock.timeScaleFromEnvironment();
        Duration simulatedTick = realTick.multipliedBy(timeScale);
        ClockPort realTime = new SystemClock();
        Instant startAt = ScaledClock.startAtFromEnvironment(realTime.now());
        long seed = Long.parseLong(env("SEED", "42"));

        BuildSimAdapter buildSim = new BuildSimAdapter(url);
        // The health wait is on real time: waiting on the scaled clock would spend simulated
        // minutes of the building's day on BuildSim's start-up.
        awaitHealthy(buildSim, realTime, url);
        buildSim.registerTopology();
        System.out.println("registered " + Topology.ZONE_IDS.size() + " rooms and "
                + Topology.AIR_HANDLER + " with BuildSim");

        // The epoch is read after the health wait, not before, so that an unscaled run with no
        // REAL_EPOCH still puts its first tick on START_AT rather than however long BuildSim took
        // to answer after it. With a REAL_EPOCH the epoch is the shared one and the wait is simply
        // part of the backlog the loop then traverses.
        Instant realEpoch = ScaledClock.realEpochFromEnvironment(realTime.now());
        ClockPort clock =
                new ScaledClock(realTime, startAt.minus(simulatedTick), realEpoch, timeScale);
        BuildingSimulation simulation = new BuildingSimulation(clock, simulatedTick, seed);
        List<InjectedFault> scheduled = scheduledFaults(startAt);
        simulation.injectAll(scheduled);
        scheduled.forEach(fault -> System.out.println("scheduled fault " + fault));

        BuildSimStateAdapter adapter = new BuildSimStateAdapter(buildSim, buildSim, simulation);
        FaultControlSurface control = new FaultControlSurface(
                simulation, clock, Integer.parseInt(env("CONTROL_PORT", "8081")));
        control.start();
        System.out.println("fault control surface on port " + control.port()
                + ", accepting fault injection and removal at /faults");

        System.out.println("simulating from " + startAt + " at a " + realTick.toSeconds()
                + " s real tick carrying " + simulatedTick.toSeconds()
                + " s of simulated time, time scale " + timeScale + ", seed " + seed
                + ", anchored on REAL_EPOCH " + realEpoch + " (simulated now " + clock.now() + ")");
        try {
            for (long ticks = 0; !Thread.currentThread().isInterrupted(); ticks++) {
                PhysicsSnapshot snapshot = adapter.exchange();
                if (ticks % LOG_EVERY_TICKS == 0) {
                    System.out.println(line(snapshot));
                }
            }
        } finally {
            // The JDK's HTTP dispatcher thread is not a daemon, so an interrupted loop would
            // otherwise return from main and leave the process alive on the listener alone.
            control.stop();
        }
    }

    private static void awaitHealthy(BuildSimAdapter buildSim, ClockPort clock, String url)
            throws InterruptedException {
        while (!buildSim.isHealthy()) {
            System.out.println("waiting for BuildSim at " + url);
            clock.waitFor(HEALTH_RETRY);
        }
    }

    /**
     * The scheduled faults, with {@code T+} onsets counted from {@code start} — the first tick's
     * simulated instant — so a {@code FAULT_SPEC} can be aimed at a point in the occupancy day.
     */
    private static List<InjectedFault> scheduledFaults(Instant start) throws IOException {
        String file = System.getenv("FAULT_SPEC_FILE");
        if (file != null && !file.isBlank()) {
            return FaultSpecParser.parse(Files.readString(Path.of(file)), start);
        }
        String spec = System.getenv("FAULT_SPEC");
        return spec == null || spec.isBlank() ? List.of() : FaultSpecParser.parse(spec, start);
    }

    private static String line(PhysicsSnapshot snapshot) {
        StringBuilder text = new StringBuilder(snapshot.at().toString());
        for (ZoneId zone : Topology.ZONE_IDS) {
            text.append(" %s %.0f/%.0f ppm n=%d".formatted(zone.value(),
                    snapshot.trueCo2(zone).value(), snapshot.measuredCo2(zone).value(),
                    snapshot.occupancy(zone)));
        }
        return text.append(" fan ").append(snapshot.fanSpeed())
                .append(' ').append(snapshot.fanPower())
                .append(" beta ").append(snapshot.filterBlockage()).toString();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
