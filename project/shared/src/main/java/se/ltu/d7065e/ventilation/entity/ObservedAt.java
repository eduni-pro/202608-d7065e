package se.ltu.d7065e.ventilation.entity;

import java.time.Duration;
import java.time.Instant;

/** The instant an observation was taken, as distinct from when it was received. */
public record ObservedAt(Instant value) {
    public ObservedAt {
        if (value == null) {
            throw new IllegalArgumentException("observation instant must not be null");
        }
    }

    public static ObservedAt of(Instant value) {
        return new ObservedAt(value);
    }

    public Duration since(ObservedAt earlier) {
        return Duration.between(earlier.value, value);
    }

    public boolean isStalerThan(Duration limit, Instant now) {
        return Duration.between(value, now).compareTo(limit) > 0;
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
