package se.ltu.d7065e.shared.boundary;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import se.ltu.d7065e.shared.control.EventPublisherPort;
import se.ltu.d7065e.shared.control.ReadingStreamPort;
import se.ltu.d7065e.shared.event.DomainEvent;

/**
 * Mosquitto through Paho. There is no Jakarta EE or MicroProfile MQTT API — reactive
 * messaging's MQTT connector is SmallRye-specific — so one Eclipse client is used by both the
 * plain-Java device processes and the Quarkus services, which keeps a single code path for the
 * transport ADR-0002 requires.
 *
 * <h2>Surviving a reconnect</h2>
 *
 * <p>A clean session and an automatic reconnect are a trap together, and this adapter is where
 * it is disarmed. The session is clean so that no stale backlog is replayed into a restarted
 * service, and the reconnect is automatic so that a broker restart does not end the process —
 * but a clean session means the broker keeps no subscriptions across the gap, and Paho does not
 * restore them. The subscriptions are therefore held here, in {@link #handlers}, and replayed
 * in {@code connectComplete}. Nothing else in the system would notice if they were not: a
 * desubscribed client stays connected, stays healthy and keeps publishing.
 */
public final class MqttAdapter implements EventPublisherPort, ReadingStreamPort {

    private static final int AT_LEAST_ONCE = 1;

    /** Paho's default is 10; see the note where it is set. */
    private static final int MAX_INFLIGHT = 1000;

    private static final Logger LOG = Logger.getLogger(MqttAdapter.class.getName());

    private final MqttClient client;
    private final List<Map.Entry<String, Consumer<DomainEvent>>> handlers =
            new CopyOnWriteArrayList<>();

    public MqttAdapter(String brokerUrl, String clientId) {
        try {
            // The session is clean, so there is nothing to resume by reusing an id — while a
            // duplicate id plus automatic reconnect makes two instances evict each other in a
            // loop. The caller's id stays as the readable prefix.
            client = new MqttClient(brokerUrl,
                    clientId + "-" + Long.toHexString(System.nanoTime() & 0xFFFFFFFFL),
                    new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setAutomaticReconnect(true);
            options.setConnectionTimeout(10);
            // The default window is ten. A monitor running forty zones publishes a residual and
            // a decision per zone per cycle, and the demo profile cycles once a real second, so
            // ten is reached by the plant itself rather than by anything going wrong.
            options.setMaxInflight(MAX_INFLIGHT);
            client.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverUri) {
                    if (reconnect) {
                        resubscribe();
                    }
                }

                @Override
                public void connectionLost(Throwable cause) {
                    // Deliberately does not raise: staleness is the monitor's business, not the
                    // transport's. It does record, because a silent reconnect is what made the
                    // subscription loss above invisible for the length of a run.
                    LOG.log(Level.WARNING, "broker connection lost; Paho will reconnect and "
                            + "every subscription will be restored", cause);
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    dispatch(topic, message);
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    // Nothing to do at QoS 1.
                }
            });
            client.connect(options);
        } catch (MqttException e) {
            throw new IllegalStateException("cannot connect to broker " + brokerUrl, e);
        }
    }

    /**
     * The generated client id, which the broker uses to decide that two connections are the same
     * client. Visible so that a test can take this client's place and force the reconnect path.
     */
    String clientId() {
        return client.getClientId();
    }

    @Override
    public void publish(String topic, DomainEvent event) {
        try {
            MqttMessage message = new MqttMessage(
                    EventCodec.encode(event).getBytes(StandardCharsets.UTF_8));
            message.setQos(AT_LEAST_ONCE);
            client.publish(topic, message);
        } catch (MqttException e) {
            throw new IllegalStateException("cannot publish to " + topic, e);
        }
    }

    @Override
    public void subscribe(String topicFilter, Consumer<DomainEvent> handler) {
        handlers.add(Map.entry(topicFilter, handler));
        try {
            client.subscribe(topicFilter, AT_LEAST_ONCE);
        } catch (MqttException e) {
            throw new IllegalStateException("cannot subscribe to " + topicFilter, e);
        }
    }

    /**
     * Restores every subscription after Paho has reconnected, because the broker has not kept
     * them.
     *
     * <p>This is the whole reason the callback is a {@link MqttCallbackExtended}. The session is
     * clean, so a reconnect gives the client a <em>new</em> broker-side session holding no
     * subscriptions at all, and Paho does not replay them. Without this method a service that
     * lost its connection for one instant reconnected, reported healthy, went on publishing —
     * and never received another message for the life of the process. It is a failure with no
     * symptom of its own: the only visible trace is that the conclusions stop changing.
     *
     * <p>A failure to resubscribe is logged rather than thrown. This runs on Paho's reconnect
     * thread, where an escaping exception is lost, and the connection that would carry a retry
     * is the one that has just come back — so the honest record is the log line, and the
     * downstream symptom is the staleness the monitor already watches for.
     */
    private void resubscribe() {
        for (var handler : handlers) {
            try {
                client.subscribe(handler.getKey(), AT_LEAST_ONCE);
            } catch (MqttException e) {
                LOG.log(Level.SEVERE, "cannot restore subscription to " + handler.getKey()
                        + " after reconnect; this client is now deaf on that filter", e);
            }
        }
    }

    /**
     * Paho allows one callback per client, so the adapter dispatches to the handler whose
     * filter matches. Registering per-filter callbacks instead would silently keep only the
     * last one, and a monitor subscribing to readings, fan power and commands would go deaf.
     */
    private void dispatch(String topic, MqttMessage message) {
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
        DomainEvent event;
        try {
            event = EventCodec.decode(payload);
        } catch (RuntimeException e) {
            // This runs on Paho's callback thread, where an escaping exception costs the
            // subscription rather than the message. One malformed or out-of-range payload must
            // not be able to make a subscriber deaf, so it is dropped and recorded instead.
            LOG.log(Level.WARNING, "dropping undecodable payload on " + topic + ": " + payload, e);
            return;
        }
        for (var handler : handlers) {
            if (!matches(handler.getKey(), topic)) {
                continue;
            }
            try {
                handler.getValue().accept(event);
            } catch (RuntimeException e) {
                // Likewise one handler's failure must not starve the handlers after it.
                LOG.log(Level.WARNING, "handler for " + handler.getKey() + " failed on " + topic, e);
            }
        }
    }

    /** MQTT filter matching: {@code +} is one level, {@code #} is the rest. */
    static boolean matches(String filter, String topic) {
        String[] f = filter.split("/", -1);
        String[] t = topic.split("/", -1);
        for (int i = 0; i < f.length; i++) {
            if (f[i].equals("#")) {
                return true;
            }
            if (i >= t.length) {
                return false;
            }
            if (!f[i].equals("+") && !f[i].equals(t[i])) {
                return false;
            }
        }
        return f.length == t.length;
    }

    @Override
    public void close() {
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        } catch (MqttException e) {
            throw new IllegalStateException("cannot close broker connection", e);
        }
    }
}
