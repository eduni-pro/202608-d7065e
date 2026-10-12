package se.ltu.d7065e.shared.event;

import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * An operator's report on {@code service/{zone}} that the equipment behind a latched case has
 * been repaired. It is the only thing that clears a latched diagnosis, and until it existed the
 * exit was unreachable from a running deployment (D-10).
 *
 * <p>It is deliberately <em>not</em> a {@code commands/...} topic. Everything under
 * {@code commands/} is actuator authority — the inner loop owns the dampers, the supervisor owns
 * the fan — and a service report moves no actuator: it states a fact about the building that no
 * component can observe. On its own topic family the authority argument is untouched, because
 * nothing new publishes to an actuator.
 *
 * <p>The case id is required rather than optional, so a report that arrives late, after the case
 * it names has already closed, cannot clear whatever case the room has next.
 */
public record ServiceReported(ZoneId zone, CaseId caseId, ObservedAt at, String reason)
        implements DomainEvent {

    public ServiceReported {
        if (zone == null || caseId == null || at == null) {
            throw new IllegalArgumentException("service report fields must not be null");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a service report must carry a reason");
        }
    }
}
