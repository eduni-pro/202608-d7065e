package se.ltu.d7065e.physics.boundary;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.physics.control.BuildingSimulation;
import se.ltu.d7065e.physics.entity.PhysicsSnapshot;
import se.ltu.d7065e.shared.boundary.SimulatedClock;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.Topology;

/**
 * The real surface on a real socket, driven by a real HTTP client, against a real
 * {@link BuildingSimulation} on a simulated clock. A stub of the handler would assert the
 * parsing and prove nothing about the thing that matters, which is that a curl changes what the
 * next tick of air actually does — so the assertions below mostly read the physics rather than
 * the status code.
 *
 * <p>Port 0, so the OS picks one and the suite can run next to a live stack holding 8081.
 */
class FaultControlSurfaceTest {

    private static final Duration TICK = Duration.ofSeconds(1);
    private static final Instant START =
            LocalDateTime.of(2026, 10, 9, 8, 0).toInstant(ZoneOffset.UTC);

    private final HttpClient client = HttpClient.newHttpClient();
    private BuildingSimulation simulation;
    private FaultControlSurface surface;
    private String endpoint;

    @BeforeEach
    void startSurface() throws Exception {
        SimulatedClock clock = new SimulatedClock(START.minusSeconds(1));
        simulation = new BuildingSimulation(clock, TICK, 42);
        surface = new FaultControlSurface(simulation, clock, 0);
        surface.start();
        endpoint = "http://127.0.0.1:" + surface.port() + "/faults";
    }

    @AfterEach
    void stopSurface() {
        surface.stop();
    }

    @Test
    @DisplayName("a stuck damper injected over HTTP holds the next tick's position against the command")
    void shouldApplyAnInjectedStuckDamperOnTheNextTick() throws Exception {
        simulation.commandDamper(Topology.A115, DamperPosition.OPEN);
        PhysicsSnapshot beforeInjection = simulation.advance();

        HttpResponse<String> response = post("STUCK_DAMPER:A115:0.15:T+0");
        PhysicsSnapshot afterInjection = simulation.advance();

        assertAll(
                () -> assertEquals(202, response.statusCode(),
                        "accepted: the plant takes it up on its next tick, " + response.body()),
                () -> assertTrue(response.body().contains("STUCK_DAMPER@A115"),
                        "the answer echoes what was injected: " + response.body()),
                () -> assertEquals(1.0, beforeInjection.appliedPosition(Topology.A115).value(),
                        1e-12, "the damper obeyed its command before the fault existed"),
                () -> assertEquals(0.15, afterInjection.appliedPosition(Topology.A115).value(),
                        1e-12, "and holds the frozen position afterwards, command unchanged"));
    }

    @Test
    @DisplayName("T+0 bites on the next tick, with no restart and no onset arithmetic")
    void shouldBiteImmediatelyWhenTheOnsetIsZero() throws Exception {
        simulation.commandDamper(Topology.A115, DamperPosition.OPEN);

        post("STUCK_DAMPER:A115:0.15:T+0");

        assertEquals(0.15, simulation.advance().appliedPosition(Topology.A115).value(), 1e-12);
        assertTrue(get().body().contains("ACTIVE"), "and it reports itself as biting: " + get().body());
    }

    /**
     * The semantic that differs from {@code FAULT_SPEC}: a {@code T+} onset on this surface is
     * counted from the simulated instant the request is handled, not from {@code START_AT}, so a
     * fault aimed five simulated minutes out waits five simulated minutes whenever it is sent.
     */
    @Test
    @DisplayName("a T+ onset is counted from the simulated now, not from the run's start")
    void shouldCountAFutureOnsetFromTheSimulatedNow() throws Exception {
        simulation.commandDamper(Topology.A115, DamperPosition.OPEN);

        HttpResponse<String> response = post("STUCK_DAMPER:A115:0.15:T+300");
        PhysicsSnapshot nextTick = simulation.advance();
        for (int tick = 0; tick < 300; tick++) {
            simulation.advance();
        }
        PhysicsSnapshot afterFiveMinutes = simulation.advance();

        assertAll(
                () -> assertTrue(response.body().contains("from " + START.plusSeconds(299)),
                        "onset is now plus 300 s, and now was one tick before START: "
                                + response.body()),
                () -> assertEquals(1.0, nextTick.appliedPosition(Topology.A115).value(), 1e-12,
                        "nothing bites yet, five simulated minutes before the onset"),
                () -> assertEquals(0.15, afterFiveMinutes.appliedPosition(Topology.A115).value(),
                        1e-12, "and it bites once the simulated clock reaches the onset"));
    }

    @Test
    @DisplayName("clearing one room's fault leaves another room's fault stuck")
    void shouldRemoveOnlyTheMatchingFault() throws Exception {
        post("STUCK_DAMPER:A115:0.15:T+0");
        post("STUCK_DAMPER:A124:0.2:T+0");
        simulation.commandDamper(Topology.A115, DamperPosition.OPEN);
        simulation.commandDamper(Topology.A124, DamperPosition.OPEN);

        HttpResponse<String> response = delete("?kind=STUCK_DAMPER&target=A115");
        PhysicsSnapshot afterRepair = simulation.advance();

        assertAll(
                () -> assertEquals(200, response.statusCode()),
                () -> assertTrue(response.body().contains("removed 1 fault"),
                        "one fault matched: " + response.body()),
                () -> assertEquals(1.0, afterRepair.appliedPosition(Topology.A115).value(), 1e-12,
                        "A115's damper obeys its command again, which is the repair"),
                () -> assertEquals(0.2, afterRepair.appliedPosition(Topology.A124).value(), 1e-12,
                        "and A124 is still stuck, because nobody repaired it"));
    }

    @Test
    @DisplayName("clearing everything reports how many faults went with it")
    void shouldClearEveryFaultAndReportTheCount() throws Exception {
        post("STUCK_DAMPER:A115:0.15:T+0");
        post("BLOCKED_FILTER:PLANT:0.4:T+0");
        simulation.commandDamper(Topology.A115, DamperPosition.OPEN);

        HttpResponse<String> response = delete("");
        PhysicsSnapshot healthy = simulation.advance();

        assertAll(
                () -> assertEquals(200, response.statusCode()),
                () -> assertTrue(response.body().contains("removed 2 faults"), response.body()),
                () -> assertTrue(get().body().contains("0 faults injected"), get().body()),
                () -> assertEquals(0, healthy.filterBlockage(), 1e-12),
                () -> assertEquals(1.0, healthy.appliedPosition(Topology.A115).value(), 1e-12),
                () -> assertTrue(healthy.activeFaults().isEmpty(), "the plant is healthy again"));
    }

    @Test
    @DisplayName("a malformed spec is refused with the parser's own words and injects nothing")
    void shouldRefuseAMalformedSpecAndLeaveTheFaultListUnchanged() throws Exception {
        post("STUCK_DAMPER:A115:0.15:T+0");

        HttpResponse<String> tooFewFields = post("STUCK_DAMPER:A115:0.15");
        HttpResponse<String> outOfRange = post("STUCK_DAMPER:A115:1.5:T+0");

        assertAll(
                () -> assertEquals(400, tooFewFields.statusCode()),
                () -> assertTrue(tooFewFields.body().contains("KIND:TARGET:MAGNITUDE:ONSET"),
                        "the parser's message, not a paraphrase: " + tooFewFields.body()),
                () -> assertEquals(400, outOfRange.statusCode()),
                () -> assertTrue(outOfRange.body().contains("within [0,1]"),
                        "the record's own invariant message: " + outOfRange.body()),
                () -> assertTrue(get().body().contains("1 fault injected"),
                        "the one good fault is still the only one: " + get().body()));
    }

    @Test
    @DisplayName("an onset that parses as neither form is a 400, not an empty 500")
    void shouldRefuseAnOnsetThatIsNeitherRelativeNorIso() throws Exception {
        // Its own test because the exception does not come from where the others do: an onset
        // without T+ goes to Instant.parse, which throws DateTimeParseException -- and that is
        // NOT an IllegalArgumentException. A handler catching only the latter lets this escape
        // and reach the operator as a 500 with an empty body, which says nothing about the typo.
        HttpResponse<String> response = post("STUCK_DAMPER:A115:0.15:nope");

        assertAll(
                () -> assertEquals(400, response.statusCode(), response.body()),
                () -> assertTrue(response.body().contains("nope"),
                        "and it says what could not be parsed: " + response.body()),
                () -> assertTrue(get().body().contains("0 faults injected"), get().body()));
    }

    @Test
    @DisplayName("GET lists what is injected and which of them are biting")
    void shouldListWhatIsInjectedAndWhatIsPending() throws Exception {
        post("STUCK_DAMPER:A115:0.15:T+0");
        post("SENSOR_DRIFT:A124:150:T+600");

        HttpResponse<String> response = get();

        assertAll(
                () -> assertEquals(200, response.statusCode()),
                () -> assertTrue(response.body().contains("2 faults injected"), response.body()),
                () -> assertTrue(response.body().contains("ACTIVE  STUCK_DAMPER@A115"),
                        response.body()),
                () -> assertTrue(response.body().contains("PENDING SENSOR_DRIFT@A124"),
                        response.body()));
    }

    @Test
    @DisplayName("an unknown path is a 404 that says where the surface is")
    void shouldAnswer404ForAnUnknownPath() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + surface.port() + "/plant"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("/faults"), response.body());
    }

    @Test
    @DisplayName("a method the surface does not serve is a 405 that lists the ones it does")
    void shouldAnswer405ForAMethodTheSurfaceDoesNotServe() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(endpoint))
                        .method("PUT", HttpRequest.BodyPublishers.ofString("")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(405, response.statusCode());
        assertTrue(response.body().contains("POST"), response.body());
    }

    @Test
    @DisplayName("a half-named filter is refused rather than guessed at")
    void shouldRefuseADeleteFilterThatNamesOnlyAKind() throws Exception {
        post("STUCK_DAMPER:A115:0.15:T+0");

        HttpResponse<String> response = delete("?kind=STUCK_DAMPER");

        assertEquals(400, response.statusCode());
        assertTrue(get().body().contains("1 fault injected"),
                "and nothing was removed on a guess: " + get().body());
    }

    @Test
    @DisplayName("a room this process is not simulating is refused rather than injected into nothing")
    void shouldRefuseARoomThisProcessIsNotSimulating() throws Exception {
        HttpResponse<String> response = post("STUCK_DAMPER:A116:0.15:T+0");

        assertAll(
                () -> assertEquals(400, response.statusCode(),
                        "A116 is a plausible typo for A116A and is not a room: " + response.body()),
                () -> assertTrue(response.body().contains("A116A"),
                        "and the refusal names the rooms there are: " + response.body()),
                () -> assertTrue(get().body().contains("0 faults injected"),
                        "nothing was injected: " + get().body()));
    }

    @Test
    @DisplayName("one unknown room refuses the whole spec rather than injecting the rest")
    void shouldInjectNoneOfASpecThatNamesAnUnknownRoom() throws Exception {
        HttpResponse<String> response = post("STUCK_DAMPER:A115:0.15:T+0;SENSOR_OFFSET:A116:350:T+0");

        assertAll(
                () -> assertEquals(400, response.statusCode(), response.body()),
                () -> assertTrue(get().body().contains("0 faults injected"),
                        "a half-applied scenario is worse than a refused one: " + get().body()));
    }

    @Test
    @DisplayName("clearing a room this process is not simulating is refused, not reported as nothing removed")
    void shouldRefuseToClearARoomThisProcessIsNotSimulating() throws Exception {
        post("STUCK_DAMPER:A115:0.15:T+0");

        HttpResponse<String> response = delete("?kind=STUCK_DAMPER&target=A116");

        assertAll(
                () -> assertEquals(400, response.statusCode(),
                        "\"removed 0\" would read as a repair that found nothing to do: "
                                + response.body()),
                () -> assertTrue(get().body().contains("1 fault injected"),
                        "and the real fault is untouched: " + get().body()));
    }

    private HttpResponse<String> post(String spec) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(endpoint))
                        .POST(HttpRequest.BodyPublishers.ofString(spec)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get() throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(endpoint)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String query) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(endpoint + query)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
