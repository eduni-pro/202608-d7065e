package se.ltu.d7065e.dashboard.boundary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.json.Json;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.shared.control.EventPublisherPort;
import se.ltu.d7065e.shared.event.DomainEvent;
import se.ltu.d7065e.shared.event.FaultStateChanged;
import se.ltu.d7065e.shared.event.ServiceReported;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * D-10 — what an operator gets back from {@code POST /api/zones/{zone}/cases/{caseId}/serviced}.
 *
 * <p>The three refusals matter as much as the publish: the monitor's own copy of this validation
 * has nowhere to answer on MQTT, so this endpoint is the only place a mistaken repair report is
 * answered rather than dropped.
 */
class ServiceResourceTest {

    private static final ZoneId FAULTED = Topology.A115;
    private static final CaseId CASE = CaseId.of("case-0001");
    private static final Instant AT = Instant.parse("2026-10-07T08:00:00Z");

    private BrokerRuntime runtime;
    private ServiceResource resource;

    @BeforeEach
    void setUp() {
        runtime = new BrokerRuntime();
        resource = new ServiceResource(runtime);
    }

    @Test
    void shouldPublishARepairReportForALatchedCase() {
        latch(FAULTED, CASE, MonitorState.COMPENSATING);

        Response response = resource.serviced(FAULTED.value(), CASE.value(), "damper replaced");

        assertEquals(Response.Status.ACCEPTED.getStatusCode(), response.getStatus(),
                "the monitor closes the case in another process, so this cannot report 200");
        assertEquals(1, runtime.published.size());
        assertEquals("service/A115", runtime.published.getFirst().topic());
        ServiceReported report = (ServiceReported) runtime.published.getFirst().event();
        assertEquals(FAULTED, report.zone());
        assertEquals(CASE, report.caseId());
        assertEquals("damper replaced", report.reason());
        assertEquals("damper replaced", detailOf(response, "reason"));
    }

    @Test
    void shouldRecordThatNoAccountOfTheRepairWasGiven() {
        latch(FAULTED, CASE, MonitorState.CONFIRMED);

        resource.serviced(FAULTED.value(), CASE.value(), " ");

        assertEquals(ServiceResource.UNSTATED_REASON,
                ((ServiceReported) runtime.published.getFirst().event()).reason());
    }

    @Test
    void shouldRefuseARoomThatIsNotInTheTopology() {
        Response response = resource.serviced("B200", CASE.value(), "repaired");

        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
        assertTrue(detailOf(response, "detail").contains("B200"));
        assertEquals(List.of(), runtime.published, "a refused report must not reach the broker");
    }

    @Test
    void shouldRefuseARoomNameThatIsNotEvenAZoneId() {
        for (String nonsense : List.of("", " ", "level0/A115")) {
            Response response = resource.serviced(nonsense, CASE.value(), "repaired");
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus(),
                    "a room name no zone id could hold must be a 404 and not a 500: '%s'"
                            .formatted(nonsense));
        }
    }

    @Test
    void shouldRefuseARoomWithNoOpenCase() {
        Response response = resource.serviced(FAULTED.value(), CASE.value(), "repaired");

        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
        assertTrue(detailOf(response, "detail").contains("no open case"));
        assertEquals(List.of(), runtime.published);
    }

    @Test
    void shouldRefuseACaseIdThatIsNotTheOneOpenInTheRoom() {
        latch(FAULTED, CASE, MonitorState.CONFIRMED);

        Response response = resource.serviced(FAULTED.value(), "a-case-from-last-week", "repaired");

        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
        assertTrue(detailOf(response, "detail").contains("case-0001"),
                "the refusal has to name the case that is actually open, or the operator cannot "
                        + "correct the request");
        assertEquals(List.of(), runtime.published);
    }

    @Test
    void shouldRefuseACaseThatIsSuspectedButNotLatched() {
        runtime.projection().observe(new FaultStateChanged(FAULTED, MonitorState.SUSPECT,
                FaultClassification.NONE, List.of(), ObservedAt.of(AT), CASE));

        Response response = resource.serviced(FAULTED.value(), CASE.value(), "repaired");

        assertEquals(Response.Status.CONFLICT.getStatusCode(), response.getStatus(),
                "a suspicion clears itself; closing it as a repair would discard evidence");
        assertTrue(detailOf(response, "detail").contains("SUSPECT"));
        assertEquals(List.of(), runtime.published);
    }

    @Test
    void shouldRefuseABlankCaseId() {
        Response response = resource.serviced(FAULTED.value(), " ", "repaired");

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    }

    @Test
    void shouldSayTheReportCannotBeCarriedWhenThereIsNoBroker() {
        ServiceResource deaf = new ServiceResource(
                new DashboardRuntime(Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), 180));

        Response response = deaf.serviced(FAULTED.value(), CASE.value(), "repaired");

        assertEquals(Response.Status.SERVICE_UNAVAILABLE.getStatusCode(), response.getStatus(),
                "without a broker the view has seen no case either, and reporting that would name "
                        + "the symptom instead of the cause");
        assertTrue(detailOf(response, "detail").contains("no broker is configured"));
    }

    private void latch(ZoneId zone, CaseId caseId, MonitorState state) {
        runtime.projection().observe(new FaultStateChanged(zone, state,
                FaultClassification.STUCK_DAMPER, List.of("the damper ignored its command"),
                ObservedAt.of(AT), caseId));
    }

    private static String detailOf(Response response, String field) {
        try (var reader = Json.createReader(new StringReader((String) response.getEntity()))) {
            return reader.readObject().getString(field);
        }
    }

    /**
     * The real runtime with a readable broker in place of a reachable one. Only the publisher is
     * replaced: the projection under test is the one the container serves, so a refusal here is a
     * refusal against the same view the operator was looking at.
     */
    private static final class BrokerRuntime extends DashboardRuntime {

        private final List<Published> published = new ArrayList<>();

        private BrokerRuntime() {
            super(Optional.of("tcp://broker:1883"), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), 180);
        }

        @Override
        public EventPublisherPort publisher() {
            return (topic, event) -> published.add(new Published(topic, event));
        }

        private record Published(String topic, DomainEvent event) {}
    }
}
