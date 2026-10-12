package se.ltu.d7065e.shared.boundary;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.time.Instant;
import java.util.List;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.diagnosis.entity.CusumStatistic;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.diagnosis.entity.Residual;
import se.ltu.d7065e.shared.event.CommandIssued;
import se.ltu.d7065e.shared.event.DomainEvent;
import se.ltu.d7065e.shared.event.FanPowerObserved;
import se.ltu.d7065e.shared.event.FanSpeedCommanded;
import se.ltu.d7065e.shared.event.FaultStateChanged;
import se.ltu.d7065e.shared.event.ReadingObserved;
import se.ltu.d7065e.shared.event.ResidualComputed;
import se.ltu.d7065e.shared.event.ServiceReported;
import se.ltu.d7065e.shared.event.StrategyChanged;
import se.ltu.d7065e.ventilation.entity.AhuId;
import se.ltu.d7065e.ventilation.entity.CommandId;
import se.ltu.d7065e.ventilation.entity.ControlMode;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.FanPower;
import se.ltu.d7065e.ventilation.entity.FanSpeed;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.Ppm;
import se.ltu.d7065e.ventilation.entity.Quality;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * Serialises domain events as the MQTT payload (ADR-0006: the event <em>is</em> the payload).
 * The wire field names are exactly the ones the implementation plan section 3 lists, plus a
 * {@code type} discriminator so one subscriber can decode a mixed stream.
 */
public interface EventCodec {

    static String encode(DomainEvent event) {
        JsonObjectBuilder json = Json.createObjectBuilder()
                .add("type", event.getClass().getSimpleName())
                .add("ts", event.at().value().toString());
        switch (event) {
            case ReadingObserved e -> json
                    .add("zone", e.zone().value())
                    .add("ppm", e.ppm().value())
                    .add("quality", e.quality().name());
            case FanPowerObserved e -> json
                    .add("ahu", e.ahu().value())
                    .add("watts", e.watts().watts())
                    .add("quality", e.quality().name());
            case CommandIssued e -> json
                    .add("zone", e.zone().value())
                    .add("position", e.position().value())
                    .add("reason", e.reason())
                    .add("commandId", e.commandId().value());
            case FanSpeedCommanded e -> json
                    .add("ahu", e.ahu().value())
                    .add("speed", e.speed().value())
                    .add("reason", e.reason())
                    .add("commandId", e.commandId().value());
            case ResidualComputed e -> {
                json.add("zone", e.zone().value())
                        .add("residual", e.residual().ppm())
                        .add("cusum", e.cusum().value());
                if (e.commandId() != null) {
                    json.add("commandId", e.commandId().value());
                }
            }
            case FaultStateChanged e -> {
                json.add("zone", e.zone().value())
                        .add("state", e.state().name())
                        .add("classification", e.classification().name())
                        .add("evidence", Json.createArrayBuilder(e.evidence()));
                if (e.caseId() != null) {
                    json.add("caseId", e.caseId().value());
                }
            }
            case StrategyChanged e -> {
                json.add("zone", e.zone().value()).add("mode", e.mode().name());
                if (e.trimmedPosition() != null) {
                    json.add("trimmedPosition", e.trimmedPosition());
                }
                if (e.caseId() != null) {
                    json.add("caseId", e.caseId().value());
                }
            }
            case ServiceReported e -> json
                    .add("zone", e.zone().value())
                    .add("caseId", e.caseId().value())
                    .add("reason", e.reason());
        }
        return json.build().toString();
    }

    static DomainEvent decode(String payload) {
        JsonObject json;
        try (var reader = Json.createReader(new StringReader(payload))) {
            json = reader.readObject();
        }
        ObservedAt at = ObservedAt.of(Instant.parse(json.getString("ts")));
        return switch (json.getString("type")) {
            case "ReadingObserved" -> new ReadingObserved(
                    ZoneId.of(json.getString("zone")),
                    Ppm.of(json.getJsonNumber("ppm").doubleValue()),
                    at,
                    Quality.valueOf(json.getString("quality")));
            case "FanPowerObserved" -> new FanPowerObserved(
                    AhuId.of(json.getString("ahu")),
                    FanPower.ofWatts(json.getJsonNumber("watts").doubleValue()),
                    at,
                    Quality.valueOf(json.getString("quality")));
            case "CommandIssued" -> new CommandIssued(
                    ZoneId.of(json.getString("zone")),
                    DamperPosition.of(json.getJsonNumber("position").doubleValue()),
                    at,
                    json.getString("reason"),
                    CommandId.of(json.getString("commandId")));
            case "FanSpeedCommanded" -> new FanSpeedCommanded(
                    AhuId.of(json.getString("ahu")),
                    FanSpeed.of(json.getJsonNumber("speed").doubleValue()),
                    at,
                    json.getString("reason"),
                    CommandId.of(json.getString("commandId")));
            case "ResidualComputed" -> new ResidualComputed(
                    ZoneId.of(json.getString("zone")),
                    Residual.of(json.getJsonNumber("residual").doubleValue()),
                    CusumStatistic.of(json.getJsonNumber("cusum").doubleValue()),
                    at,
                    json.containsKey("commandId") ? CommandId.of(json.getString("commandId")) : null);
            case "FaultStateChanged" -> new FaultStateChanged(
                    ZoneId.of(json.getString("zone")),
                    MonitorState.valueOf(json.getString("state")),
                    FaultClassification.valueOf(json.getString("classification")),
                    strings(json),
                    at,
                    json.containsKey("caseId") ? CaseId.of(json.getString("caseId")) : null);
            case "StrategyChanged" -> new StrategyChanged(
                    ZoneId.of(json.getString("zone")),
                    ControlMode.valueOf(json.getString("mode")),
                    json.containsKey("trimmedPosition")
                            ? json.getJsonNumber("trimmedPosition").doubleValue() : null,
                    at,
                    json.containsKey("caseId") ? CaseId.of(json.getString("caseId")) : null);
            case "ServiceReported" -> new ServiceReported(
                    ZoneId.of(json.getString("zone")),
                    CaseId.of(json.getString("caseId")),
                    at,
                    json.getString("reason"));
            default -> throw new IllegalArgumentException(
                    "unknown event type: " + json.getString("type"));
        };
    }

    private static List<String> strings(JsonObject json) {
        if (!json.containsKey("evidence")) {
            return List.of();
        }
        return json.getJsonArray("evidence").stream()
                .filter(v -> v.getValueType() == JsonValue.ValueType.STRING)
                .map(v -> ((JsonString) v).getString())
                .toList();
    }
}
