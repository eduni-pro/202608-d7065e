package se.ltu.d7065e.dashboard.boundary;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.time.Duration;
import java.util.List;
import se.ltu.d7065e.dashboard.control.AirHandlerSnapshot;
import se.ltu.d7065e.dashboard.control.CaseReplay;
import se.ltu.d7065e.dashboard.control.CaseSnapshot;
import se.ltu.d7065e.dashboard.control.LiveProjection;
import se.ltu.d7065e.dashboard.control.RoomSnapshot;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.shared.boundary.EventCodec;
import se.ltu.d7065e.shared.event.DomainEvent;

/**
 * The machine-readable live view, and the R-5 replay endpoint beside it. JSON is built with
 * JSON-P rather than bound from the view records, so the wire shape is visible in one place and
 * the control layer stays free of any annotation.
 */
@Path("/api")
public class StateResource {

    private final DashboardRuntime runtime;

    public StateResource(DashboardRuntime runtime) {
        this.runtime = runtime;
    }

    @GET
    @Path("/state")
    @Produces(MediaType.APPLICATION_JSON)
    public String state() {
        LiveProjection projection = runtime.projection();
        JsonArrayBuilder rooms = Json.createArrayBuilder();
        projection.rooms().forEach(room -> rooms.add(asJson(room)));
        return Json.createObjectBuilder()
                .add("at", projection.now().toString())
                .add("staleAfterSeconds", projection.stalenessWindow().toSeconds())
                .add("broker", Json.createObjectBuilder()
                        .add("configured", runtime.isBrokerConfigured())
                        .add("connected", runtime.isBrokerConnected()))
                .add("buildSim", Json.createObjectBuilder()
                        .add("configured", runtime.isOverlayConfigured()))
                .add("journal", Json.createObjectBuilder()
                        .add("configured", runtime.isJournalConfigured())
                        .add("available", runtime.journal() != null))
                .add("airHandler", asJson(projection.airHandler()))
                .add("rooms", rooms)
                .build().toString();
    }

    /**
     * Every case this view has seen, open ones first.
     *
     * <p>Live rather than journalled, and the two answer different questions. This is what the
     * running view currently holds, which is what an operator acts on and what the Serviced
     * control on the page is driven from; {@link #replayCase} is the authoritative history of one
     * case and reads the journal. A case opened before this process started is therefore absent
     * here and present there, which is stated on the page rather than left to be discovered.
     */
    @GET
    @Path("/cases")
    @Produces(MediaType.APPLICATION_JSON)
    public String cases() {
        LiveProjection projection = runtime.projection();
        List<CaseSnapshot> cases = projection.cases();
        JsonArrayBuilder json = Json.createArrayBuilder();
        cases.forEach(one -> json.add(asJson(one)));
        return Json.createObjectBuilder()
                .add("at", projection.now().toString())
                .add("open", cases.stream().filter(CaseSnapshot::isOpen).count())
                .add("total", cases.size())
                .add("cases", json)
                .build().toString();
    }

    /**
     * R-5: rebuild one diagnosis from the journal. The events come back in time order with the
     * same field names they had on the wire, because {@link EventCodec} is the project's one
     * event shape and a second one here would let the replay disagree with what was published.
     */
    @GET
    @Path("/cases/{caseId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response replayCase(@PathParam("caseId") String caseId) {
        if (caseId.isBlank()) {
            return Problem.of(Response.Status.BAD_REQUEST, "a case id is required");
        }
        CaseReplay replay = new CaseReplay(runtime.journal());
        if (!replay.isAvailable()) {
            return Problem.of(Response.Status.SERVICE_UNAVAILABLE, runtime.isJournalConfigured()
                    ? "the journal is configured but unreachable, so this case cannot be replayed"
                    : "JDBC_URL is unset, so no journal is configured and no case can be replayed");
        }
        List<DomainEvent> events;
        try {
            events = replay.timelineOf(CaseId.of(caseId));
        } catch (IllegalArgumentException e) {
            return Problem.of(Response.Status.NOT_FOUND, "no such case in the journal: " + caseId);
        } catch (IllegalStateException e) {
            return Problem.of(Response.Status.SERVICE_UNAVAILABLE,
                    "the journal could not be read: " + e.getMessage());
        }
        if (events.isEmpty()) {
            return Problem.of(Response.Status.NOT_FOUND, "no such case in the journal: " + caseId);
        }
        JsonArrayBuilder timeline = Json.createArrayBuilder();
        int sequence = 1;
        for (DomainEvent event : events) {
            timeline.add(Json.createObjectBuilder(decoded(event)).add("seq", sequence++));
        }
        return Response.ok(Json.createObjectBuilder()
                .add("caseId", caseId)
                .add("eventCount", events.size())
                .add("openedAt", events.getFirst().at().value().toString())
                .add("closedAt", events.getLast().at().value().toString())
                .add("events", timeline)
                .build().toString()).build();
    }

    private static JsonObject decoded(DomainEvent event) {
        try (var reader = Json.createReader(new StringReader(EventCodec.encode(event)))) {
            return reader.readObject();
        }
    }

    private static JsonObject asJson(CaseSnapshot one) {
        JsonArrayBuilder evidence = Json.createArrayBuilder();
        one.evidence().forEach(evidence::add);
        return Json.createObjectBuilder()
                .add("caseId", one.caseId().value())
                .add("zone", one.zone().value())
                .add("state", one.state().name())
                .add("classification", one.classification().name())
                .add("open", one.isOpen())
                .add("openedAt", one.openedAt().toString())
                .add("updatedAt", one.updatedAt().toString())
                .add("evidence", evidence)
                .build();
    }

    private static JsonObject asJson(RoomSnapshot room) {
        JsonObjectBuilder json = Json.createObjectBuilder()
                .add("zone", room.zone().value())
                .add("monitorState", room.monitorState().name())
                .add("latched", room.isLatched())
                .add("classification", room.classification().name())
                .add("controlMode", room.controlMode().name())
                .add("stale", room.stale());
        addNumber(json, "co2Ppm", room.co2Ppm());
        addNumber(json, "residualPpm", room.residualPpm());
        addNumber(json, "cusum", room.cusum());
        addText(json, "caseId", room.caseId() == null ? null : room.caseId().value());
        addText(json, "lastMeasuredAt",
                room.lastMeasuredAt() == null ? null : room.lastMeasuredAt().toString());
        addSeconds(json, room.age());
        return json.build();
    }

    private static JsonObject asJson(AirHandlerSnapshot ahu) {
        JsonObjectBuilder json = Json.createObjectBuilder()
                .add("id", ahu.ahu().value())
                .add("stale", ahu.stale());
        addNumber(json, "watts", ahu.watts());
        addNumber(json, "fanSpeed", ahu.fanSpeed());
        addText(json, "lastMeasuredAt",
                ahu.lastMeasuredAt() == null ? null : ahu.lastMeasuredAt().toString());
        addSeconds(json, ahu.age());
        return json.build();
    }

    /** A never-measured signal is explicitly null rather than absent, so the shape is stable. */
    private static void addNumber(JsonObjectBuilder json, String name, Double value) {
        if (value == null) {
            json.addNull(name);
        } else {
            json.add(name, value);
        }
    }

    private static void addText(JsonObjectBuilder json, String name, String value) {
        if (value == null) {
            json.addNull(name);
        } else {
            json.add(name, value);
        }
    }

    private static void addSeconds(JsonObjectBuilder json, Duration age) {
        if (age == null) {
            json.addNull("ageSeconds");
        } else {
            json.add("ageSeconds", age.toSeconds());
        }
    }

}
