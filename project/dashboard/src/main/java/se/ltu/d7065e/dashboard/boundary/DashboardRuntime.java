package se.ltu.d7065e.dashboard.boundary;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import se.ltu.d7065e.dashboard.control.LiveProjection;
import se.ltu.d7065e.shared.boundary.BuildSimAdapter;
import se.ltu.d7065e.shared.boundary.MqttAdapter;
import se.ltu.d7065e.shared.boundary.ScaledClock;
import se.ltu.d7065e.shared.boundary.SystemClock;
import se.ltu.d7065e.shared.boundary.TimescaleJournalAdapter;
import se.ltu.d7065e.shared.control.BuildingOverlayPort;
import se.ltu.d7065e.shared.control.EventPublisherPort;
import se.ltu.d7065e.shared.control.ResidualJournalPort;
import se.ltu.d7065e.shared.event.Topics;

/**
 * Wires the live view to whatever infrastructure is actually present, and to nothing more.
 *
 * <p>Every dependency is optional on purpose. The dashboard is the one container an operator
 * looks at to decide whether the system is alive, so it must never be the reason the system
 * looks broken: an unset {@code BUILDSIM_URL} costs the 3D overlay, an unset {@code JDBC_URL}
 * costs case replay, an unreachable broker costs live values — and in every case the HTTP view
 * still starts and says which part is missing.
 *
 * <p>The broker connection is the one dependency that is not read-only, and only since D-10:
 * {@link ServiceResource} publishes an operator's repair report on {@code service/{zone}}. That is
 * the whole of what this container writes to the broker. It publishes on no reading, residual,
 * fault, strategy or command topic, so the view cannot contradict the components it watches and no
 * actuator gains a second commander.
 */
@ApplicationScoped
public class DashboardRuntime {

    private static final Logger LOG = Logger.getLogger(DashboardRuntime.class);
    private static final String CLIENT_ID = "dashboard";

    /**
     * The five event streams the view projects, plus the commanded fan speed: speed is never
     * measured anywhere in this system, so the command topic is the only place to read it and
     * {@code /api/state} is required to show it.
     */
    private static final List<String> SUBSCRIPTIONS = List.of(
            Topics.ALL_CO2_READINGS,
            Topics.ALL_RESIDUALS,
            Topics.ALL_FAULTS,
            Topics.ALL_STRATEGIES,
            Topics.ALL_FAN_POWER,
            Topics.ALL_FAN_SPEED_COMMANDS);

    private final Optional<String> brokerUrl;
    private final Optional<String> buildSimUrl;
    private final Optional<String> jdbcUrl;
    private final String jdbcUser;
    private final String jdbcPassword;
    private final LiveProjection projection;

    /**
     * Written on the startup and scheduler threads and read from JAX-RS worker and health-check
     * threads, so all three are volatile. {@link #connectStream()} stays synchronised because it
     * also needs the check-then-connect to happen once; volatile is what makes the result visible
     * to the readers, which are not synchronised and have nothing to serialise.
     */
    private volatile MqttAdapter stream;
    private volatile BuildSimAdapter buildSim;
    private volatile TimescaleJournalAdapter journal;

    public DashboardRuntime(
            @ConfigProperty(name = "dashboard.broker-url") Optional<String> brokerUrl,
            @ConfigProperty(name = "dashboard.buildsim-url") Optional<String> buildSimUrl,
            @ConfigProperty(name = "dashboard.jdbc-url") Optional<String> jdbcUrl,
            @ConfigProperty(name = "dashboard.jdbc-user") Optional<String> jdbcUser,
            @ConfigProperty(name = "dashboard.jdbc-password") Optional<String> jdbcPassword,
            @ConfigProperty(name = "dashboard.stale-seconds") long staleSeconds) {
        this.brokerUrl = brokerUrl;
        this.buildSimUrl = buildSimUrl;
        this.jdbcUrl = jdbcUrl;
        this.jdbcUser = jdbcUser.orElse("");
        this.jdbcPassword = jdbcPassword.orElse("");
        // The staleness window is a span of the building's day already — three missed 60 s sample
        // periods — so it is not scaled; the clock it is measured against is, because every
        // ObservedAt arriving on the broker is a simulated instant.
        this.projection = new LiveProjection(
                ScaledClock.fromEnvironment(new SystemClock()), Duration.ofSeconds(staleSeconds));
    }

    void onStart(@Observes StartupEvent event) {
        LOG.infof("live view reading simulated now %s, stale after %s",
                projection.now(), projection.stalenessWindow());
        connectStream();
        connectBuildSim();
        connectJournal();
    }

    public LiveProjection projection() {
        return projection;
    }

    /** Null when BuildSim is not configured, which disables layer publishing and nothing else. */
    public BuildingOverlayPort overlay() {
        return buildSim;
    }

    /** Null when no journal is configured, which {@code /api/cases/{id}} reports as such. */
    public ResidualJournalPort journal() {
        return journal;
    }

    /**
     * The broker as somewhere to publish, or null while there is no connection — which
     * {@link ServiceResource} has to report rather than swallow, because a repair report nobody
     * received is worse than a refused one.
     */
    public EventPublisherPort publisher() {
        return stream;
    }

    public boolean isBrokerConfigured() {
        return brokerUrl.isPresent();
    }

    public boolean isBrokerConnected() {
        return stream != null;
    }

    public boolean isOverlayConfigured() {
        return buildSimUrl.isPresent();
    }

    public boolean isJournalConfigured() {
        return jdbcUrl.isPresent();
    }

    /**
     * Connects and subscribes if that has not already succeeded. Called again on every layer
     * tick, because a broker that was not up when the dashboard booted must not leave the view
     * permanently deaf.
     */
    synchronized boolean connectStream() {
        if (stream != null || brokerUrl.isEmpty()) {
            return stream != null;
        }
        MqttAdapter adapter = null;
        try {
            adapter = new MqttAdapter(brokerUrl.get(), CLIENT_ID);
            for (String filter : SUBSCRIPTIONS) {
                adapter.subscribe(filter, projection::observe);
            }
            stream = adapter;
            LOG.infof("subscribed to %s on %s", SUBSCRIPTIONS, brokerUrl.get());
        } catch (RuntimeException e) {
            closeQuietly(adapter);
            LOG.warnf("broker %s unavailable, retrying on the next tick: %s",
                    brokerUrl.get(), e.getMessage());
        }
        return stream != null;
    }

    private void connectBuildSim() {
        if (buildSimUrl.isEmpty()) {
            LOG.info("BUILDSIM_URL unset: serving the HTTP view without the 3D room layers");
            return;
        }
        buildSim = new BuildSimAdapter(buildSimUrl.get());
    }

    private void connectJournal() {
        if (jdbcUrl.isEmpty()) {
            LOG.info("JDBC_URL unset: case replay disabled, the live view is unaffected");
            return;
        }
        try {
            journal = new TimescaleJournalAdapter(jdbcUrl.get(), jdbcUser, jdbcPassword);
        } catch (RuntimeException e) {
            LOG.warnf("journal %s unavailable, case replay disabled: %s",
                    jdbcUrl.get(), e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        closeQuietly(stream);
        closeQuietly(journal);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            LOG.debugf("ignoring failure closing %s: %s", closeable.getClass().getSimpleName(),
                    e.getMessage());
        }
    }
}
