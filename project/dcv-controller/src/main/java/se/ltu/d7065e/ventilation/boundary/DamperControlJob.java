package se.ltu.d7065e.ventilation.boundary;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import se.ltu.d7065e.shared.boundary.MqttAdapter;
import se.ltu.d7065e.shared.boundary.ScaledClock;
import se.ltu.d7065e.shared.boundary.SystemClock;
import se.ltu.d7065e.shared.boundary.TimescaleJournalAdapter;
import se.ltu.d7065e.shared.control.ResidualJournalPort;
import se.ltu.d7065e.shared.event.CommandIssued;
import se.ltu.d7065e.shared.event.DomainEvent;
import se.ltu.d7065e.shared.event.ReadingObserved;
import se.ltu.d7065e.shared.event.StrategyChanged;
import se.ltu.d7065e.shared.event.Topics;
import se.ltu.d7065e.ventilation.control.ControlSettings;
import se.ltu.d7065e.ventilation.control.DcvControlLoop;

/**
 * Wires the inner loop to the broker and the journal, and ticks it once per control interval.
 *
 * <p>Everything framework-shaped lives here: the Quarkus scheduler, MicroProfile Config, Paho
 * through {@link MqttAdapter} and JDBC through {@link TimescaleJournalAdapter}. The loop itself
 * is plain Java (ADR-0005).
 *
 * <p>The journal is optional. If {@code JDBC_URL} is unset the controller runs and commands
 * normally and simply does not write the {@code commands} rows, so the evaluation harness can
 * bring up a broker and the six rooms without a database. The broker is not optional: a
 * controller that cannot read CO2 or publish a command has nothing to do.
 */
@ApplicationScoped
public class DamperControlJob {

    /** Provenance in the {@code commands} table: the inner loop owns the damper rows. */
    static final String SOURCE = "dcv-controller";

    private static final Logger LOG = Logger.getLogger(DamperControlJob.class.getName());

    private final String brokerUrl;
    private final Optional<String> jdbcUrl;
    private final String jdbcUser;
    private final String jdbcPassword;
    private final DcvControlLoop loop;

    /**
     * Written once on the startup thread and read on the scheduler and Paho callback threads, so
     * both are volatile: without it those threads may never see the startup thread's writes.
     */
    private volatile MqttAdapter broker;

    /** Null when {@code JDBC_URL} is unset, which is a supported deployment and not a failure. */
    private volatile ResidualJournalPort journal;

    @Inject
    public DamperControlJob(
            @ConfigProperty(name = "broker.url") String brokerUrl,
            @ConfigProperty(name = "jdbc.url") Optional<String> jdbcUrl,
            @ConfigProperty(name = "jdbc.user") String jdbcUser,
            @ConfigProperty(name = "jdbc.password") String jdbcPassword,
            @ConfigProperty(name = "open.above.ppm") double openAbovePpm,
            @ConfigProperty(name = "close.below.ppm") double closeBelowPpm,
            @ConfigProperty(name = "control.seconds") long controlSeconds) {
        this.brokerUrl = brokerUrl;
        this.jdbcUrl = jdbcUrl;
        this.jdbcUser = jdbcUser;
        this.jdbcPassword = jdbcPassword;
        // control.seconds is a real loop period; everything the loop reasons about is a span of
        // the building's day, so the freshness limit derived from it is scaled by the same factor
        // the plant is. At TIME_SCALE=1 this is Duration.ofSeconds(controlSeconds) exactly and
        // fromEnvironment hands back the SystemClock unchanged.
        long timeScale = ScaledClock.timeScaleFromEnvironment();
        this.loop = new DcvControlLoop(
                ControlSettings.band(openAbovePpm, closeBelowPpm,
                        Duration.ofSeconds(controlSeconds).multipliedBy(timeScale)),
                ScaledClock.fromEnvironment(new SystemClock()));
    }

    void onStart(@Observes StartupEvent event) {
        jdbcUrl.ifPresentOrElse(
                url -> journal = new TimescaleJournalAdapter(url, jdbcUser, jdbcPassword),
                () -> LOG.info("JDBC_URL unset: commands are published but not journalled"));
        broker = new MqttAdapter(brokerUrl, SOURCE);
        broker.subscribe(Topics.ALL_CO2_READINGS, this::accept);
        broker.subscribe(Topics.ALL_STRATEGIES, this::accept);
        LOG.info(() -> "inner loop subscribed on " + brokerUrl + ", freshness limit "
                + loop.settings().freshnessLimit() + " of simulated time");
    }

    void onStop(@Observes ShutdownEvent event) {
        if (broker != null) {
            broker.close();
        }
        if (journal instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "could not close the journal", e);
            }
        }
    }

    /**
     * One control interval. Publishing is the last step: the loop has already applied the
     * position to its own state, so a broker that rejects the message loses that one command
     * rather than desynchronising the six rooms.
     */
    @Scheduled(every = "${control.seconds}s",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void control() {
        if (broker == null) {
            return;
        }
        for (CommandIssued command : loop.tick()) {
            broker.publish(Topics.damperCommand(command.zone()), command);
            if (journal != null) {
                journal.append(command, SOURCE);
            }
        }
    }

    /**
     * Both subscriptions share one handler because Paho allows one callback per client, so the
     * adapter hands every matching event to every registered filter.
     */
    private void accept(DomainEvent event) {
        switch (event) {
            case ReadingObserved reading -> loop.observe(reading);
            case StrategyChanged strategy -> loop.applyStrategy(strategy);
            default -> { }
        }
    }

    DcvControlLoop loop() {
        return loop;
    }

    boolean isSubscribed() {
        return broker != null;
    }

    boolean isJournalling() {
        return journal != null;
    }
}
