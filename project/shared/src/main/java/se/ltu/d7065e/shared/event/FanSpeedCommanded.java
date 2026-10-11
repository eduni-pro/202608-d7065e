package se.ltu.d7065e.shared.event;

import se.ltu.d7065e.ventilation.entity.AhuId;
import se.ltu.d7065e.ventilation.entity.CommandId;
import se.ltu.d7065e.ventilation.entity.FanSpeed;
import se.ltu.d7065e.ventilation.entity.ObservedAt;

/**
 * A fan-speed command on {@code commands/{ahu}/fan-speed}. Owned by the supervisor: it is
 * the compensation lever, so the inner loop must not touch it (ADR-0008).
 */
public record FanSpeedCommanded(
        AhuId ahu, FanSpeed speed, ObservedAt at, String reason, CommandId commandId)
        implements DomainEvent {

    public FanSpeedCommanded {
        if (ahu == null || speed == null || at == null || commandId == null) {
            throw new IllegalArgumentException("fan speed command fields must not be null");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a command must carry a reason");
        }
    }
}
