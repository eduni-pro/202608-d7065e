package se.ltu.d7065e.supervision.boundary;

import java.util.Map;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.shared.control.BuildingOverlayPort;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * What the supervisor gets when {@code BUILDSIM_URL} is unset. The operator's 3D view is an
 * output, not an input to any decision, so a headless evaluation run must not fail to start for
 * want of it — and the supervisor must not be written to care which of the two it got.
 */
final class SkippedWorkOrders implements BuildingOverlayPort {

    @Override
    public void publishLayer(String id, String label, String unit, double minimum, double maximum,
            Map<ZoneId, Double> valuePerZone) {
    }

    @Override
    public void raiseWorkOrder(ZoneId zone, CaseId caseId, String summary, String severity) {
    }

    @Override
    public void clearWorkOrder(CaseId caseId) {
    }
}
