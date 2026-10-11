package se.ltu.d7065e.devices;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import se.ltu.d7065e.shared.boundary.BuildSimAdapter;
import se.ltu.d7065e.shared.boundary.MqttAdapter;
import se.ltu.d7065e.shared.boundary.ScaledClock;
import se.ltu.d7065e.shared.boundary.SystemClock;
import se.ltu.d7065e.shared.control.ClockPort;
import se.ltu.d7065e.shared.event.CommandIssued;
import se.ltu.d7065e.shared.event.DomainEvent;
import se.ltu.d7065e.shared.event.FanSpeedCommanded;
import se.ltu.d7065e.shared.event.Topics;

/**
 * The {@code damper-actuator} device process: it subscribes to damper and fan-speed commands and
 * is the only process that writes those actuator states to BuildSim.
 *
 * <p>Flat by decision (ADR-0005): the device processes are around a hundred lines each and do not
 * carry the boundary/control/entity split the four structured services carry.
 */
public final class DamperActuatorMain {

    private static final Duration HEALTH_RETRY = Duration.ofSeconds(2);
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

    private static final Logger LOG = Logger.getLogger(DamperActuatorMain.class.getName());

    private DamperActuatorMain() {
    }

    static void main(String[] args) throws InterruptedException {
        String buildSimUrl = env("BUILDSIM_URL", "http://localhost:9090");
        String brokerUrl = env("BROKER_URL", "tcp://localhost:1883");

        // Two clocks, and the split is the point. The retry backoff below is a real-time wait on
        // a real HTTP endpoint, so it stays on real time whatever TIME_SCALE says; the simulated
        // clock is resolved so that this container reports the same timeline as the other six and
        // an operator can see all seven agree in the logs. This process stamps nothing, which is
        // why the simulated clock is not handed to the applier: it would only divide the backoff.
        ClockPort realTime = new SystemClock();
        ClockPort clock = ScaledClock.fromEnvironment(realTime);
        BuildSimAdapter buildSim = new BuildSimAdapter(buildSimUrl);
        awaitBuildSim(buildSim, realTime, buildSimUrl);

        CountDownLatch stop = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        installShutdownHook(stop, stopped);

        try (MqttAdapter broker = new MqttAdapter(brokerUrl, "damper-actuator")) {
            HvacCommandApplier applier = new HvacCommandApplier(buildSim, realTime);
            broker.subscribe(Topics.ALL_DAMPER_COMMANDS, event -> handle(event, applier));
            broker.subscribe(Topics.ALL_FAN_SPEED_COMMANDS, event -> handle(event, applier));
            LOG.info("applying commands from " + brokerUrl + " to BuildSim at " + buildSimUrl
                    + "; simulated now " + clock.now());
            stop.await();
        } finally {
            // Not the logger: java.util.logging closes its handlers in a shutdown hook of its
            // own, racing this one, so a logged line here is usually lost. The broker has been
            // disconnected by the time this prints, which is the evidence that the container
            // stopped rather than died.
            System.out.println("damper-actuator stopped");
            stopped.countDown();
        }
    }

    /**
     * One handler per subscription, and nothing thrown from it: this runs on the broker client's
     * callback thread, and letting an exception out of it would deafen the device for every later
     * command rather than for the one that was bad.
     */
    private static void handle(DomainEvent event, HvacCommandApplier applier) {
        try {
            switch (event) {
                case CommandIssued damper -> applier.onDamperCommand(damper);
                case FanSpeedCommanded fan -> applier.onFanSpeedCommand(fan);
                default -> LOG.warning("ignoring event on a command topic: " + event);
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "failed to handle " + event, e);
        }
    }

    private static void awaitBuildSim(BuildSimAdapter buildSim, ClockPort clock, String url)
            throws InterruptedException {
        while (!buildSim.isHealthy()) {
            LOG.info("waiting for BuildSim at " + url);
            clock.waitFor(HEALTH_RETRY);
        }
    }

    /**
     * SIGTERM releases the wait and unsubscribes cleanly, so {@code docker compose down} is a
     * shutdown and not a kill. The hook waits for that to finish, because the JVM exits as soon
     * as every hook returns.
     */
    private static void installShutdownHook(CountDownLatch stop, CountDownLatch stopped) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stop.countDown();
            try {
                stopped.await(SHUTDOWN_GRACE.toSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.log(Level.WARNING, "interrupted while awaiting clean shutdown", e);
            }
        }, "damper-actuator-shutdown"));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
