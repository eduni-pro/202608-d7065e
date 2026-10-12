package se.ltu.d7065e.dashboard.boundary;

import jakarta.json.Json;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import se.ltu.d7065e.dashboard.control.RoomSnapshot;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.shared.control.EventPublisherPort;
import se.ltu.d7065e.shared.event.ServiceReported;
import se.ltu.d7065e.shared.event.Topics;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * D-10 — the operator's door to the one exit from a latched case: the technician repaired the
 * damper, and this is where they say so.
 *
 * <p>The monitor's own subscription to {@code service/{zone}} is what actually clears the case, and
 * it refuses the same three mistakes against the state it holds. This endpoint exists because that
 * refusal has nowhere to go on MQTT, and an operator told nothing cannot tell a cleared case from a
 * lost message. So the view checks the request against what it knows first and answers
 * synchronously — 404 for a room or a case that is not there, 409 for a case that is not latched,
 * 503 when there is no broker to carry the report at all.
 *
 * <p><strong>202 and not 200.</strong> Publishing the report is all this container can do. The
 * monitor lives in another process and is the only thing that can close a case, so claiming the
 * case was cleared here would be a claim about a component this one cannot see. The view will show
 * the room as {@code SERVICED} once the monitor says so on {@code faults/{zone}}, which is also
 * what releases the supervisor's compensation.
 */
@Path("/api/zones")
public class ServiceResource {

    /** What is recorded when the operator gives no account of the repair. */
    static final String UNSTATED_REASON = "reported serviced by an operator, no detail given";

    private final DashboardRuntime runtime;

    public ServiceResource(DashboardRuntime runtime) {
        this.runtime = runtime;
    }

    @POST
    @Path("/{zone}/cases/{caseId}/serviced")
    @Produces(MediaType.APPLICATION_JSON)
    public Response serviced(@PathParam("zone") String zone, @PathParam("caseId") String caseId,
            @QueryParam("reason") String reason) {
        if (caseId.isBlank()) {
            return Problem.of(Response.Status.BAD_REQUEST, "a case id is required");
        }
        // Matched against the topology rather than parsed into a ZoneId first: a path segment that
        // decodes to something ZoneId refuses — an encoded slash, say — would otherwise leave the
        // boundary as a 500 instead of the 404 it is.
        ZoneId reported = Topology.ZONE_IDS.stream()
                .filter(id -> id.value().equals(zone))
                .findFirst()
                .orElse(null);
        if (reported == null) {
            return Problem.of(Response.Status.NOT_FOUND, "no such room: " + zone);
        }
        EventPublisherPort publisher = runtime.publisher();
        if (publisher == null) {
            // Checked before the case, because without a broker the view has seen no fault event
            // either and every room would otherwise be refused as having no open case — which
            // names the symptom and hides the cause.
            return Problem.of(Response.Status.SERVICE_UNAVAILABLE, runtime.isBrokerConfigured()
                    ? "the broker is configured but unreachable, so no repair can be reported"
                    : "no broker is configured, so this view cannot report a repair");
        }
        RoomSnapshot room = runtime.projection().room(reported);
        if (room.caseId() == null) {
            return Problem.of(Response.Status.NOT_FOUND,
                    "no open case in %s, so case %s cannot be serviced".formatted(zone, caseId));
        }
        if (!room.caseId().value().equals(caseId)) {
            return Problem.of(Response.Status.NOT_FOUND,
                    "case %s is not the case open in %s, which is %s"
                            .formatted(caseId, zone, room.caseId().value()));
        }
        if (!room.isLatched()) {
            return Problem.of(Response.Status.CONFLICT,
                    "case %s in %s is %s, and only a latched case can be serviced"
                            .formatted(caseId, zone, room.monitorState()));
        }
        ObservedAt at = ObservedAt.of(runtime.projection().now());
        String account = reason == null || reason.isBlank() ? UNSTATED_REASON : reason;
        String topic = Topics.service(reported);
        publisher.publish(topic, new ServiceReported(reported, CaseId.of(caseId), at, account));
        return Response.accepted(Json.createObjectBuilder()
                .add("zone", zone)
                .add("caseId", caseId)
                .add("reason", account)
                .add("reportedAt", at.value().toString())
                .add("topic", topic)
                .add("detail", "the repair was reported; the monitor closes the case and the room "
                        + "turns SERVICED in this view once it does")
                .build().toString()).build();
    }
}
