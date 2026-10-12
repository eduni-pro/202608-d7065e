package se.ltu.d7065e.diagnosis.boundary;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import se.ltu.d7065e.diagnosis.control.FaultMonitor;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * R-4's "report unavailable", as something an operator can actually read: one entry per room
 * naming its monitor state, and readiness DOWN when the monitor can see no room at all.
 *
 * <p>DOWN only when <em>every</em> room is BLIND, because one dead CO2 sensor is a per-room
 * unavailability the per-room state already reports, and failing readiness for it would take a
 * monitor that is still diagnosing five rooms out of service — which is R-7's isolation
 * principle applied to the health endpoint.
 */
@Readiness
@ApplicationScoped
public class MonitorHealthCheck implements HealthCheck {

    private final MonitorRuntime runtime;

    @Inject
    public MonitorHealthCheck(MonitorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder response = HealthCheckResponse.named("diagnosis");
        FaultMonitor monitor = runtime.monitor();
        if (monitor == null) {
            return response.down().withData("reason", "not started").build();
        }
        int blind = 0;
        for (ZoneId zone : monitor.zones()) {
            MonitorState state = monitor.stateOf(zone);
            response.withData(zone.value(), state.name());
            if (state == MonitorState.BLIND) {
                blind++;
            }
        }
        return response.status(blind < monitor.zones().size()).build();
    }
}
