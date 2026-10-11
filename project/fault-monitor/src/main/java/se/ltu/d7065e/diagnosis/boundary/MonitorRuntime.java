package se.ltu.d7065e.diagnosis.boundary;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import se.ltu.d7065e.diagnosis.control.FaultMonitor;
import se.ltu.d7065e.diagnosis.control.LowerArm;
import se.ltu.d7065e.diagnosis.control.MonitorParameters;
import se.ltu.d7065e.shared.boundary.FileServiceStateAdapter;
import se.ltu.d7065e.shared.boundary.MqttAdapter;
import se.ltu.d7065e.shared.boundary.ScaledClock;
import se.ltu.d7065e.shared.boundary.SystemClock;
import se.ltu.d7065e.shared.boundary.TimescaleJournalAdapter;
import se.ltu.d7065e.shared.control.ClockPort;
import se.ltu.d7065e.shared.control.ResidualJournalPort;
import se.ltu.d7065e.shared.control.ServiceStatePort;
import se.ltu.d7065e.shared.event.Topics;
import se.ltu.d7065e.ventilation.entity.Topology;

/**
 * The only class in this component that knows there is a broker, a database and a framework
 * (ADR-0005). It wires the adapters to the diagnosis core, runs the cycle on the scheduler, and
 * holds nothing of its own.
 *
 * <h2>Subscriptions, and what is deliberately missing</h2>
 *
 * In: {@code readings/+/co2}, {@code readings/+/fan-power}, {@code commands/+/damper} — the three
 * the brief named — plus {@code commands/+/fan-speed} and {@code service/+}, which it did not. The
 * fourth is read-only like the rest and the reason it is necessary is in
 * {@link se.ltu.d7065e.diagnosis.control.AhuFlowModel#fanSpeedBelieved}: the supervisor owns the
 * fan, and a monitor that did not know what speed had been set would read every compensation as a
 * blocked filter through the affinity law. The fifth is the one input that is not an observation:
 * {@link ServiceRequests} carries an operator's repair report, which is the only thing that clears
 * a latched case and had no transport at all before (D-10). The monitor is the right subscriber
 * because it is the component that holds the case; nothing else can refuse a report that names a
 * case which is not open or not latched.
 * Out: {@code residuals/{zone}} and {@code faults/{zone}}.
 *
 * <p>There is no publish to a command topic anywhere in this component, and that is the point.
 * {@code fault-monitor} raises events and never commands (plan section 3): the inner loop owns
 * {@code commands/{zone}/damper}, the supervisor owns {@code commands/{ahu}/fan-speed}, and a
 * monitor that also commanded would be a third writer to actuators it is simultaneously judging.
 * Even T-18's active probe, which genuinely needs a damper to move, is published as a request on
 * {@code faults/{zone}} for the supervisor to execute.
 *
 * <p>The command subscription is what makes the observer possible rather than a nicety: the
 * residual is a statement about a commanded step, so the monitor has to see the commands it is
 * judging, and under ADR-0008's shared air handler it has to see <em>all six</em> rooms'
 * commands, because opening one damper takes air from the other five.
 */
@ApplicationScoped
public class MonitorRuntime {

    private static final Logger LOG = Logger.getLogger(MonitorRuntime.class);
    private static final String CLIENT_ID = "fault-monitor";

    private final String brokerUrl;
    private final Optional<String> jdbcUrl;
    private final String jdbcUser;
    private final String jdbcPassword;
    private final Optional<String> statePath;
    private final MonitorParameters parameters;

    private MqttAdapter broker;
    private ResidualJournalPort journal;
    private FaultMonitor monitor;
    private DurableDiagnosis diagnosis;

    @Inject
    public MonitorRuntime(
            @ConfigProperty(name = "broker.url", defaultValue = "tcp://localhost:1883") String brokerUrl,
            @ConfigProperty(name = "jdbc.url") Optional<String> jdbcUrl,
            @ConfigProperty(name = "jdbc.user", defaultValue = "ventilation") String jdbcUser,
            @ConfigProperty(name = "jdbc.password", defaultValue = "ventilation") String jdbcPassword,
            @ConfigProperty(name = "service.state.path") Optional<String> statePath,
            @ConfigProperty(name = "cusum.k", defaultValue = "30") double cusumK,
            @ConfigProperty(name = "cusum.h", defaultValue = "150") double cusumH,
            @ConfigProperty(name = "cycle.seconds", defaultValue = "60") long cycleSeconds,
            @ConfigProperty(name = "cusum.lower-arm", defaultValue = "GUARDED") LowerArm lowerArm) {
        this.brokerUrl = brokerUrl;
        this.jdbcUrl = jdbcUrl;
        this.jdbcUser = jdbcUser;
        this.jdbcPassword = jdbcPassword;
        this.statePath = statePath;
        // cycle.seconds is how often the scheduler fires in REAL time; the cycle interval the
        // observer integrates over and the staleness window derived from it are spans of the
        // building's day, so both carry TIME_SCALE. Under the 60x demo profile CYCLE_SECONDS=1
        // therefore still means one cycle per 60 simulated seconds, which is what ADR-0008 tuned
        // k and h against. At TIME_SCALE=1 nothing here changes.
        this.parameters = MonitorParameters.defaults()
                .withCycleInterval(Duration.ofSeconds(cycleSeconds)
                        .multipliedBy(ScaledClock.timeScaleFromEnvironment()))
                .withCusum(cusumK, cusumH)
                .withLowerArm(lowerArm);
    }

    /**
     * Opening the broker connection here rather than in the constructor keeps a broker that is
     * not up yet from turning into a bean-construction failure with a stack trace that names CDI
     * instead of the broker.
     *
     * <p>What the last run believed is restored here, and before the five subscriptions rather
     * than after them, because the order matters and nothing else enforces it: the broker connects
     * with a clean session and a random client id, so no backlog is waiting to be raced — but there
     * is also no second chance at a message published in the gap, and a recovery that ran after the
     * first service report had arrived would resurrect a case a technician had just closed.
     */
    void start(@Observes StartupEvent startup) {
        broker = new MqttAdapter(brokerUrl, CLIENT_ID);
        journal = openJournal();
        ClockPort clock = ScaledClock.fromEnvironment(new SystemClock());
        monitor = new FaultMonitor(Topology.zones(), Topology.airHandler(), parameters,
                clock, broker, journal);
        diagnosis = new DurableDiagnosis(monitor, openStateStore());
        diagnosis.resume();
        broker.subscribe(Topics.ALL_CO2_READINGS, diagnosis::accept);
        broker.subscribe(Topics.ALL_FAN_POWER, diagnosis::accept);
        broker.subscribe(Topics.ALL_DAMPER_COMMANDS, diagnosis::accept);
        broker.subscribe(Topics.ALL_FAN_SPEED_COMMANDS, diagnosis::accept);
        broker.subscribe(Topics.ALL_SERVICE_REPORTS, diagnosis::serviced);
        LOG.infof("fault-monitor watching %d zones, k=%.0f ppm, h=%.0f ppm, lower arm %s, cycle %ds "
                        + "of simulated time, stale after %ds, simulated now %s, journal %s, "
                        + "latched diagnoses %s",
                Topology.ZONE_IDS.size(), parameters.cusumK(), parameters.cusumH(),
                parameters.lowerArm(),
                parameters.cycleInterval().getSeconds(),
                parameters.staleLimit().getSeconds(), clock.now(),
                journal instanceof DiscardingJournal ? "disabled" : "timescaledb",
                statePath.map("survive a restart, in %s"::formatted)
                        .orElse("do not survive a restart"));
    }

    void stop(@Observes ShutdownEvent shutdown) {
        if (broker != null) {
            broker.close();
        }
        if (journal instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                LOG.warn("journal did not close cleanly", e);
            }
        }
    }

    @Scheduled(every = "{monitor.cycle}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void cycle() {
        if (diagnosis == null) {
            return;
        }
        diagnosis.runCycle();
    }

    /** For the health check, which reports BLIND zones as unavailable (R-4). */
    public FaultMonitor monitor() {
        return monitor;
    }

    /**
     * R-4's "run without a database": an unset {@code JDBC_URL} skips journalling instead of
     * refusing to start, so the evaluation harness and a laptop run need no Timescale.
     */
    private ResidualJournalPort openJournal() {
        if (jdbcUrl.isEmpty() || jdbcUrl.get().isBlank()) {
            LOG.info("JDBC_URL unset: residuals and decisions will not be journalled, so R-5's "
                    + "reconstruction is unavailable in this run");
            return new DiscardingJournal();
        }
        return new TimescaleJournalAdapter(jdbcUrl.get(), jdbcUser, jdbcPassword);
    }

    /**
     * Where this monitor keeps what it currently believes, so a restart resumes its latched
     * diagnoses instead of handing a confirmed fault a second exit (ADR-0012). An unset
     * {@code SERVICE_STATE_PATH} substitutes {@link SkippedState} and the monitor keeps nothing,
     * the same way an unset {@code JDBC_URL} means it records nothing — the store is an output of
     * its belief and an input to no diagnosis, so a deployment without a volume must not fail to
     * boot.
     */
    private ServiceStatePort openStateStore() {
        if (statePath.isEmpty() || statePath.get().isBlank()) {
            LOG.info("SERVICE_STATE_PATH unset: latched diagnoses will not survive a restart, "
                    + "which is the behaviour this component had before ADR-0012");
            return new SkippedState();
        }
        return new FileServiceStateAdapter(Path.of(statePath.get()));
    }
}
