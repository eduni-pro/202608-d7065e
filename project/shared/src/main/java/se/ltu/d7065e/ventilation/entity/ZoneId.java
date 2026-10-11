package se.ltu.d7065e.ventilation.entity;

/**
 * A room identifier, {@code A115}.
 *
 * <p>Deliberately the bare room name and not BuildSim's floor-qualified {@code level0/A115}
 * key: a slash is an MQTT level separator, so carrying the floor here would silently turn
 * {@code readings/{zone}/co2} into a four-level topic. BuildSim's key is that schema's
 * business and is composed in its adapter (ADR-0006).
 */
public record ZoneId(String value) {
    public ZoneId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("zone id must not be blank");
        }
        if (value.contains("/")) {
            throw new IllegalArgumentException(
                    "zone id is the bare room name, not a BuildSim key, was: " + value);
        }
    }

    public static ZoneId of(String value) {
        return new ZoneId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
