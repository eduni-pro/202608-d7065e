package se.ltu.d7065e.physics.control;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.physics.entity.FaultKind;
import se.ltu.d7065e.physics.entity.InjectedFault;
import se.ltu.d7065e.ventilation.entity.Topology;

/** T-14: the env- and file-driven fault configuration the containerised demo injects with. */
class FaultSpecParserTest {

    private static final Instant START = Instant.parse("2026-10-05T08:00:00Z");

    @Test
    @DisplayName("T-14: a per-room spec with a relative onset parses into one fault")
    void shouldParseARoomFaultWithARelativeOnset() {
        List<InjectedFault> faults = FaultSpecParser.parse("STUCK_DAMPER:A115:0.15:T+1800", START);

        InjectedFault fault = faults.getFirst();
        assertAll(
                () -> assertEquals(1, faults.size()),
                () -> assertEquals(FaultKind.STUCK_DAMPER, fault.kind()),
                () -> assertEquals(Topology.A115, fault.zone()),
                () -> assertEquals(0.15, fault.magnitude(), 1e-12),
                () -> assertEquals(START.plusSeconds(1800), fault.onset()));
    }

    @Test
    @DisplayName("T-14: several specs, comments and an absolute onset parse together")
    void shouldParseSeveralSpecsWithCommentsAndAbsoluteOnsets() {
        String spec = """
                # the demo's injection plan
                STUCK_DAMPER:A115:0.15:T+1800
                BLOCKED_FILTER:PLANT:0.4:T+7200
                SENSOR_DRIFT:A124:150:2026-10-05T09:00:00Z
                """;

        List<InjectedFault> faults = FaultSpecParser.parse(spec, START);

        assertAll(
                () -> assertEquals(3, faults.size()),
                () -> assertEquals(null, faults.get(1).zone(), "a blocked filter is plant-wide"),
                () -> assertEquals(0.4, faults.get(1).magnitude(), 1e-12),
                () -> assertEquals(Instant.parse("2026-10-05T09:00:00Z"), faults.get(2).onset()),
                () -> assertEquals(150, faults.get(2).magnitude(), 1e-12));
    }

    @Test
    @DisplayName("T-14: specs separated by semicolons parse, so one env variable carries them all")
    void shouldParseSemicolonSeparatedSpecs() {
        List<InjectedFault> faults = FaultSpecParser.parse(
                "STUCK_DAMPER:A115:0.15:T+1800;BLOCKED_FILTER:PLANT:0.4:T+7200", START);

        assertEquals(2, faults.size());
    }

    @Test
    @DisplayName("T-14: a malformed spec is refused at the boundary rather than half applied")
    void shouldRefuseMalformedSpecs() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> FaultSpecParser.parse("STUCK_DAMPER:A115:0.15", START),
                        "too few fields"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> FaultSpecParser.parse("MELTED_DUCT:A115:0.15:T+1", START),
                        "unknown kind"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> FaultSpecParser.parse("BLOCKED_FILTER:A115:0.4:T+1", START),
                        "a plant-wide fault takes no room"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> FaultSpecParser.parse("BLOCKED_FILTER:PLANT:1.4:T+1", START),
                        "beta outside (0,1)"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> FaultSpecParser.parse("STUCK_DAMPER:A115:1.5:T+1", START),
                        "a damper position outside [0,1]"));
    }

    /**
     * The spec the shipped {@code docker-compose.demo.yml} falls back to when the operator sets
     * nothing. It is pinned here because {@code SENSOR_OFFSET} was added to {@link FaultKind} after
     * the grammar was published, and a new kind must cost the grammar nothing: a spec that worked
     * before the constant existed has to parse to the same fault after it.
     */
    @Test
    @DisplayName("T-14: the shipped default spec still parses unchanged after a kind is added")
    void shouldStillParseTheShippedDefaultSpec() {
        List<InjectedFault> faults = FaultSpecParser.parse("STUCK_DAMPER:A115:0.15:T+5400", START);

        InjectedFault fault = faults.getFirst();
        assertAll(
                () -> assertEquals(1, faults.size()),
                () -> assertEquals(FaultKind.STUCK_DAMPER, fault.kind()),
                () -> assertEquals(Topology.A115, fault.zone()),
                () -> assertEquals(0.15, fault.magnitude(), 1e-12),
                () -> assertEquals(START.plusSeconds(5400), fault.onset()));
    }

    @Test
    @DisplayName("T-15: a step sensor offset is injectable through the same four-field grammar")
    void shouldParseAStepSensorOffsetWithoutWideningTheGrammar() {
        List<InjectedFault> faults = FaultSpecParser.parse(
                "SENSOR_OFFSET:A124:350:T+3600;SENSOR_DRIFT:A124:60:T+3600", START);

        assertAll(
                () -> assertEquals(2, faults.size()),
                () -> assertEquals(FaultKind.SENSOR_OFFSET, faults.getFirst().kind()),
                () -> assertEquals(Topology.A124, faults.getFirst().zone()),
                () -> assertEquals(350, faults.getFirst().magnitude(), 1e-12),
                () -> assertEquals(FaultKind.SENSOR_DRIFT, faults.get(1).kind(),
                        "the two sensor faults are different kinds and must stay tellable apart"));
    }

    @Test
    @DisplayName("T-14: a sensor offset of zero ppm is refused, like a drift of zero ppm/h")
    void shouldRefuseASensorOffsetOfZero() {
        assertThrows(IllegalArgumentException.class,
                () -> FaultSpecParser.parse("SENSOR_OFFSET:A124:0:T+1", START));
    }

    @Test
    @DisplayName("T-32: a sensor offset may be negative, which is the under-reading sensor")
    void shouldParseANegativeSensorOffset() {
        assertEquals(-250, FaultSpecParser
                .parse("SENSOR_OFFSET:A124:-250:T+1", START).getFirst().magnitude(), 1e-12);
    }

    @Test
    @DisplayName("T-14: an empty specification injects nothing")
    void shouldParseAnEmptySpecificationAsNoFaults() {
        assertEquals(List.of(), FaultSpecParser.parse("\n# nothing today\n", START));
    }
}
