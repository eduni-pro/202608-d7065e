package se.ltu.d7065e.dashboard.control;

import java.time.Duration;
import java.time.Instant;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.ventilation.entity.ControlMode;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * One room as the live view currently knows it. A {@code null} measurement means nothing has
 * ever arrived for that signal, which is deliberately distinct from a measurement that arrived
 * and then went {@link #stale}: the first has no number to show, the second has one that must
 * not be shown as if it were current.
 */
public record RoomSnapshot(
        ZoneId zone,
        Double co2Ppm,
        Double residualPpm,
        Double cusum,
        MonitorState monitorState,
        FaultClassification classification,
        ControlMode controlMode,
        CaseId caseId,
        Instant lastMeasuredAt,
        Duration age,
        boolean stale) {

    public boolean isLatched() {
        return monitorState.isLatched();
    }
}
