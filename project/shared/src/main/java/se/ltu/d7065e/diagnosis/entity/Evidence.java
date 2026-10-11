package se.ltu.d7065e.diagnosis.entity;

import se.ltu.d7065e.ventilation.entity.ObservedAt;

/**
 * One observation that argued for or against a classification, kept so a diagnosis can be
 * explained rather than only reported (R-5).
 */
public record Evidence(String observation, boolean supportsFault, ObservedAt at) {
    public Evidence {
        if (observation == null || observation.isBlank()) {
            throw new IllegalArgumentException("evidence needs an observation");
        }
        if (at == null) {
            throw new IllegalArgumentException("evidence needs an instant");
        }
    }

    public static Evidence supporting(String observation, ObservedAt at) {
        return new Evidence(observation, true, at);
    }

    public static Evidence against(String observation, ObservedAt at) {
        return new Evidence(observation, false, at);
    }
}
