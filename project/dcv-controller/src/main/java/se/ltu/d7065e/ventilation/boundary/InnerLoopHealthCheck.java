package se.ltu.d7065e.ventilation.boundary;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import se.ltu.d7065e.ventilation.entity.Topology;

/**
 * Readiness: the controller is ready once it is subscribed, because that is the point from which
 * it can act. How many rooms it currently has fresh readings for is reported as data rather than
 * as health — starved readings are a condition the controller handles by holding position, and
 * T-27 kills the sensor on purpose, so going DOWN for it would make a tested scenario look like
 * a broken container.
 */
@Readiness
@ApplicationScoped
public class InnerLoopHealthCheck implements HealthCheck {

    private final DamperControlJob job;

    @Inject
    public InnerLoopHealthCheck(DamperControlJob job) {
        this.job = job;
    }

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("dcv-controller inner loop")
                .status(job.isSubscribed())
                .withData("roomsConfigured", Topology.ZONE_IDS.size())
                .withData("roomsWithFreshReadings", job.loop().roomsWithFreshReadings())
                .withData("journalling", job.isJournalling())
                .build();
    }
}
