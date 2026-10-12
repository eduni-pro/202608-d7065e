package se.ltu.d7065e.diagnosis.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.diagnosis.boundary.ServiceRequests;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.shared.boundary.EventCodec;
import se.ltu.d7065e.shared.event.ServiceReported;
import se.ltu.d7065e.shared.event.Topics;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * D-10 — the transport for the one exit from a latched case, and the refusals that guard it.
 *
 * <p>The subject is {@link ServiceRequests}, which is a boundary class; the test sits here because
 * this is where {@link MonitorHarness} lives, and only a harness that can drive a real confirmation
 * out of the physics can produce the latched case the transport exists to clear. A fabricated state
 * would test the validation and not the edge.
 *
 * <p>Every report is encoded and decoded through {@link EventCodec} first, so what reaches the
 * adapter is what would arrive on {@code service/{zone}} rather than an object handed over
 * in-process.
 */
class ServiceTransportTest {

    private static final ZoneId FAULTED = Topology.A115;
    private static final Duration BUDGET = Duration.ofMinutes(15);

    @Test
    void shouldCarryAServiceReportOnItsOwnTopicAndNotAnActuatorTopic() {
        assertEquals("service/A115", Topics.service(FAULTED));
        assertTrue(Topics.ALL_SERVICE_REPORTS.startsWith("service/"),
                "a repair report must not travel on a commands/ topic: nothing but the inner loop "
                        + "and the supervisor may write to an actuator");
    }

    @Test
    void shouldClearALatchedCaseThroughTheServiceTopicWithoutRestartingAnything() {
        MonitorHarness harness = confirmedHarness(42);
        ServiceRequests requests = new ServiceRequests(harness.monitor());
        CaseId latched = harness.monitor().caseOf(FAULTED).id();

        harness.command(FAULTED, 1.0, "supervisor: compensate a latched zone");
        harness.tick();
        assertEquals(MonitorState.COMPENSATING, harness.monitor().stateOf(FAULTED));

        harness.physics().unstick(FAULTED);
        requests.accept(onTheWire(new ServiceReported(FAULTED, latched,
                ObservedAt.of(harness.now()), "damper actuator replaced")));

        assertEquals(MonitorState.SERVICED, harness.monitor().stateOf(FAULTED));
        harness.tick();
        assertEquals(MonitorState.WARMUP, harness.monitor().stateOf(FAULTED));
        assertNull(harness.monitor().caseOf(FAULTED), "service must close the case, not keep it");
        harness.tickFor(Duration.ofMinutes(10));
        assertEquals(MonitorState.HEALTHY, harness.monitor().stateOf(FAULTED),
                "a room cleared over the service topic must be able to become healthy again");
    }

    @Test
    void shouldTellTheSupervisorTheCaseClearedByPublishingServicedOnTheFaultTopic() {
        MonitorHarness harness = confirmedHarness(42);
        ServiceRequests requests = new ServiceRequests(harness.monitor());
        CaseId latched = harness.monitor().caseOf(FAULTED).id();
        harness.events().clear();

        requests.accept(onTheWire(new ServiceReported(FAULTED, latched,
                ObservedAt.of(harness.now()), "damper actuator replaced")));

        assertTrue(harness.events().states(FAULTED).stream()
                        .anyMatch(e -> e.state() == MonitorState.SERVICED
                                && latched.equals(e.caseId())),
                "the supervisor releases on SERVICED arriving over MQTT, so clearing the case has "
                        + "to publish it on faults/" + FAULTED.value());
    }

    @Test
    void shouldRefuseAReportForAZoneTheMonitorDoesNotWatch() {
        MonitorHarness harness = confirmedHarness(42);
        ServiceRequests requests = new ServiceRequests(harness.monitor());

        var thrown = assertThrows(IllegalArgumentException.class,
                () -> requests.accept(onTheWire(new ServiceReported(ZoneId.of("B200"),
                        CaseId.of("case-0001"), ObservedAt.of(harness.now()), "repaired"))));

        assertTrue(thrown.getMessage().contains("B200"), thrown.getMessage());
        assertTrue(harness.monitor().stateOf(FAULTED).isLatched(),
                "a refused report must leave every case where it was");
    }

    @Test
    void shouldRefuseAReportNamingACaseThatIsNotTheOneOpenInThatZone() {
        MonitorHarness harness = confirmedHarness(42);
        ServiceRequests requests = new ServiceRequests(harness.monitor());
        CaseId latched = harness.monitor().caseOf(FAULTED).id();

        var thrown = assertThrows(IllegalArgumentException.class,
                () -> requests.accept(onTheWire(new ServiceReported(FAULTED,
                        CaseId.of("a-case-from-last-week"), ObservedAt.of(harness.now()),
                        "repaired"))));

        assertTrue(thrown.getMessage().contains("a-case-from-last-week"), thrown.getMessage());
        assertEquals(latched, harness.monitor().caseOf(FAULTED).id(),
                "a stale report must not clear whatever case the room has now");
        assertTrue(harness.monitor().stateOf(FAULTED).isLatched());
    }

    @Test
    void shouldRefuseAReportForARoomWithNoOpenCase() {
        MonitorHarness harness = settledHarness(42);
        ServiceRequests requests = new ServiceRequests(harness.monitor());
        assertNull(harness.monitor().caseOf(Topology.A124), "the fixture needs a quiet room");

        var thrown = assertThrows(IllegalArgumentException.class,
                () -> requests.accept(onTheWire(new ServiceReported(Topology.A124,
                        CaseId.of("case-0001"), ObservedAt.of(harness.now()), "repaired"))));

        assertTrue(thrown.getMessage().contains("no open case"), thrown.getMessage());
    }

    @Test
    void shouldRefuseAReportForACaseThatIsSuspectedButNotLatched() {
        MonitorHarness harness = suspectHarness(42);
        ServiceRequests requests = new ServiceRequests(harness.monitor());
        CaseId suspected = harness.monitor().caseOf(FAULTED).id();

        var thrown = assertThrows(IllegalStateException.class,
                () -> requests.accept(onTheWire(new ServiceReported(FAULTED, suspected,
                        ObservedAt.of(harness.now()), "repaired"))));

        assertTrue(thrown.getMessage().contains("SUSPECT"), thrown.getMessage());
        assertEquals(MonitorState.SUSPECT, harness.monitor().stateOf(FAULTED),
                "a suspicion is reversible on its own and must not be closed as a repair");
    }

    @Test
    void shouldIgnoreAnEventThatIsNotAServiceReport() {
        MonitorHarness harness = confirmedHarness(42);
        ServiceRequests requests = new ServiceRequests(harness.monitor());

        requests.accept(harness.events().all().getFirst().event());

        assertTrue(harness.monitor().stateOf(FAULTED).isLatched());
    }

    /** What the broker would hand the adapter, rather than the object the test built. */
    private static ServiceReported onTheWire(ServiceReported report) {
        return (ServiceReported) EventCodec.decode(EventCodec.encode(report));
    }

    private MonitorHarness settledHarness(long seed) {
        MonitorHarness harness = MonitorHarness.standard(seed);
        harness.innerLoopRunning(false);
        for (ZoneId zone : Topology.ZONE_IDS) {
            harness.physics().occupyToDesign(zone);
            harness.physics().settle(zone);
        }
        harness.tickFor(Duration.ofHours(1));
        return harness;
    }

    private MonitorHarness suspectHarness(long seed) {
        MonitorHarness harness = settledHarness(seed);
        harness.physics().stick(FAULTED, Topology.MINIMUM_POSITION.value());
        harness.command(FAULTED, 0.80, "the first ignored command");
        int cycles = harness.tickUntil(BUDGET,
                () -> harness.monitor().stateOf(FAULTED) == MonitorState.SUSPECT);
        assertNotEquals(-1, cycles, "the fixture needs a suspected case");
        return harness;
    }

    private MonitorHarness confirmedHarness(long seed) {
        MonitorHarness harness = settledHarness(seed);
        harness.physics().stick(FAULTED, Topology.MINIMUM_POSITION.value());
        harness.command(FAULTED, 0.80, "the first ignored command");
        int cycles = harness.tickUntil(BUDGET,
                () -> harness.monitor().stateOf(FAULTED) == MonitorState.CONFIRMED);
        assertNotEquals(-1, cycles, "the fixture needs a confirmed case to latch");
        return harness;
    }
}
