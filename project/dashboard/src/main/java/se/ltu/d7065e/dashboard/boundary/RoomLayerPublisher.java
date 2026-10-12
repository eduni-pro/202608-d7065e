package se.ltu.d7065e.dashboard.boundary;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import se.ltu.d7065e.dashboard.control.LiveProjection;
import se.ltu.d7065e.dashboard.control.RoomLayer;
import se.ltu.d7065e.shared.control.BuildingOverlayPort;

/**
 * Feeds BuildSim's existing 3D floorplan one value layer per tick. BuildSim renders the
 * building already, so painting the current value of every room onto it is cheaper and more
 * honest than building a second viewer that would immediately disagree with the first.
 *
 * <p><strong>This container never raises a BuildSim work order.</strong> The supervisor raises
 * one per fault it acts on, and {@code BuildSimAdapter} keeps its work orders in its own
 * in-process map and PUTs the whole {@code /api/alerts} collection on every change — so a
 * second writer in a second process would not add an alert, it would erase the supervisor's.
 * A {@code monitor-} prefixed parallel id would not help: the clash is the whole-collection
 * PUT, not the id. Fault state therefore reaches the operator through
 * {@link RoomLayer#STATE}, which also survives the supervisor's compensation shrinking the
 * residual that first revealed the fault.
 */
@ApplicationScoped
public class RoomLayerPublisher {

    private static final Logger LOG = Logger.getLogger(RoomLayerPublisher.class);

    private final DashboardRuntime runtime;

    public RoomLayerPublisher(DashboardRuntime runtime) {
        this.runtime = runtime;
    }

    @Scheduled(every = "{dashboard.layer-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void publishLayers() {
        runtime.connectStream();
        BuildingOverlayPort overlay = runtime.overlay();
        if (overlay == null) {
            return;
        }
        LiveProjection projection = runtime.projection();
        try {
            // One call per layer so the viewer can switch between them, and the complete value
            // map every tick because BuildSim renders a snapshot and does not merge.
            for (RoomLayer layer : RoomLayer.values()) {
                overlay.publishLayer(layer.id(), layer.label(), layer.unit(), layer.minimum(),
                        layer.maximum(), projection.layerValues(layer));
            }
        } catch (RuntimeException e) {
            LOG.warnf("BuildSim refused the room layers, retrying on the next tick: %s",
                    e.getMessage());
        }
    }
}
