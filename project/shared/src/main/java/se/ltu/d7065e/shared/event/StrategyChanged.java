package se.ltu.d7065e.shared.event;

import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.ventilation.entity.ControlMode;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * Published on {@code strategy/{zone}} by the supervisor. The inner loop subscribes and
 * stops commanding a zone the supervisor has degraded; a trimmed healthy zone carries its
 * trim here too (ADR-0008 consequence 6).
 */
public record StrategyChanged(
        ZoneId zone, ControlMode mode, Double trimmedPosition, ObservedAt at, CaseId caseId)
        implements DomainEvent {

    public StrategyChanged {
        if (zone == null || mode == null || at == null) {
            throw new IllegalArgumentException("strategy fields must not be null");
        }
        // Every other numeric field on the wire is a validated value object, so an unchecked
        // trim is the one hole through which a supervisor arithmetic slip could reach the
        // controller and the actuator as a damper position outside [0,1], failing far from its
        // cause. Validated here rather than typed as DamperPosition only because null means
        // "no trim", which the value object cannot express.
        if (trimmedPosition != null && !(trimmedPosition >= 0 && trimmedPosition <= 1)) {
            throw new IllegalArgumentException(
                    "trimmed position must be within [0,1], was: " + trimmedPosition);
        }
    }
}
