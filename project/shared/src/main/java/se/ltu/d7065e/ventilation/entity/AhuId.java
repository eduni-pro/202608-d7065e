package se.ltu.d7065e.ventilation.entity;

/** Identifier of an air-handling unit, {@code AHU-A120}. */
public record AhuId(String value) {
    public AhuId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ahu id must not be blank");
        }
    }

    public static AhuId of(String value) {
        return new AhuId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
