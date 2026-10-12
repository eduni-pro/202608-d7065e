package se.ltu.d7065e.shared.event;

import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.Ppm;
import se.ltu.d7065e.ventilation.entity.Quality;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/** A CO2 reading published by {@code co2-sensor} on {@code readings/{zone}/co2}. */
public record ReadingObserved(ZoneId zone, Ppm ppm, ObservedAt at, Quality quality)
        implements DomainEvent {

    public ReadingObserved {
        if (zone == null || ppm == null || at == null || quality == null) {
            throw new IllegalArgumentException("reading fields must not be null");
        }
    }
}
