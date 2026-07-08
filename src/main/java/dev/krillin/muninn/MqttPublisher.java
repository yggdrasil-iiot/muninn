package dev.krillin.muninn;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/**
 * Paho v3 Sparkplug B publisher for the governed-equipment edge node. Mirrors the
 * {@code Spb40Edge} connect/publish idiom: clean-session connect at construction,
 * plain {@code publish(topic, bytes, qos, retained)}, and a guarded {@code close()}.
 */
public final class MqttPublisher {

    private final MqttClient client;

    /** Connects (clean session) as {@code muninn-edge-<edge>}. */
    public MqttPublisher(String broker, String edge) throws Exception {
        MqttConnectOptions o = new MqttConnectOptions();
        o.setCleanSession(true);
        client = new MqttClient(broker, "muninn-edge-" + edge, new MemoryPersistence());
        client.connect(o);
    }

    /** Sparkplug B topic: {@code spBv1.0/<group>/<msgType>/<edge>}. */
    public static String topic(String group, String msgType, String edge) {
        return "spBv1.0/" + group + "/" + msgType + "/" + edge;
    }

    public void publish(String topic, byte[] bytes, int qos, boolean retained) throws Exception {
        client.publish(topic, bytes, qos, retained);
    }

    public void close() throws Exception {
        if (client != null) {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        }
    }
}
