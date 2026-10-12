package se.ltu.d7065e.physics.boundary;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import se.ltu.d7065e.physics.control.BuildingSimulation;
import se.ltu.d7065e.physics.control.FaultSpecParser;
import se.ltu.d7065e.physics.entity.FaultKind;
import se.ltu.d7065e.physics.entity.InjectedFault;
import se.ltu.d7065e.shared.control.ClockPort;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * The runtime half of {@code FAULT_SPEC}: an HTTP surface an operator curls to inject a fault
 * into the running plant, to ask what is injected, and — the reason it exists — to take one away
 * again.
 *
 * <pre>
 * curl -X POST --data 'STUCK_DAMPER:A115:0.15:T+0' http://127.0.0.1:8081/faults
 * curl http://127.0.0.1:8081/faults
 * curl -X DELETE 'http://127.0.0.1:8081/faults?kind=STUCK_DAMPER&amp;target=A115'
 * curl -X DELETE http://127.0.0.1:8081/faults
 * </pre>
 *
 * <p>Removal is what makes a recovery watchable. Marking a case serviced on the dashboard closes
 * the paperwork, but the damper is still physically stuck, so the monitor re-detects it and opens
 * a fresh case about a minute later — correctly, because nothing has been repaired. Only taking
 * the fault out of the plant makes the room go healthy, and {@code FAULT_SPEC} is read once at
 * start-up, so before this surface existed the only way to do that was to restart the stack.
 *
 * <p><b>{@code T+<seconds>} here counts from the simulated now, not from {@code START_AT}.</b>
 * This is the one thing a reader coming from {@code FAULT_SPEC} will get wrong. There, a
 * {@code T+} onset is relative to the first tick's instant, which is what lets a scenario be
 * aimed at a point in the occupancy day. A request arriving at an already-running plant has no
 * such anchor to aim at — the run started whenever it started — so here {@code T+0} means "bites
 * on the next tick" and {@code T+300} means five simulated minutes after the request is handled.
 * At a {@code TIME_SCALE} of 60 those five simulated minutes are five real seconds. The grammar
 * is otherwise exactly {@link FaultSpecParser}'s, deliberately: one grammar, one set of
 * invariants, and a spec demonstrated from a file can be pasted straight into a curl.
 *
 * <p>Validation happens here and nowhere deeper. A spec the parser or {@link InjectedFault}
 * rejects comes back as 400 carrying that component's own message, because it already says the
 * useful thing and a second layer of checks here would only paraphrase it worse.
 */
public final class FaultControlSurface {

    private static final String PATH = "/faults";

    private final BuildingSimulation simulation;
    private final ClockPort clock;
    private final HttpServer server;

    /**
     * @param clock the same simulated clock the tick loop runs on, which is what makes a
     *              {@code T+} onset and the active/pending split mean simulated time
     * @param port the port to bind, or 0 to let the OS pick one — which is what the tests do,
     *             asking {@link #port()} afterwards for what they got
     */
    public FaultControlSurface(BuildingSimulation simulation, ClockPort clock, int port)
            throws IOException {
        this.simulation = simulation;
        this.clock = clock;
        // The wildcard address rather than loopback, which looks like the weaker choice and is
        // the only working one: Docker's port proxy connects to the container's own interface, so
        // a server bound inside the container to 127.0.0.1 is unreachable through any published
        // port. Keeping this endpoint off the network is the host-side mapping's job, and
        // docker-compose.yml does it by publishing on 127.0.0.1.
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::dispatch);
        // One daemon thread. Operator commands arrive a handful per demo, so serialising them
        // costs nothing and keeps two curls from interleaving; daemon because the tick loop is
        // the process's reason to live and a handler thread must not outlive it.
        server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "fault-control");
            thread.setDaemon(true);
            return thread;
        }));
    }

    public void start() {
        server.start();
    }

    /** The bound port, which is the interesting question only when 0 was asked for. */
    public int port() {
        return server.getAddress().getPort();
    }

    /**
     * Stops listening. Worth calling rather than leaving to process exit: the JDK's own
     * dispatcher thread is not a daemon, so a process whose tick loop has ended hangs on it.
     */
    public void stop() {
        server.stop(0);
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (!PATH.equals(path)) {
                respond(exchange, 404, "the fault control surface is " + PATH + ", not " + path);
                return;
            }
            switch (exchange.getRequestMethod()) {
                case "POST" -> inject(exchange);
                case "GET" -> list(exchange);
                case "DELETE" -> remove(exchange);
                default -> respond(exchange, 405, PATH
                        + " takes POST to inject, GET to list and DELETE to clear, not "
                        + exchange.getRequestMethod());
            }
        } catch (IllegalArgumentException | DateTimeException rejected) {
            // Handing back the rejecting component's own message, and catching two types to do
            // it: Instant.parse throws DateTimeParseException, which is NOT an
            // IllegalArgumentException, so an ISO-8601 onset with a typo would otherwise escape
            // and reach the operator as an empty 500.
            respond(exchange, 400, String.valueOf(rejected.getMessage()));
        } finally {
            exchange.close();
        }
    }

    private void inject(HttpExchange exchange) throws IOException {
        String spec = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Instant now = clock.now();
        List<InjectedFault> injected = FaultSpecParser.parse(spec, now);
        if (injected.isEmpty()) {
            respond(exchange, 400, "no fault spec in the request body: send "
                    + "KIND:TARGET:MAGNITUDE:ONSET, as in STUCK_DAMPER:A115:0.15:T+0");
            return;
        }
        injected.forEach(fault -> requireSimulatedRoom(fault.zone()));
        simulation.injectAll(injected);
        // Accepted rather than applied: the plant picks the fault up on its next tick, and with a
        // future onset it picks it up on the tick that reaches the onset.
        respond(exchange, 202, lines("injected " + counted(injected.size())
                + " at simulated " + now, injected, now));
    }

    private void list(HttpExchange exchange) throws IOException {
        Instant now = clock.now();
        List<InjectedFault> scheduled = simulation.injectedFaults();
        respond(exchange, 200, lines(
                "simulated now " + now + ", " + counted(scheduled.size()) + " injected",
                scheduled, now));
    }

    private void remove(HttpExchange exchange) throws IOException {
        Map<String, String> filter = parameters(exchange.getRequestURI().getQuery());
        String kind = filter.get("kind");
        String target = filter.get("target");
        if (kind == null && target == null) {
            respond(exchange, 200, "removed " + counted(simulation.clearFaults()));
            return;
        }
        if (kind == null || target == null) {
            respond(exchange, 400, "clearing one fault takes both kind and target, as in "
                    + "?kind=STUCK_DAMPER&target=A115; clearing every fault takes neither");
            return;
        }
        FaultKind faultKind = FaultKind.valueOf(kind.trim().toUpperCase(Locale.ROOT));
        ZoneId zone = FaultSpecParser.PLANT_TARGET.equalsIgnoreCase(target.trim())
                ? null
                : ZoneId.of(target.trim());
        requireSimulatedRoom(zone);
        respond(exchange, 200, "removed " + counted(simulation.clearFaults(faultKind, zone))
                + " matching " + faultKind + " on " + target.trim());
    }

    /**
     * Refuses a room this process is not simulating, before anything is injected or removed.
     *
     * <p>{@link ZoneId} rejects a blank name and a BuildSim key, but not a well-formed name that
     * is simply not here — and which names are here depends on {@code ZONE_COUNT}, so the set is
     * a property of this process rather than of the code. Without this check {@code A116} for
     * {@code A116A} would parse, validate, inject, and then bite nothing whatsoever: the operator
     * would be left asking why the plant ignored the fault, which is the one question a demo
     * cannot afford to debug in front of an audience. A spec naming several faults is refused
     * whole for the same reason, rather than injecting the half that happened to be spelled right.
     *
     * @param zone the room to check, or {@code null} for a plant-wide fault, which has no room
     */
    private static void requireSimulatedRoom(ZoneId zone) {
        if (zone != null && !Topology.ZONE_IDS.contains(zone)) {
            throw new IllegalArgumentException("no such room: %s; this process is simulating %d: %s"
                    .formatted(zone.value(), Topology.ZONE_IDS.size(),
                            String.join(", ", Topology.ZONE_IDS.stream().map(ZoneId::value).toList())));
        }
    }

    /** A heading, then one fault per line marked by whether it is biting at {@code now} yet. */
    private static String lines(String heading, List<InjectedFault> faults, Instant now) {
        StringBuilder text = new StringBuilder(heading);
        for (InjectedFault fault : faults) {
            text.append(fault.isActiveAt(now) ? "\nACTIVE  " : "\nPENDING ").append(fault);
        }
        return text.toString();
    }

    private static String counted(int faults) {
        return faults + (faults == 1 ? " fault" : " faults");
    }

    private static Map<String, String> parameters(String query) {
        Map<String, String> parameters = new HashMap<>();
        if (query != null) {
            for (String pair : query.split("&")) {
                int equals = pair.indexOf('=');
                if (equals > 0) {
                    parameters.put(pair.substring(0, equals), pair.substring(equals + 1));
                }
            }
        }
        return parameters;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = (body + "\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
