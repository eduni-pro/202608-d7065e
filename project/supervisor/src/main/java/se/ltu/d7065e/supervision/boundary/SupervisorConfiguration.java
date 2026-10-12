package se.ltu.d7065e.supervision.boundary;

import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import se.ltu.d7065e.shared.boundary.BuildSimAdapter;
import se.ltu.d7065e.shared.boundary.FileServiceStateAdapter;
import se.ltu.d7065e.shared.boundary.MqttAdapter;
import se.ltu.d7065e.shared.boundary.ScaledClock;
import se.ltu.d7065e.shared.boundary.SystemClock;
import se.ltu.d7065e.shared.boundary.TimescaleJournalAdapter;
import se.ltu.d7065e.shared.control.BuildingOverlayPort;
import se.ltu.d7065e.shared.control.ClockPort;
import se.ltu.d7065e.shared.control.EventPublisherPort;
import se.ltu.d7065e.shared.control.ResidualJournalPort;
import se.ltu.d7065e.shared.control.ServiceStatePort;
import se.ltu.d7065e.supervision.control.PlantState;
import se.ltu.d7065e.supervision.control.SupervisionService;

/**
 * Wires the supervision core to its adapters. Everything framework-shaped lives here, so
 * {@link SupervisionService} stays plain Java and the evaluation harness constructs it directly
 * (ADR-0005).
 *
 * <p>The broker is required — without it the supervisor can neither hear a fault nor command a
 * damper. BuildSim and the journal are optional: a missing {@code BUILDSIM_URL} or
 * {@code JDBC_URL} substitutes {@link SkippedWorkOrders} or {@link SkippedJournal} instead of
 * failing to boot, so the evaluation harness can run headless. The state store ADR-0012 adds is
 * optional in the same way and for the same reason.
 */
public class SupervisorConfiguration {

    static final String DEFAULT_BROKER_URL = "tcp://localhost:1883";
    static final String CLIENT_ID = "supervisor";

    /**
     * The one clock this component has, and under {@code TIME_SCALE > 1} it is the shared
     * simulated one: every {@code ObservedAt} the supervisor stamps on a strategy, a work order
     * or a journal row has to be on the same timeline as the readings and residuals it is
     * answering, or the recorded history of a case would interleave two notions of time.
     */
    @Produces
    @Singleton
    ClockPort clock() {
        return ScaledClock.fromEnvironment(new SystemClock());
    }

    @Produces
    @Singleton
    PlantState plantState() {
        return PlantState.ofTopology();
    }

    @Produces
    @Singleton
    MqttAdapter broker() {
        return new MqttAdapter(environment("BROKER_URL", DEFAULT_BROKER_URL), CLIENT_ID);
    }

    void closeBroker(@Disposes MqttAdapter broker) {
        broker.close();
    }

    @Produces
    @Singleton
    BuildingOverlayPort workOrders(ClockPort clock) {
        String url = environment("BUILDSIM_URL", null);
        return url == null ? new SkippedWorkOrders() : new BuildSimAdapter(url, clock);
    }

    @Produces
    @Singleton
    ResidualJournalPort journal() {
        String url = environment("JDBC_URL", null);
        return url == null ? new SkippedJournal() : new TimescaleJournalAdapter(
                url, environment("JDBC_USER", null), environment("JDBC_PASSWORD", null));
    }

    void closeJournal(@Disposes ResidualJournalPort journal) {
        if (journal instanceof TimescaleJournalAdapter adapter) {
            adapter.close();
        }
    }

    /**
     * Where this supervisor keeps what it currently believes, so a restart resumes instead of
     * re-snapshotting an already trimmed plant (ADR-0012). An unset {@code SERVICE_STATE_PATH}
     * substitutes {@link SkippedState} and the supervisor keeps nothing, the same way an unset
     * {@code JDBC_URL} means it records nothing — the store is an output of its belief and an
     * input to no decision, so a deployment without a volume must not fail to boot.
     */
    @Produces
    @Singleton
    ServiceStatePort serviceState() {
        String path = environment("SERVICE_STATE_PATH", null);
        return path == null ? new SkippedState() : new FileServiceStateAdapter(Path.of(path));
    }

    @Produces
    @Singleton
    SupervisionService supervision(PlantState plant, EventPublisherPort publisher,
            BuildingOverlayPort workOrders, ResidualJournalPort journal, ClockPort clock) {
        return new SupervisionService(plant, publisher, workOrders, journal, clock);
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
