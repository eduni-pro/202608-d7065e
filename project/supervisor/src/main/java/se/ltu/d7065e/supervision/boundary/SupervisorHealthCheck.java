package se.ltu.d7065e.supervision.boundary;

import jakarta.inject.Singleton;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Liveness;
import se.ltu.d7065e.supervision.control.CompensationPlan;
import se.ltu.d7065e.supervision.control.SupervisionService;

/**
 * Liveness and not readiness: a supervisor with a latched fault is doing its job, so an open case
 * must never read as unhealthy and get the container restarted out from under the compensation it
 * is holding. The data is here so an operator can see what the supervisor is holding.
 */
@Liveness
@Singleton
public class SupervisorHealthCheck implements HealthCheck {

    private final SupervisionService supervision;

    public SupervisorHealthCheck(SupervisionService supervision) {
        this.supervision = supervision;
    }

    @Override
    public HealthCheckResponse call() {
        CompensationPlan plan = supervision.currentPlan();
        HealthCheckResponseBuilder response = HealthCheckResponse.named("supervisor")
                .up()
                .withData("faultedZones", supervision.faultedZones().toString())
                .withData("fanSpeed", supervision.plant().airHandler().commandedSpeed().toString());
        if (plan != null) {
            response.withData("damperTrim", "%.3f".formatted(plan.trim()))
                    .withData("faultedFlowGain", "%.3f".formatted(plan.achievedFaultedFlowGain()))
                    .withData("worstHealthyFlowDeviation",
                            "%.4f".formatted(plan.worstHealthyFlowDeviation()));
        }
        return response.build();
    }
}
