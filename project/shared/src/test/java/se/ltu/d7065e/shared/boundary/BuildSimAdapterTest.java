package se.ltu.d7065e.shared.boundary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * BuildSim's {@code /api/room-layers} is a collection endpoint: a PUT replaces everything that is
 * there. The dashboard publishes four layers per tick and the viewer switches between them with a
 * query parameter, so all four have to survive the tick.
 *
 * <p>They did not. Each layer was PUT on its own, so each call erased the one before it and only
 * the last layer of the enum ever reached BuildSim. The symptom was that {@code ?layer=state}
 * rendered while {@code ?layer=co2}, {@code ?layer=residual} and {@code ?layer=cusum} showed
 * nothing — three quarters of the operator view, silently absent, with no error anywhere because
 * every individual PUT succeeded.
 *
 * <p>The stub below is the JDK's own HTTP server rather than a mock, because the defect was in
 * what went over the wire and a mock of the adapter's own method would have asserted the bug.
 */
class BuildSimAdapterTest {

    private HttpServer server;
    private final Map<String, String> lastBodyByPath = new ConcurrentHashMap<>();
    private final Map<String, Integer> putCountByPath = new ConcurrentHashMap<>();
    private String baseUrl;

    @BeforeEach
    void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastBodyByPath.put(exchange.getRequestURI().getPath(), body);
            putCountByPath.merge(exchange.getRequestURI().getPath(), 1, Integer::sum);
            byte[] response = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private static List<String> layerIdsIn(String body) {
        try (JsonReader reader = Json.createReader(new StringReader(body))) {
            JsonArray array = reader.readArray();
            return array.getValuesAs(JsonObject.class).stream().map(o -> o.getString("id")).toList();
        }
    }

    @Test
    @DisplayName("every published layer is still present after the next layer is published")
    void shouldKeepEveryLayerWhenTheNextIsPublished() {
        BuildSimAdapter adapter = new BuildSimAdapter(baseUrl);
        Map<ZoneId, Double> values = Map.of(ZoneId.of("A115"), 500.0);

        adapter.publishLayer("co2", "CO2", "ppm", 420, 1500, values);
        adapter.publishLayer("residual", "Residual", "ppm", -100, 500, values);
        adapter.publishLayer("cusum", "CUSUM", "ppm", 0, 300, values);
        adapter.publishLayer("state", "Monitor state", "severity", 0, 3, values);

        List<String> sent = layerIdsIn(lastBodyByPath.get("/api/room-layers"));
        assertEquals(List.of("co2", "residual", "cusum", "state"), sent,
                "the last PUT must carry every layer, because it replaces the collection");
    }

    @Test
    @DisplayName("republishing a layer updates it in place rather than appending a duplicate")
    void shouldReplaceALayerRatherThanDuplicateIt() {
        BuildSimAdapter adapter = new BuildSimAdapter(baseUrl);

        adapter.publishLayer("co2", "CO2", "ppm", 420, 1500, Map.of(ZoneId.of("A115"), 500.0));
        adapter.publishLayer("co2", "CO2", "ppm", 420, 1500, Map.of(ZoneId.of("A115"), 900.0));

        JsonArray array;
        try (JsonReader reader = Json.createReader(new StringReader(lastBodyByPath.get("/api/room-layers")))) {
            array = reader.readArray();
        }
        assertEquals(1, array.size(), "a layer republished on the next tick must not accumulate");
        JsonObject values = array.getJsonObject(0).getJsonObject("values");
        assertEquals(1, values.size(), "one zone was published, so one value should be carried");
        // The key is Topology's floor-qualified room key and is its own concern; this test is
        // about the layer surviving, so it reads the single value whatever the key is called.
        double carried = values.values().stream()
                .map(v -> ((jakarta.json.JsonNumber) v).doubleValue()).findFirst().orElseThrow();
        assertEquals(900.0, carried,
                "the republished value must be the one BuildSim ends up holding");
    }

    @Test
    @DisplayName("a single layer is still sent as a collection, not a bare object")
    void shouldSendACollectionEvenForOneLayer() {
        BuildSimAdapter adapter = new BuildSimAdapter(baseUrl);

        adapter.publishLayer("state", "Monitor state", "severity", 0, 3,
                Map.of(ZoneId.of("A115"), 3.0));

        String body = lastBodyByPath.get("/api/room-layers");
        assertTrue(body.trim().startsWith("["), "the endpoint takes a collection: " + body);
        assertEquals(List.of("state"), layerIdsIn(body));
    }
}
