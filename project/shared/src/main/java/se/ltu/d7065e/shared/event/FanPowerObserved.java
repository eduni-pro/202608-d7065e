package se.ltu.d7065e.shared.event;

import se.ltu.d7065e.ventilation.entity.AhuId;
import se.ltu.d7065e.ventilation.entity.FanPower;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.Quality;

/**
 * Fan power published on {@code readings/{ahu}/fan-power}. Power belongs to the air handler,
 * not a room — it is the monitor's one cross-room signal (ADR-0008).
 */
public record FanPowerObserved(AhuId ahu, FanPower watts, ObservedAt at, Quality quality)
        implements DomainEvent {

    public FanPowerObserved {
        if (ahu == null || watts == null || at == null || quality == null) {
            throw new IllegalArgumentException("fan power fields must not be null");
        }
    }
}
