package se.ltu.d7065e.shared.event;

import se.ltu.d7065e.ventilation.entity.CommandId;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * A damper command on {@code commands/{zone}/damper}. The monitor keys its prediction on
 * {@code commandId}, which is how a residual is tied to the command it judges.
 */
public record CommandIssued(
        ZoneId zone, DamperPosition position, ObservedAt at, String reason, CommandId commandId)
        implements DomainEvent {

    public CommandIssued {
        if (zone == null || position == null || at == null || commandId == null) {
            throw new IllegalArgumentException("command fields must not be null");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a command must carry a reason");
        }
    }
}
