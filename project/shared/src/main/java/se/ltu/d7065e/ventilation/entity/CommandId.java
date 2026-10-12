package se.ltu.d7065e.ventilation.entity;

import java.util.UUID;

/** Correlates a command with the residual the monitor computes against it. */
public record CommandId(String value) {
    public CommandId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("command id must not be blank");
        }
    }

    public static CommandId of(String value) {
        return new CommandId(value);
    }

    public static CommandId newId() {
        return new CommandId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
