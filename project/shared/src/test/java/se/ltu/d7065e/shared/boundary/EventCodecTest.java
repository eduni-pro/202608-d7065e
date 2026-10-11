package se.ltu.d7065e.shared.boundary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
 * The wire contract (ADR-0006: the domain event <em>is</em> the payload; plan §3 fixes the
 * field names). Seven containers encode and decode these payloads independently, so a renamed
 * field is a silent cross-container break — these tests parse the encoded JSON and assert on
 * the key names themselves rather than only on a successful round trip.
 */
class EventCodecTest {

    private static final ObservedAt AT = ObservedAt.of(Instant.parse("2026-10-05T08:30:00Z"));
    private static final ZoneId ZONE = ZoneId.of("A115");
    private static final AhuId AHU = AhuId.of("AHU-A120");
    private static final CommandId COMMAND = CommandId.of("cmd-0001");
    private static final CaseId CASE = CaseId.of("case-0001");

    /**
     * The field vocabulary plan §3 declares for its seven topics. The eighth topic,
     * {@code service/{zone}} (D-10), reuses it rather than extending it: a service report is
     * {@code zone}, {@code caseId}, {@code reason} and {@code ts}, all four already declared.
     */
    private static final Set<String> DECLARED_FIELDS = Set.of(
            "zone", "ppm", "ts", "quality", "position", "reason", "commandId", "residual",
            "cusum", "state", "classification", "evidence", "caseId", "mode", "watts", "ahu",
            "speed");

    /**
     * {@code type} is the discriminator ADR-0006 adds so one subscriber can decode a mixed
     * stream; {@code trimmedPosition} is ADR-0008 consequence 6 on {@code strategy/{zone}}.
     */
    private static final Set<String> CODEC_ADDITIONS = Set.of("type", "trimmedPosition");

    private static ReadingObserved reading() {
        return new ReadingObserved(ZONE, Ppm.of(812.5), AT, Quality.GOOD);
    }

    private static FanPowerObserved fanPower() {
        return new FanPowerObserved(AHU, FanPower.ofWatts(312.75), AT, Quality.STALE);
    }

    private static CommandIssued command() {
        return new CommandIssued(ZONE, DamperPosition.of(0.65), AT, "co2 above setpoint", COMMAND);
    }

    private static FanSpeedCommanded fanSpeed() {
        return new FanSpeedCommanded(AHU, FanSpeed.of(0.85), AT, "compensating A115", COMMAND);
    }

    private static ResidualComputed residual() {
        return new ResidualComputed(ZONE, Residual.of(-42.5), CusumStatistic.of(120.0), AT, COMMAND);
    }

    private static FaultStateChanged fault() {
        return new FaultStateChanged(ZONE, MonitorState.CONFIRMED, FaultClassification.STUCK_DAMPER,
                List.of("cusum over h=150 ppm", "flow did not follow the command"), AT, CASE);
    }

    private static StrategyChanged strategy() {
        return new StrategyChanged(ZONE, ControlMode.COMPENSATING, 0.35, AT, CASE);
    }

    private static ServiceReported service() {
        return new ServiceReported(ZONE, CASE, AT, "damper actuator replaced");
    }

    /** All eight event types of the sealed {@code DomainEvent} hierarchy. */
    static List<DomainEvent> allEventTypes() {
        return List.of(reading(), fanPower(), command(), fanSpeed(), residual(), fault(),
                strategy(), service());
    }

    private static JsonObject parse(String payload) {
        try (var reader = Json.createReader(new StringReader(payload))) {
            return reader.readObject();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allEventTypes")
    @DisplayName("ADR-0006: encode then decode returns an equal record, for all eight types")
    void shouldRoundTripEveryEventType(DomainEvent event) {
        assertEquals(event, EventCodec.decode(EventCodec.encode(event)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allEventTypes")
    @DisplayName("plan §3: every field name is from the declared vocabulary")
    void shouldUseOnlyDeclaredFieldNames(DomainEvent event) {
        for (String key : parse(EventCodec.encode(event)).keySet()) {
            assertTrue(DECLARED_FIELDS.contains(key) || CODEC_ADDITIONS.contains(key),
                    "undeclared wire field '%s' on %s".formatted(key, event.getClass().getSimpleName()));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allEventTypes")
    @DisplayName("ADR-0006: ts carries the observation instant, type the record name")
    void shouldCarryTypeDiscriminatorAndTimestamp(DomainEvent event) {
        JsonObject json = parse(EventCodec.encode(event));
        assertEquals(event.getClass().getSimpleName(), json.getString("type"));
        assertEquals("2026-10-05T08:30:00Z", json.getString("ts"));
        assertEquals(AT, EventCodec.decode(EventCodec.encode(event)).at());
    }

    @Test
    @DisplayName("readings/{zone}/co2 is exactly {zone, ppm, ts, quality}")
    void shouldEncodeReadingWithTheDeclaredFields() {
        JsonObject json = parse(EventCodec.encode(reading()));
        assertEquals(Set.of("type", "zone", "ppm", "ts", "quality"), json.keySet());
        assertEquals("A115", json.getString("zone"));
        assertEquals(812.5, json.getJsonNumber("ppm").doubleValue(), 1e-12);
        assertEquals("GOOD", json.getString("quality"));
    }

    @Test
    @DisplayName("readings/{ahu}/fan-power is exactly {ahu, watts, ts, quality}")
    void shouldEncodeFanPowerWithTheDeclaredFields() {
        JsonObject json = parse(EventCodec.encode(fanPower()));
        assertEquals(Set.of("type", "ahu", "watts", "ts", "quality"), json.keySet());
        assertEquals("AHU-A120", json.getString("ahu"));
        assertEquals(312.75, json.getJsonNumber("watts").doubleValue(), 1e-12);
        assertEquals("STALE", json.getString("quality"));
    }

    @Test
    @DisplayName("commands/{zone}/damper is exactly {zone, position, ts, reason, commandId}")
    void shouldEncodeDamperCommandWithTheDeclaredFields() {
        JsonObject json = parse(EventCodec.encode(command()));
        assertEquals(Set.of("type", "zone", "position", "ts", "reason", "commandId"), json.keySet());
        assertEquals(0.65, json.getJsonNumber("position").doubleValue(), 1e-12);
        assertEquals("co2 above setpoint", json.getString("reason"));
        assertEquals("cmd-0001", json.getString("commandId"));
    }

    @Test
    @DisplayName("commands/{ahu}/fan-speed is exactly {ahu, speed, ts, reason, commandId}")
    void shouldEncodeFanSpeedCommandWithTheDeclaredFields() {
        JsonObject json = parse(EventCodec.encode(fanSpeed()));
        assertEquals(Set.of("type", "ahu", "speed", "ts", "reason", "commandId"), json.keySet());
        assertEquals(0.85, json.getJsonNumber("speed").doubleValue(), 1e-12);
        assertEquals("compensating A115", json.getString("reason"));
    }

    @Test
    @DisplayName("residuals/{zone} is exactly {zone, residual, cusum, ts, commandId}")
    void shouldEncodeResidualWithTheDeclaredFields() {
        JsonObject json = parse(EventCodec.encode(residual()));
        assertEquals(Set.of("type", "zone", "residual", "cusum", "ts", "commandId"), json.keySet());
        assertEquals(-42.5, json.getJsonNumber("residual").doubleValue(), 1e-12);
        assertEquals(120.0, json.getJsonNumber("cusum").doubleValue(), 1e-12);
        assertEquals("cmd-0001", json.getString("commandId"));
    }

    @Test
    @DisplayName("faults/{zone} is exactly {zone, state, classification, evidence, ts, caseId}")
    void shouldEncodeFaultStateWithTheDeclaredFields() {
        JsonObject json = parse(EventCodec.encode(fault()));
        assertEquals(Set.of("type", "zone", "state", "classification", "evidence", "ts", "caseId"),
                json.keySet());
        assertEquals("CONFIRMED", json.getString("state"));
        assertEquals("STUCK_DAMPER", json.getString("classification"));
        assertEquals(2, json.getJsonArray("evidence").size());
        assertEquals("cusum over h=150 ppm", json.getJsonArray("evidence").getString(0));
        assertEquals("case-0001", json.getString("caseId"));
    }

    @Test
    @DisplayName("strategy/{zone} is {zone, mode, ts, caseId} plus the ADR-0008 trim")
    void shouldEncodeStrategyWithTheDeclaredFields() {
        JsonObject json = parse(EventCodec.encode(strategy()));
        assertEquals(Set.of("type", "zone", "mode", "ts", "caseId", "trimmedPosition"),
                json.keySet());
        assertTrue(json.keySet().containsAll(Set.of("zone", "mode", "ts", "caseId")));
        assertEquals("COMPENSATING", json.getString("mode"));
        assertEquals(0.35, json.getJsonNumber("trimmedPosition").doubleValue(), 1e-12);
    }

    @Test
    @DisplayName("D-11: DAMPER_STUCK_OPEN crosses the wire under exactly that name")
    void shouldCarryTheStuckOpenClassificationOnTheWire() {
        FaultStateChanged stuckOpen = new FaultStateChanged(ZONE, MonitorState.CONFIRMED,
                FaultClassification.DAMPER_STUCK_OPEN, List.of("damper stuck WIDE OPEN"), AT, CASE);

        String encoded = EventCodec.encode(stuckOpen);

        // The literal, not only the round trip. `classification` is an enum name on the wire, so
        // this string is the contract three containers match on; the round trip would still pass if
        // the constant were renamed on both sides at once, and a subscriber built against the old
        // name would then drop every message.
        assertEquals("DAMPER_STUCK_OPEN", parse(encoded).getString("classification"));
        assertEquals(stuckOpen, EventCodec.decode(encoded));
        assertEquals(FaultClassification.DAMPER_STUCK_OPEN,
                ((FaultStateChanged) EventCodec.decode(encoded)).classification());
    }

    @Test
    @DisplayName("service/{zone} is exactly {zone, caseId, reason, ts}")
    void shouldEncodeServiceReportWithTheDeclaredFields() {
        JsonObject json = parse(EventCodec.encode(service()));
        assertEquals(Set.of("type", "zone", "caseId", "reason", "ts"), json.keySet());
        assertEquals("A115", json.getString("zone"));
        assertEquals("case-0001", json.getString("caseId"));
        assertEquals("damper actuator replaced", json.getString("reason"));
    }

    @Test
    @DisplayName("D-10: a service report names its case, so a late report cannot clear the next one")
    void shouldRefuseAServiceReportWithoutACase() {
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceReported(ZONE, null, AT, "damper actuator replaced"));
        assertThrows(IllegalArgumentException.class, () -> new ServiceReported(ZONE, CASE, AT, " "));
    }

    @Test
    @DisplayName("ResidualComputed round-trips with a null commandId (no command yet)")
    void shouldRoundTripResidualWithoutCommandId() {
        ResidualComputed unattributed =
                new ResidualComputed(ZONE, Residual.of(7.25), CusumStatistic.RESET, AT, null);
        String encoded = EventCodec.encode(unattributed);
        assertFalse(parse(encoded).containsKey("commandId"),
                "an absent commandId must be omitted, not encoded as null");
        DomainEvent decoded = EventCodec.decode(encoded);
        assertEquals(unattributed, decoded);
        assertNull(((ResidualComputed) decoded).commandId());
    }

    @Test
    @DisplayName("FaultStateChanged round-trips with a null caseId and an empty evidence list")
    void shouldRoundTripFaultStateWithoutCaseIdAndWithoutEvidence() {
        FaultStateChanged healthy = new FaultStateChanged(ZONE, MonitorState.HEALTHY,
                FaultClassification.NONE, List.of(), AT, null);
        String encoded = EventCodec.encode(healthy);
        JsonObject json = parse(encoded);
        assertFalse(json.containsKey("caseId"));
        assertTrue(json.containsKey("evidence"), "evidence stays on the wire even when empty");
        assertEquals(0, json.getJsonArray("evidence").size());
        DomainEvent decoded = EventCodec.decode(encoded);
        assertEquals(healthy, decoded);
        assertEquals(List.of(), ((FaultStateChanged) decoded).evidence());
        assertNull(((FaultStateChanged) decoded).caseId());
    }

    @Test
    @DisplayName("FaultStateChanged normalises a null evidence list to empty before the wire")
    void shouldRoundTripFaultStateWithNullEvidence() {
        FaultStateChanged blind = new FaultStateChanged(ZONE, MonitorState.BLIND,
                FaultClassification.NONE, null, AT, null);
        assertEquals(blind, EventCodec.decode(EventCodec.encode(blind)));
    }

    @Test
    @DisplayName("StrategyChanged round-trips with a null trimmedPosition (an untrimmed zone)")
    void shouldRoundTripStrategyWithoutTrimmedPosition() {
        StrategyChanged degraded =
                new StrategyChanged(ZONE, ControlMode.DEGRADED, null, AT, CASE);
        String encoded = EventCodec.encode(degraded);
        assertFalse(parse(encoded).containsKey("trimmedPosition"));
        DomainEvent decoded = EventCodec.decode(encoded);
        assertEquals(degraded, decoded);
        assertNull(((StrategyChanged) decoded).trimmedPosition());
    }

    @Test
    @DisplayName("StrategyChanged round-trips with neither a trim nor a case")
    void shouldRoundTripStrategyWithoutTrimOrCase() {
        StrategyChanged backToNormal =
                new StrategyChanged(ZONE, ControlMode.NORMAL, null, AT, null);
        JsonObject json = parse(EventCodec.encode(backToNormal));
        assertEquals(Set.of("type", "zone", "mode", "ts"), json.keySet());
        assertEquals(backToNormal, EventCodec.decode(EventCodec.encode(backToNormal)));
    }

    /** Every zone and every enum constant must survive the wire, not just the happy fixture. */
    @Test
    void shouldRoundTripEveryQualityAndMonitorStateAndMode() {
        for (Quality quality : Quality.values()) {
            ReadingObserved event = new ReadingObserved(ZONE, Ppm.of(420), AT, quality);
            assertEquals(event, EventCodec.decode(EventCodec.encode(event)));
        }
        for (MonitorState state : MonitorState.values()) {
            FaultClassification classification = state.isLatched()
                    ? FaultClassification.UNCLASSIFIED : FaultClassification.NONE;
            FaultStateChanged event = new FaultStateChanged(ZONE, state, classification,
                    List.of("e"), AT, CASE);
            assertEquals(event, EventCodec.decode(EventCodec.encode(event)));
        }
        for (ControlMode mode : ControlMode.values()) {
            StrategyChanged event = new StrategyChanged(ZONE, mode, 0.15, AT, CASE);
            assertEquals(event, EventCodec.decode(EventCodec.encode(event)));
        }
        for (FaultClassification classification : FaultClassification.values()) {
            MonitorState state = classification == FaultClassification.NONE
                    ? MonitorState.HEALTHY : MonitorState.CONFIRMED;
            FaultStateChanged event = new FaultStateChanged(ZONE, state, classification,
                    List.of(), AT, CASE);
            assertEquals(event, EventCodec.decode(EventCodec.encode(event)));
        }
    }

    @Test
    @DisplayName("boundary values survive the wire unrounded")
    void shouldRoundTripBoundaryValues() {
        assertEquals(new ReadingObserved(ZONE, Ppm.of(0), AT, Quality.MISSING),
                EventCodec.decode(EventCodec.encode(
                        new ReadingObserved(ZONE, Ppm.of(0), AT, Quality.MISSING))));
        assertEquals(new CommandIssued(ZONE, DamperPosition.SHUT, AT, "shut", COMMAND),
                EventCodec.decode(EventCodec.encode(
                        new CommandIssued(ZONE, DamperPosition.SHUT, AT, "shut", COMMAND))));
        assertEquals(new CommandIssued(ZONE, DamperPosition.OPEN, AT, "open", COMMAND),
                EventCodec.decode(EventCodec.encode(
                        new CommandIssued(ZONE, DamperPosition.OPEN, AT, "open", COMMAND))));
        assertEquals(new FanSpeedCommanded(AHU, FanSpeed.of(FanSpeed.MINIMUM), AT, "idle", COMMAND),
                EventCodec.decode(EventCodec.encode(
                        new FanSpeedCommanded(AHU, FanSpeed.of(FanSpeed.MINIMUM), AT, "idle", COMMAND))));
        assertEquals(new FanSpeedCommanded(AHU, FanSpeed.NOMINAL, AT, "full", COMMAND),
                EventCodec.decode(EventCodec.encode(
                        new FanSpeedCommanded(AHU, FanSpeed.NOMINAL, AT, "full", COMMAND))));
    }

    @Test
    @DisplayName("sub-second timestamps survive the wire, so R-5 can order a case")
    void shouldRoundTripSubSecondTimestamps() {
        ObservedAt precise = ObservedAt.of(Instant.parse("2026-10-05T08:30:00.123456789Z"));
        ReadingObserved event = new ReadingObserved(ZONE, Ppm.of(500), precise, Quality.GOOD);
        assertEquals(event, EventCodec.decode(EventCodec.encode(event)));
    }

    @Test
    @DisplayName("a mixed stream carrying an unknown type must fail loudly, not be dropped")
    void shouldRejectUnknownEventType() {
        String payload = """
                {"type":"FaultCleared","ts":"2026-10-05T08:30:00Z","zone":"A115"}""";
        var thrown = assertThrows(IllegalArgumentException.class, () -> EventCodec.decode(payload));
        assertTrue(thrown.getMessage().contains("FaultCleared"),
                "the message must name the type it could not decode");
    }

    @Test
    @DisplayName("a payload carrying a BuildSim key as the zone is refused at the boundary")
    void shouldRejectBuildSimKeyAsZoneOnTheWire() {
        String payload = """
                {"type":"ReadingObserved","ts":"2026-10-05T08:30:00Z",\
                "zone":"level0/A115","ppm":812.5,"quality":"GOOD"}""";
        assertThrows(IllegalArgumentException.class, () -> EventCodec.decode(payload));
    }

    // ---------------------------------------------------------------------------------------
    // Documented bugs. These two tests state the behaviour the contract needs and are disabled
    // because main does not have it yet. They are not fixed here: every other container
    // compiles against these records, so the signatures stay untouched.
    // ---------------------------------------------------------------------------------------

    /**
     * Every other numeric field on the wire is a validated value object — {@code position} is a
     * {@link DamperPosition}, {@code speed} a {@link FanSpeed}, {@code ppm} a {@link Ppm}. The
     * trim on {@code strategy/{zone}} is a bare {@code Double}, so a supervisor arithmetic slip
     * travels to {@code dcv-controller} and {@code damper-actuator} as a damper position outside
     * [0,1], where it will blow up far from its cause (ADR-0006, ADR-0008 consequence 6).
     */
    @Test
    @DisplayName("ADR-0006: a trimmed position outside [0,1] must be refused on the wire")
    void shouldRejectTrimmedPositionOutsideUnitInterval() {
        assertThrows(IllegalArgumentException.class,
                () -> new StrategyChanged(ZONE, ControlMode.COMPENSATING, 1.5, AT, CASE));
        assertThrows(IllegalArgumentException.class,
                () -> new StrategyChanged(ZONE, ControlMode.COMPENSATING, -0.3, AT, CASE));
    }

    /**
     * R-5 requires every diagnosis to be reconstructable. {@code FaultCase} enforces "a latched
     * case must carry a classification, even UNCLASSIFIED", but {@code FaultStateChanged} does
     * not, so {@code fault-monitor} can publish {@code {state: CONFIRMED, classification: NONE}}
     * — an event that encodes and decodes cleanly yet throws when a subscriber rebuilds the
     * aggregate from it. The invariant has to hold on both sides of the wire or neither.
     */
    @Test
    @DisplayName("R-5: a latched fault event must carry a classification, even UNCLASSIFIED")
    void shouldRejectLatchedFaultEventWithoutClassification() {
        assertThrows(IllegalArgumentException.class, () -> new FaultStateChanged(
                ZONE, MonitorState.CONFIRMED, FaultClassification.NONE, List.of(), AT, CASE));
        assertThrows(IllegalArgumentException.class, () -> new FaultStateChanged(
                ZONE, MonitorState.COMPENSATING, FaultClassification.NONE, List.of(), AT, CASE));
    }
}
