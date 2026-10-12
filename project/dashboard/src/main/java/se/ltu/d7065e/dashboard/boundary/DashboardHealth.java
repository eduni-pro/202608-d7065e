package se.ltu.d7065e.dashboard.boundary;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import se.ltu.d7065e.dashboard.control.RoomSnapshot;

/**
 * Reports what the view can currently see, and reports itself up regardless.
 *
 * <p>Deliberately never DOWN: a silent plant is exactly when an operator needs this page, and a
 * dashboard that failed its own readiness probe because no readings were arriving would be
 * restarted by the orchestrator precisely then. The numbers are in the data, where a human or a
 * probe can judge them.
 */
@Readiness
@ApplicationScoped
public class DashboardHealth implements HealthCheck {

    private final DashboardRuntime runtime;

    public DashboardHealth(DashboardRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public HealthCheckResponse call() {
        long live = runtime.projection().rooms().stream().filter(room -> !room.stale()).count();
        long latched = runtime.projection().rooms().stream().filter(RoomSnapshot::isLatched).count();
        return HealthCheckResponse.named("dashboard view")
                .up()
                .withData("roomsLive", live)
                .withData("roomsLatched", latched)
                .withData("brokerConnected", runtime.isBrokerConnected())
                .withData("buildSimConfigured", runtime.isOverlayConfigured())
                .withData("journalAvailable", runtime.journal() != null)
                .build();
    }
}
