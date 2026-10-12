package se.ltu.d7065e.diagnosis.boundary;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import se.ltu.d7065e.diagnosis.control.DiagnosisSnapshot;
import se.ltu.d7065e.diagnosis.control.LatchedCase;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.diagnosis.entity.Evidence;
import se.ltu.d7065e.diagnosis.entity.FaultCase;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.FanSpeed;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * The monitor's belief as the JSON document ADR-0012 stores, and back again.
 *
 * <p>Here in {@code boundary} because this is where storage shapes belong: {@code control} hands
 * over a {@link DiagnosisSnapshot} and takes one back, and nothing that diagnoses anything learns
 * that the state is JSON — or a file — at all. Written with {@code jakarta.json}, which is already
 * a declared dependency and already how {@code EventCodec} speaks to the wire, so the dependency
 * hierarchy is satisfied at step two, a Jakarta EE API, and the build gains nothing.
 *
 * <p>A case is written whole, evidence included, rather than reduced to its id and classification.
 * Two reasons, and the second is the sharper one. A latched case <em>is</em> its argument — R-5 is
 * about a diagnosis that can be explained and not only reported, and a case restored without its
 * evidence would publish an unexplained latch on the next probe request. And the pinned damper's
 * value is written into that evidence as formatted prose
 * ({@code ZoneMonitor.stopBelievingCommands}), which ADR-0012 cites as the one datum the journal
 * does not hold; the pin itself is stored as a number here, so the prose is no longer the only
 * copy, but a work order whose numbers moved and whose reason had been dropped would be unreadable.
 *
 * <p>Instants are written as they are read on whichever clock the writing process ran. Under a
 * scaled timeline a restored instant is only comparable while the epoch is unchanged, which
 * {@code restart: unless-stopped} preserves and a fresh {@code up} does not — a bound ADR-0012
 * owns rather than hides.
 */
interface DiagnosisStateCodec {

    static String encode(DiagnosisSnapshot snapshot) {
        JsonArrayBuilder cases = Json.createArrayBuilder();
        snapshot.cases().forEach(latched -> cases.add(Json.createObjectBuilder()
                .add("confirmedAt", latched.confirmedAt().toString())
                .add("case", caseDocument(latched.openCase()))));
        JsonObjectBuilder positions = Json.createObjectBuilder();
        snapshot.believedPositions().forEach(
                (zone, position) -> positions.add(zone.value(), position.value()));
        return Json.createObjectBuilder()
                .add("cases", cases)
                .add("believedFanSpeed", snapshot.believedFanSpeed().value())
                .add("believedPositions", positions)
                .add("unresponsiveDampers", Json.createArrayBuilder(
                        snapshot.unresponsiveDampers().stream().map(ZoneId::value).toList()))
                .build()
                .toString();
    }

    static DiagnosisSnapshot decode(String document) {
        JsonObject json;
        try (var reader = Json.createReader(new StringReader(document))) {
            json = reader.readObject();
        }
        List<LatchedCase> cases = json.getJsonArray("cases").stream()
                .map(JsonValue::asJsonObject)
                .map(DiagnosisStateCodec::latchedCase)
                .toList();
        return new DiagnosisSnapshot(cases,
                FanSpeed.of(json.getJsonNumber("believedFanSpeed").doubleValue()),
                positions(json.getJsonObject("believedPositions")),
                zones(json, "unresponsiveDampers"));
    }

    private static JsonObjectBuilder caseDocument(FaultCase open) {
        JsonArrayBuilder evidence = Json.createArrayBuilder();
        open.evidence().forEach(observed -> evidence.add(Json.createObjectBuilder()
                .add("observation", observed.observation())
                .add("supportsFault", observed.supportsFault())
                .add("at", observed.at().value().toString())));
        return Json.createObjectBuilder()
                .add("id", open.id().value())
                .add("zone", open.zone().value())
                .add("state", open.state().name())
                .add("classification", open.classification().name())
                .add("evidence", evidence)
                .add("openedAt", open.openedAt().value().toString())
                .add("updatedAt", open.updatedAt().value().toString());
    }

    private static LatchedCase latchedCase(JsonObject json) {
        return new LatchedCase(faultCase(json.getJsonObject("case")),
                Instant.parse(json.getString("confirmedAt")));
    }

    private static FaultCase faultCase(JsonObject json) {
        return new FaultCase(
                CaseId.of(json.getString("id")),
                ZoneId.of(json.getString("zone")),
                MonitorState.valueOf(json.getString("state")),
                FaultClassification.valueOf(json.getString("classification")),
                json.getJsonArray("evidence").stream()
                        .map(JsonValue::asJsonObject)
                        .map(DiagnosisStateCodec::evidence)
                        .toList(),
                ObservedAt.of(Instant.parse(json.getString("openedAt"))),
                ObservedAt.of(Instant.parse(json.getString("updatedAt"))));
    }

    private static Evidence evidence(JsonObject json) {
        return new Evidence(json.getString("observation"), json.getBoolean("supportsFault"),
                ObservedAt.of(Instant.parse(json.getString("at"))));
    }

    private static Map<ZoneId, DamperPosition> positions(JsonObject json) {
        Map<ZoneId, DamperPosition> believed = new LinkedHashMap<>();
        json.keySet().forEach(zone -> believed.put(ZoneId.of(zone),
                DamperPosition.of(json.getJsonNumber(zone).doubleValue())));
        return believed;
    }

    private static Set<ZoneId> zones(JsonObject json, String field) {
        Set<ZoneId> named = new LinkedHashSet<>();
        json.getJsonArray(field).forEach(zone -> named.add(ZoneId.of(((JsonString) zone).getString())));
        return named;
    }
}
