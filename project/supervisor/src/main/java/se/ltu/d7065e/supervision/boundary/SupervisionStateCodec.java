package se.ltu.d7065e.supervision.boundary;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.supervision.control.PlantSnapshot;
import se.ltu.d7065e.supervision.control.SupervisedCase;
import se.ltu.d7065e.supervision.control.SupervisionSnapshot;
import se.ltu.d7065e.ventilation.entity.AhuId;
import se.ltu.d7065e.ventilation.entity.AirHandlingUnit;
import se.ltu.d7065e.ventilation.entity.AirflowRate;
import se.ltu.d7065e.ventilation.entity.ControlMode;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.FanSpeed;
import se.ltu.d7065e.ventilation.entity.VentilationZone;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * The supervisor's belief as the JSON document ADR-0012 stores, and back again.
 *
 * <p>Here in {@code boundary} because this is where storage shapes belong: {@code control} hands
 * over a {@link SupervisionSnapshot} and takes one back, and nothing that decides anything learns
 * that the state is JSON — or a file — at all. Written with {@code jakarta.json}, which is already
 * a declared dependency and already how {@code EventCodec} speaks to the wire, so the dependency
 * hierarchy is satisfied at step two, a Jakarta EE API, and the build gains nothing.
 *
 * <p>Zones are written whole rather than reduced to an id a reader looks back up in
 * {@code Topology}. The document is what the supervisor <em>believed</em>, and the geometry is
 * part of what the solver computed from; a document carrying only ids would silently read a stored
 * damper position against a rebuilt room.
 *
 * <p>Instants are written as they are read on whichever clock the writing process ran. Under a
 * scaled timeline a restored instant is only comparable while the epoch is unchanged, which
 * {@code restart: unless-stopped} preserves and a fresh {@code up} does not — a bound ADR-0012
 * owns rather than hides.
 */
interface SupervisionStateCodec {

    static String encode(SupervisionSnapshot snapshot) {
        JsonArrayBuilder cases = Json.createArrayBuilder();
        snapshot.cases().forEach(open -> cases.add(Json.createObjectBuilder()
                .add("caseId", open.caseId().value())
                .add("classification", open.classification().name())
                .add("frozenZone", zoneDocument(open.frozenZone()))));
        JsonObjectBuilder probes = Json.createObjectBuilder();
        snapshot.lastProbes().forEach((zone, at) -> probes.add(zone.value(), at.toString()));
        JsonObjectBuilder document = Json.createObjectBuilder()
                .add("cases", cases)
                .add("probes", probes);
        if (snapshot.baseline() != null) {
            document.add("baseline", plantDocument(snapshot.baseline()));
        }
        return document.build().toString();
    }

    static SupervisionSnapshot decode(String document) {
        JsonObject json;
        try (var reader = Json.createReader(new StringReader(document))) {
            json = reader.readObject();
        }
        List<SupervisedCase> cases = json.getJsonArray("cases").stream()
                .map(JsonValue::asJsonObject)
                .map(SupervisionStateCodec::supervisedCase)
                .toList();
        PlantSnapshot baseline = json.containsKey("baseline")
                ? plant(json.getJsonObject("baseline"))
                : null;
        return new SupervisionSnapshot(cases, baseline, probes(json.getJsonObject("probes")));
    }

    private static JsonObjectBuilder plantDocument(PlantSnapshot plant) {
        JsonArrayBuilder zones = Json.createArrayBuilder();
        plant.zones().forEach(zone -> zones.add(zoneDocument(zone)));
        return Json.createObjectBuilder()
                .add("zones", zones)
                .add("airHandler", Json.createObjectBuilder()
                        .add("id", plant.airHandler().id().value())
                        .add("nominalFlowCubicMetresPerSecond",
                                plant.airHandler().nominalFlow().cubicMetresPerSecond())
                        .add("nominalPowerWatts", plant.airHandler().nominalPowerWatts())
                        .add("commandedSpeed", plant.airHandler().commandedSpeed().value())
                        .add("servedZones", Json.createArrayBuilder(
                                plant.airHandler().servedZones().stream()
                                        .map(ZoneId::value).toList())));
    }

    private static JsonObjectBuilder zoneDocument(VentilationZone zone) {
        return Json.createObjectBuilder()
                .add("id", zone.id().value())
                .add("volumeCubicMetres", zone.volumeCubicMetres())
                .add("floorAreaSquareMetres", zone.floorAreaSquareMetres())
                .add("ahu", zone.ahuId().value())
                .add("damperAuthority", zone.damperAuthority())
                .add("minimumPosition", zone.minimumPosition().value())
                .add("mode", zone.mode().name())
                .add("commandedPosition", zone.commandedPosition().value());
    }

    private static SupervisedCase supervisedCase(JsonObject json) {
        return new SupervisedCase(
                CaseId.of(json.getString("caseId")),
                FaultClassification.valueOf(json.getString("classification")),
                zone(json.getJsonObject("frozenZone")));
    }

    private static PlantSnapshot plant(JsonObject json) {
        JsonObject airHandler = json.getJsonObject("airHandler");
        return new PlantSnapshot(
                json.getJsonArray("zones").stream()
                        .map(JsonValue::asJsonObject)
                        .map(SupervisionStateCodec::zone)
                        .toList(),
                new AirHandlingUnit(
                        AhuId.of(airHandler.getString("id")),
                        AirflowRate.ofCubicMetresPerSecond(airHandler
                                .getJsonNumber("nominalFlowCubicMetresPerSecond").doubleValue()),
                        airHandler.getJsonNumber("nominalPowerWatts").doubleValue(),
                        FanSpeed.of(airHandler.getJsonNumber("commandedSpeed").doubleValue()),
                        airHandler.getJsonArray("servedZones").stream()
                                .map(served -> ZoneId.of(((JsonString) served).getString()))
                                .toList()));
    }

    private static VentilationZone zone(JsonObject json) {
        return new VentilationZone(
                ZoneId.of(json.getString("id")),
                json.getJsonNumber("volumeCubicMetres").doubleValue(),
                json.getJsonNumber("floorAreaSquareMetres").doubleValue(),
                AhuId.of(json.getString("ahu")),
                json.getJsonNumber("damperAuthority").doubleValue(),
                DamperPosition.of(json.getJsonNumber("minimumPosition").doubleValue()),
                ControlMode.valueOf(json.getString("mode")),
                DamperPosition.of(json.getJsonNumber("commandedPosition").doubleValue()));
    }

    private static Map<ZoneId, Instant> probes(JsonObject json) {
        Map<ZoneId, Instant> granted = new LinkedHashMap<>();
        json.keySet().forEach(
                zone -> granted.put(ZoneId.of(zone), Instant.parse(json.getString(zone))));
        return granted;
    }
}
