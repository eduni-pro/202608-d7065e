package se.ltu.d7065e.shared.control;

import java.util.Map;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * The operator's view: per-room value layers on the 3D floorplan, and work orders as alerts.
 * Separate from {@link BuildingStatePort} because this is what a human reads, not what a
 * device measures.
 */
public interface BuildingOverlayPort {

    void publishLayer(String id, String label, String unit, double minimum, double maximum,
            Map<ZoneId, Double> valuePerZone);

    void raiseWorkOrder(ZoneId zone, CaseId caseId, String summary, String severity);

    void clearWorkOrder(CaseId caseId);
}
