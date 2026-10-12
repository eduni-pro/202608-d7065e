package se.ltu.d7065e.supervision.control;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.shared.control.BuildingOverlayPort;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/** The alert set as BuildSim would hold it, so a test can read what the operator would see. */
final class RecordingWorkOrders implements BuildingOverlayPort {

    record WorkOrder(ZoneId zone, CaseId caseId, String summary, String severity) {
    }

    private final Map<CaseId, WorkOrder> open = new LinkedHashMap<>();
    private final List<CaseId> cleared = new ArrayList<>();

    @Override
    public void publishLayer(String id, String label, String unit, double minimum, double maximum,
            Map<ZoneId, Double> valuePerZone) {
        throw new AssertionError("the supervisor does not own room layers; the dashboard does");
    }

    @Override
    public void raiseWorkOrder(ZoneId zone, CaseId caseId, String summary, String severity) {
        open.put(caseId, new WorkOrder(zone, caseId, summary, severity));
    }

    @Override
    public void clearWorkOrder(CaseId caseId) {
        open.remove(caseId);
        cleared.add(caseId);
    }

    List<WorkOrder> open() {
        return List.copyOf(open.values());
    }

    List<CaseId> cleared() {
        return List.copyOf(cleared);
    }

    WorkOrder only() {
        if (open.size() != 1) {
            throw new AssertionError("expected exactly one open work order, had " + open.size());
        }
        return open.values().iterator().next();
    }
}
