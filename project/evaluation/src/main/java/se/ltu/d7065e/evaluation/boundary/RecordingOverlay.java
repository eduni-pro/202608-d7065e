package se.ltu.d7065e.evaluation.boundary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.shared.control.BuildingOverlayPort;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/** BuildSim, replaced by two lists: the work orders raised and the ones cleared again. */
public final class RecordingOverlay implements BuildingOverlayPort {

    private final List<WorkOrder> raised = new ArrayList<>();
    private final List<CaseId> cleared = new ArrayList<>();

    @Override
    public void publishLayer(String id, String label, String unit, double minimum, double maximum,
            Map<ZoneId, Double> valuePerZone) {
        // The 3D overlay is T-23's deliverable and carries no requirement; nothing to record.
    }

    @Override
    public void raiseWorkOrder(ZoneId zone, CaseId caseId, String summary, String severity) {
        raised.add(new WorkOrder(zone, caseId, summary, severity));
    }

    @Override
    public void clearWorkOrder(CaseId caseId) {
        cleared.add(caseId);
    }

    public List<WorkOrder> raised() {
        return List.copyOf(raised);
    }

    public List<CaseId> cleared() {
        return List.copyOf(cleared);
    }

    public record WorkOrder(ZoneId zone, CaseId caseId, String summary, String severity) {}
}
