package yggdrasil.muninn;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.tahu.message.SparkplugBPayloadDecoder;
import org.eclipse.tahu.message.model.Metric;
import org.eclipse.tahu.message.model.SparkplugBPayload;

/**
 * Paho v3 {@link EdgeSession.Transport}. Every CONNECT is a new clean session as
 * {@code muninn-edge-<edge>} with the session's NDEATH Will. Incoming messages are only translated
 * into {@link EdgeSession.Event}s on the queue — the session thread decides what they mean.
 */
public final class PahoTransport implements EdgeSession.Transport, MqttCallback {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String broker;
    private final String clientId;
    private final BlockingQueue<EdgeSession.Event> events;
    private MqttClient client;

    public PahoTransport(String broker, String edge, BlockingQueue<EdgeSession.Event> events) {
        this.broker = broker;
        this.clientId = "muninn-edge-" + edge;
        this.events = events;
    }

    @Override
    public void connect(String willTopic, byte[] willPayload) throws Exception {
        MqttConnectOptions o = new MqttConnectOptions();
        o.setCleanSession(true);
        o.setWill(willTopic, willPayload, 1, false);
        client = new MqttClient(broker, clientId, new MemoryPersistence());
        client.setCallback(this);
        client.connect(o);
    }

    @Override
    public void subscribe(String topicFilter) throws Exception {
        client.subscribe(topicFilter, 1);
    }

    @Override
    public void publish(String topic, byte[] payload, int qos) throws Exception {
        client.publish(topic, payload, qos, false);
    }

    @Override
    public void disconnect() throws Exception {
        if (client != null) {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
            client = null;
        }
    }

    @Override
    public void messageArrived(String topic, MqttMessage m) {
        EdgeSession.Event e = translate(topic, m.getPayload());
        if (e != null) {
            events.add(e);
        }
    }

    /** NCMD rebirth and host STATE become events; anything else is logged and dropped. */
    static EdgeSession.Event translate(String topic, byte[] payload) {
        String[] parts = topic.split("/");
        try {
            if (parts.length == 3 && "STATE".equals(parts[1])) {
                JsonNode s = MAPPER.readTree(new String(payload, StandardCharsets.UTF_8));
                if (s.path("online").isBoolean() && s.path("timestamp").canConvertToLong()) {
                    return new EdgeSession.HostState(s.path("online").asBoolean(), s.path("timestamp").asLong());
                }
            } else if (parts.length == 4 && "NCMD".equals(parts[2])) {
                SparkplugBPayload p = new SparkplugBPayloadDecoder().buildFromByteArray(payload, null);
                for (Metric metric : p.getMetrics()) {
                    if (SparkplugCodec.REBIRTH.equals(metric.getName()) && Boolean.TRUE.equals(metric.getValue())) {
                        return new EdgeSession.Rebirth();
                    }
                }
            }
        } catch (Exception ex) {
            System.out.println("[MUNINN] ignored malformed " + topic + ": " + ex.getMessage());
            return null;
        }
        System.out.println("[MUNINN] ignored " + topic);
        return null;
    }

    @Override
    public void connectionLost(Throwable cause) {
        System.out.println("[MUNINN] connection lost: " + cause.getMessage());
        events.add(new EdgeSession.ConnectionLost(String.valueOf(cause.getMessage())));
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // no-op
    }
}
