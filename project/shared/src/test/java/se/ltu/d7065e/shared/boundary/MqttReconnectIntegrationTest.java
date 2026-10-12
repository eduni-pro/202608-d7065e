package se.ltu.d7065e.shared.boundary;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.shared.event.FanPowerObserved;
import se.ltu.d7065e.ventilation.entity.AhuId;
import se.ltu.d7065e.ventilation.entity.FanPower;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.Quality;

/**
 * That a subscriber survives losing its connection, against a real broker.
 *
 * <h2>The defect this exists to prevent coming back</h2>
 *
 * <p>{@link MqttAdapter} connects with a clean session and an automatic reconnect. Each is
 * deliberate and together they were a trap: a clean session means the broker keeps no
 * subscriptions across the gap, and Paho restores the connection but not the subscriptions. A
 * service that lost its connection for one instant therefore reconnected, reported healthy, went
 * on publishing — and never received another message again.
 *
 * <p>It was found at {@code ZONE_COUNT=40}, where the monitor's publish rate was enough to time
 * the keepalive out. Thirty-eight of forty rooms went {@code BLIND} and stayed there, residuals
 * and CUSUM identically zero, through an injected stuck damper that had already driven its room
 * past 850 ppm. No log line said the subscriptions were gone; the only symptom was that the
 * conclusions stopped changing. That is precisely the kind of failure a test has to hold down,
 * because the system cannot be relied on to complain about it.
 *
 * <h2>How the connection is broken</h2>
 *
 * <p>Not by stopping the broker, which would disturb whatever else is using it, and not by
 * calling {@code disconnect()}, which is a deliberate parting that does not arm the automatic
 * reconnect at all. Instead a second raw Paho client connects under the adapter's own client id.
 * MQTT 3.1.1 requires the broker to take the earlier connection away when an id is reused, so
 * this reproduces exactly the involuntary drop the defect needs, from the broker's side, and the
 * intruder then leaves so the adapter can have its id back.
 */
@DisplayName("A subscriber that loses its connection")
class MqttReconnectIntegrationTest {

    private static final String BROKER =
            System.getProperty("mqtt.broker.url", "tcp://localhost:1883");
    private static final Duration RECONNECTED_WITHIN = Duration.ofSeconds(30);
    private static final Duration DELIVERED_WITHIN = Duration.ofSeconds(20);

    /** Matched by no service: this test must not put anything into the plant's own topics. */
    private final String topic = "test/reconnect/" + UUID.randomUUID() + "/fan-power";

    private MqttAdapter subscriber;
    private MqttAdapter publisher;
    private String unavailable;

    @BeforeEach
    void connect() {
        try {
            subscriber = new MqttAdapter(BROKER, "reconnect-test-subscriber");
            publisher = new MqttAdapter(BROKER, "reconnect-test-publisher");
        } catch (RuntimeException e) {
            unavailable = ("no broker at %s (%s). Start the stack and run this again.")
                    .formatted(BROKER, e.getMessage());
        }
        Assumptions.assumeTrue(unavailable == null, () -> unavailable);
    }

    @AfterEach
    void disconnect() {
        close(subscriber);
        close(publisher);
    }

    @Test
    void shouldStillReceiveAfterTheBrokerTakesItsConnectionAway() throws Exception {
        CountDownLatch beforeTheDrop = new CountDownLatch(1);
        CountDownLatch afterTheDrop = new CountDownLatch(1);
        subscriber.subscribe(topic, event -> {
            if (beforeTheDrop.getCount() > 0) {
                beforeTheDrop.countDown();
            } else {
                afterTheDrop.countDown();
            }
        });

        publisher.publish(topic, anObservation());
        assertTrue(beforeTheDrop.await(DELIVERED_WITHIN.toSeconds(), TimeUnit.SECONDS),
                "the subscription has to work before the drop, or the test proves nothing");

        evictFromTheBroker();

        assertTrue(redeliveredWithoutResubscribing(afterTheDrop),
                "a reconnected client must be subscribed again; before the fix it was connected, "
                        + "healthy and deaf");
    }

    /**
     * Publishes until the subscriber hears one or the deadline passes.
     *
     * <p>It deliberately never calls {@code subscribe} again. Re-subscribing here would restore
     * the very thing under test and the assertion would hold with the defect still present —
     * which is the shape this test was first written in and the reason it is spelled out. Each
     * publish before the reconnect simply has no subscriber and is dropped by the broker.
     */
    private boolean redeliveredWithoutResubscribing(CountDownLatch delivered)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(RECONNECTED_WITHIN);
        while (Instant.now().isBefore(deadline)) {
            try {
                publisher.publish(topic, anObservation());
            } catch (RuntimeException notYet) {
                // The publisher shares the broker that has just evicted somebody; a refused
                // publish here is a transient, not the result.
            }
            if (delivered.await(500, TimeUnit.MILLISECONDS)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Takes the subscriber's connection away from the broker's side by reusing its client id,
     * then releases the id again.
     */
    private void evictFromTheBroker() throws Exception {
        MqttClient intruder =
                new MqttClient(BROKER, subscriber.clientId(), new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        intruder.connect(options);
        intruder.disconnect();
        intruder.close();
    }

    private FanPowerObserved anObservation() {
        return new FanPowerObserved(AhuId.of("AHU-RECONNECT-TEST"), FanPower.ofWatts(137.0),
                ObservedAt.of(Instant.now()), Quality.GOOD);
    }

    private static void close(MqttAdapter adapter) {
        if (adapter != null) {
            try {
                adapter.close();
            } catch (RuntimeException alreadyGone) {
                // The test's whole subject is a connection being taken away; failing teardown
                // because it was would hide the result.
            }
        }
    }
}
