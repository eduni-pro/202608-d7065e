package se.ltu.d7065e.dashboard.control;

import java.time.Duration;
import java.time.Instant;
import se.ltu.d7065e.ventilation.entity.AhuId;

/**
 * The air handler as the live view knows it. Power is measured and speed is commanded, so the
 * two carry separate instants and only the measured one decides staleness — a fan speed is
 * still in force long after it was last commanded.
 */
public record AirHandlerSnapshot(
        AhuId ahu,
        Double watts,
        Double fanSpeed,
        Instant lastMeasuredAt,
        Duration age,
        boolean stale) {
}
