package se.ltu.d7065e.supervision.control;

import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.ventilation.entity.VentilationZone;

/**
 * One latched fault the supervisor is acting on, from the moment it degrades the zone until the
 * monitor reports it serviced.
 *
 * @param frozenZone the zone as it stood when the case latched, which carries the position the
 *     damper stuck at — the quantity the compensation gain is computed from
 */
public record SupervisedCase(
        CaseId caseId, FaultClassification classification, VentilationZone frozenZone) {

    public SupervisedCase {
        if (caseId == null || classification == null || frozenZone == null) {
            throw new IllegalArgumentException("a supervised case needs all three fields");
        }
    }

    /**
     * The same case under the monitor's later, named diagnosis, keeping the frozen zone: the
     * compensation gain is computed from the position the damper stuck at, and a case that is
     * renamed is the same episode at the same opening.
     */
    public SupervisedCase named(FaultClassification diagnosed) {
        return new SupervisedCase(caseId, diagnosed, frozenZone);
    }
}
