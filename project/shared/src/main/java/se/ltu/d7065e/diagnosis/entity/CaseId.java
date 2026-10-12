package se.ltu.d7065e.diagnosis.entity;

import java.util.UUID;

/** Identifies one fault case from first suspicion to service, for R-5 reconstruction. */
public record CaseId(String value) {
    public CaseId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("case id must not be blank");
        }
    }

    public static CaseId of(String value) {
        return new CaseId(value);
    }

    public static CaseId newId() {
        return new CaseId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
