package se.ltu.d7065e.shared.boundary;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.shared.control.BuildingOverlayPort;
import se.ltu.d7065e.shared.control.BuildingStatePort;
import se.ltu.d7065e.shared.control.ClockPort;
import se.ltu.d7065e.shared.control.OccupancyPort;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.FanPower;
import se.ltu.d7065e.ventilation.entity.FanSpeed;
import se.ltu.d7065e.ventilation.entity.Ppm;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.VentilationZone;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * BuildSim's REST API, the one place translation earns its cost (ADR-0006): sensor values and
 * actuator states are strings there and typed value objects here.
 */
public final class BuildSimAdapter
        implements BuildingStatePort, OccupancyPort, BuildingOverlayPort {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String baseUrl;
    private final ClockPort clock;
    private final Map<CaseId, JsonObject> workOrders = new LinkedHashMap<>();
    private final Map<String, JsonObject> roomLayers = new LinkedHashMap<>();

    /**
     * Without a clock, for the callers that only read sensors and write actuators — BuildSim
     * carries no timestamp on either, so those paths have no instant to stamp.
     */
    public BuildSimAdapter(String baseUrl) {
        this(baseUrl, new SystemClock());
    }

    /**
     * With the clock whose instants the work orders are stamped with. The supervisor passes the
     * clock it stamps its own events with, so that a work order's timestamp sits on the same
     * timeline as the case that raised it rather than on the wall clock beside it.
     */
    public BuildSimAdapter(String baseUrl, ClockPort clock) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.clock = clock;
    }

    @Override
    public Ppm readCo2(ZoneId zone) {
        return Ppm.of(numericField(get("/api/sensors/" + Topology.co2SensorId(zone)), "value"));
    }

    @Override
    public DamperPosition readDamperPosition(ZoneId zone) {
        return DamperPosition.of(
                numericField(get("/api/actuators/" + Topology.damperActuatorId(zone)), "state"));
    }

    @Override
    public FanSpeed readFanSpeed() {
        return FanSpeed.clamped(
                numericField(get("/api/actuators/" + Topology.fanSpeedActuatorId()), "state"));
    }

    @Override
    public FanPower readFanPower() {
        return FanPower.ofWatts(
                numericField(get("/api/sensors/" + Topology.fanPowerSensorId()), "value"));
    }

    @Override
    public void writeCo2(ZoneId zone, Ppm ppm) {
        putSensorValue(Topology.co2SensorId(zone), "%.1f".formatted(ppm.value()));
    }

    @Override
    public void writeFanPower(FanPower power) {
        putSensorValue(Topology.fanPowerSensorId(), "%.1f".formatted(power.watts()));
    }

    @Override
    public void applyDamperPosition(ZoneId zone, DamperPosition position) {
        putActuatorState(Topology.damperActuatorId(zone), "%.3f".formatted(position.value()));
    }

    @Override
    public void applyFanSpeed(FanSpeed speed) {
        putActuatorState(Topology.fanSpeedActuatorId(), "%.3f".formatted(speed.value()));
    }

    @Override
    public void publishOccupancy(Map<ZoneId, Integer> headCountPerZone) {
        JsonObjectBuilder body = Json.createObjectBuilder();
        headCountPerZone.forEach((zone, count) -> {
            JsonArrayBuilder persons = Json.createArrayBuilder();
            for (int i = 0; i < count; i++) {
                persons.add(Json.createObjectBuilder()
                        .add("id", zone.value() + "-p" + i)
                        .add("name", "Occupant " + (i + 1)));
            }
            body.add(Topology.buildSimKey(zone), Json.createObjectBuilder()
                    .add("persons", persons)
                    .add("aliens", Json.createArrayBuilder()));
        });
        put("/api/occupancy", body.build().toString());
    }

    @Override
    public void publishLayer(String id, String label, String unit, double minimum, double maximum,
            Map<ZoneId, Double> valuePerZone) {
        JsonObjectBuilder values = Json.createObjectBuilder();
        valuePerZone.forEach((zone, value) -> values.add(Topology.buildSimKey(zone), value));
        roomLayers.put(id, Json.createObjectBuilder()
                .add("id", id)
                .add("label", label)
                .add("unit", unit)
                .add("source", "supervisory monitor")
                .add("minimum", minimum)
                .add("maximum", maximum)
                .add("values", values)
                .build());
        putRoomLayers();
    }

    /**
     * {@code /api/room-layers} is a collection endpoint and a PUT replaces the whole collection,
     * exactly as {@code /api/alerts} does. Publishing one layer at a time therefore erased the
     * previous one, and only the last layer written in a tick ever reached the viewer -- which is
     * why {@code ?layer=state} rendered and {@code ?layer=co2}, {@code ?layer=residual} and
     * {@code ?layer=cusum} did not. Every known layer is sent on every call, the same way
     * {@link #putAlerts()} sends every open work order.
     */
    private void putRoomLayers() {
        JsonArrayBuilder all = Json.createArrayBuilder();
        roomLayers.values().forEach(all::add);
        put("/api/room-layers", all.build().toString());
    }

    @Override
    public void raiseWorkOrder(ZoneId zone, CaseId caseId, String summary, String severity) {
        workOrders.put(caseId, Json.createObjectBuilder()
                .add("id", "work-order-" + caseId.value())
                .add("level", Topology.LEVEL)
                .add("room", zone.value())
                .add("title", "Ventilation work order")
                .add("message", summary)
                .add("severity", severity)
                .add("timestamp", clock.now().toString())
                .build());
        putAlerts();
    }

    @Override
    public void clearWorkOrder(CaseId caseId) {
        workOrders.remove(caseId);
        putAlerts();
    }

    /**
     * Registers the configured rooms' sensors and dampers plus the air handler. Bulk creation is
     * idempotent for existing equipment ids, so this runs safely on every boot.
     */
    public void registerTopology() {
        JsonArrayBuilder equipment = Json.createArrayBuilder();
        for (VentilationZone zone : Topology.zones()) {
            equipment.add(Json.createObjectBuilder()
                    .add("id", "hvac-" + zone.id().value())
                    .add("name", "Ventilation " + zone.id().value())
                    .add("type", "ac_unit")
                    .add("category", "hvac")
                    .add("level", Topology.LEVEL)
                    .add("room", zone.id().value())
                    .add("status", "running")
                    .add("sensors", Json.createArrayBuilder()
                            .add(Json.createObjectBuilder()
                                    .add("id", Topology.co2SensorId(zone.id()))
                                    .add("name", "CO2")
                                    .add("type", "co2")
                                    .add("data_type", "text")
                                    .add("unit", "ppm")
                                    .add("value", "%.0f".formatted(Topology.OUTDOOR.value()))))
                    .add("actuators", Json.createArrayBuilder()
                            .add(Json.createObjectBuilder()
                                    .add("id", Topology.damperActuatorId(zone.id()))
                                    .add("name", "Ventilation damper")
                                    .add("type", "fan_speed")
                                    .add("state", "%.3f".formatted(
                                            zone.commandedPosition().value())))));
        }
        equipment.add(Json.createObjectBuilder()
                .add("id", Topology.AIR_HANDLER.value())
                .add("name", "Air handling unit " + Topology.AIR_HANDLER_ROOM)
                .add("type", "ventilation_fan")
                .add("category", "hvac")
                .add("level", Topology.LEVEL)
                .add("room", Topology.AIR_HANDLER_ROOM)
                .add("status", "running")
                .add("sensors", Json.createArrayBuilder()
                        .add(Json.createObjectBuilder()
                                .add("id", Topology.fanPowerSensorId())
                                .add("name", "Fan power")
                                .add("type", "power")
                                .add("data_type", "text")
                                .add("unit", "W")
                                .add("value", "%.1f".formatted(Topology.airHandler().power(0).watts()))))
                .add("actuators", Json.createArrayBuilder()
                        .add(Json.createObjectBuilder()
                                .add("id", Topology.fanSpeedActuatorId())
                                .add("name", "Fan speed")
                                .add("type", "fan_speed")
                                // The plant starts at its normal operating speed, not at maximum:
                                // registering it wide open would hand the simulator a building
                                // with no headroom, and the supervisor's compensation lever would
                                // be a no-op in the deployed system even though Topology reserves
                                // it (ADR-0008).
                                .add("state", "%.3f".formatted(
                                        Topology.NORMAL_FAN_SPEED.value())))));
        post("/api/equipment/bulk", equipment.build().toString());
    }

    public boolean isHealthy() {
        try {
            return http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/healthz"))
                            .timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (InterruptedException e) {
            // Callers poll this in a boot loop, so swallowing the interrupt would make them deaf
            // to SIGTERM and burn the whole shutdown grace period waiting for a BuildSim that is
            // not coming.
            Thread.currentThread().interrupt();
            return false;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /**
     * Republishes the whole alert collection, because that is the only shape BuildSim's
     * {@code /api/alerts} offers.
     *
     * <p>Consequence: <strong>exactly one process may own alerts.</strong> The set lives in this
     * adapter's own memory, so a second writer in a second process would not add an alert, it
     * would erase the first writer's on its next call, and they would erase each other in turn.
     * A distinct id prefix does not help — the clash is the whole-collection PUT, not the id.
     * The supervisor owns work orders; the dashboard reaches the operator through room layers.
     */
    private void putAlerts() {
        JsonArrayBuilder alerts = Json.createArrayBuilder();
        workOrders.values().forEach(alerts::add);
        put("/api/alerts", alerts.build().toString());
    }

    private void putSensorValue(String sensorId, String value) {
        put("/api/sensors/" + sensorId + "/value",
                Json.createObjectBuilder().add("data_type", "text").add("value", value)
                        .build().toString());
    }

    private void putActuatorState(String actuatorId, String state) {
        put("/api/actuators/" + actuatorId + "/state",
                Json.createObjectBuilder().add("state", state).build().toString());
    }

    private static double numericField(JsonObject object, String field) {
        if (!object.containsKey(field)) {
            throw new IllegalStateException("BuildSim response has no '" + field + "': " + object);
        }
        return switch (object.get(field).getValueType()) {
            case NUMBER -> object.getJsonNumber(field).doubleValue();
            case STRING -> Double.parseDouble(object.getString(field).trim());
            default -> throw new IllegalStateException(
                    "BuildSim '" + field + "' is not numeric: " + object.get(field));
        };
    }

    private JsonObject get(String path) {
        return parse(send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET()));
    }

    private void put(String path, String body) {
        send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)));
    }

    private void post(String path, String body) {
        send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private String send(HttpRequest.Builder request) {
        try {
            HttpResponse<String> response = http.send(
                    request.timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                        "BuildSim returned %d: %s".formatted(response.statusCode(), response.body()));
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted calling BuildSim", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot reach BuildSim at " + baseUrl, e);
        }
    }

    private static JsonObject parse(String body) {
        try (var reader = Json.createReader(new StringReader(body))) {
            return reader.readObject();
        }
    }
}
