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

/**
 * The {@code co2-sensor} device process: every {@code SAMPLE_SECONDS} it reads the six rooms'
 * CO2 and the air handler's fan power from BuildSim and publishes them to the broker.
 *
 * <p>Flat by decision, not by omission: ADR-0005 gives the boundary/control/entity split to the
 * four structured services and keeps the device processes flat, because uniformity between
 * components differing by an order of magnitude in complexity is cost without benefit.
 *
 * <p>Two clocks, deliberately. {@code SAMPLE_SECONDS} paces the loop in real time, because that
 * is how often BuildSim is worth asking; the {@code ObservedAt} on every reading and the
 * staleness window it is judged against come from the scaled clock, because that is the time the
 * plant and every consumer downstream are living in. At {@code TIME_SCALE=1} the two are the same
 * object. See {@link ScaledClock}.
 */
public final class Co2SensorMain {

    private static final Duration HEALTH_RETRY = Duration.ofSeconds(2);
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

    private static final Logger LOG = Logger.getLogger(Co2SensorMain.class.getName());

    private Co2SensorMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        String buildSimUrl = env("BUILDSIM_URL", "http://localhost:9090");
        String brokerUrl = env("BROKER_URL", "tcp://localhost:1883");
        Duration sampleInterval = Duration.ofSeconds(Long.parseLong(env("SAMPLE_SECONDS", "1")));
        // One real sample interval carries TIME_SCALE of them in the building's day, and every
        // span this process reasons about — the staleness window, the ObservedAt it stamps — is a
        // span of the building's day. The loop still sleeps the real interval.
        Duration simulatedInterval =
                sampleInterval.multipliedBy(ScaledClock.timeScaleFromEnvironment());

        ClockPort realTime = new SystemClock();
        ClockPort clock = ScaledClock.fromEnvironment(realTime);
        BuildSimAdapter buildSim = new BuildSimAdapter(buildSimUrl);
        awaitBuildSim(buildSim, realTime, buildSimUrl);

        CountDownLatch stopped = new CountDownLatch(1);
        LOG.info("sampling every " + sampleInterval + " of real time, " + simulatedInterval
                + " of simulated time, towards broker " + brokerUrl
                + "; simulated now " + clock.now());
        try (MqttAdapter broker = new MqttAdapter(brokerUrl, "co2-sensor")) {
            Co2Sampler sampler = new Co2Sampler(buildSim, broker, clock, simulatedInterval);
            installShutdownHook(sampler, Thread.currentThread(), stopped);
            while (!sampler.isStopping()) {
                sampler.sampleOnce();
                realTime.waitFor(sampleInterval);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // Not the logger: java.util.logging closes its handlers in a shutdown hook of its
            // own, racing this one, so a logged line here is usually lost. The broker has been
            // disconnected by the time this prints, which is the evidence that the container
            // stopped rather than died.
            System.out.println("co2-sensor stopped");
            stopped.countDown();
        }
    }

    /**
     * BuildSim owns the sensors this process reads, so there is nothing to do until it answers.
     * Retrying rather than failing fast keeps {@code docker compose up} order-independent.
     */
    private static void awaitBuildSim(BuildSimAdapter buildSim, ClockPort clock, String url)
            throws InterruptedException {
        while (!buildSim.isHealthy()) {
            LOG.info("waiting for BuildSim at " + url);
            clock.waitFor(HEALTH_RETRY);
        }
    }

    /**
     * SIGTERM must end the sample loop and disconnect from the broker, so {@code docker compose
     * down} is a shutdown and not a kill. The hook waits for the loop to unwind, because the JVM
     * exits as soon as every hook returns.
     *
     * <p>The sampler is silenced before the interrupt, not after: the interrupt is what makes the
     * in-flight reads fail, and a sampler that still published would turn an orderly stop into a
     * burst of MISSING readings.
     *
     * <p>Installed once the sampler exists rather than at the top of {@code main}, so that the
     * hook has something to silence. Nothing before this point publishes anything.
     */
    private static void installShutdownHook(Co2Sampler sampler, Thread loop,
            CountDownLatch stopped) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            sampler.stop();
            loop.interrupt();
            try {
                stopped.await(SHUTDOWN_GRACE.toSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.log(Level.WARNING, "interrupted while awaiting clean shutdown", e);
            }
        }, "co2-sensor-shutdown"));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
