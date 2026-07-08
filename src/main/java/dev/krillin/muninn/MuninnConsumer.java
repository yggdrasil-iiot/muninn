package dev.krillin.muninn;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
 * Paho v3 observer (gate harness) mirroring {@code SchemaResolvingConsumer}. Subscribes to a
 * group's Sparkplug tree, captures the NBIRTH's governed-definition bytes to {@code birthOutFile}
 * and every NDATA metric name to {@code ndataOutFile}, and counts down once a birth plus the
 * expected number of NDATA member-lines have been seen.
 *
 * <p>Both out files are truncated/created empty at construction so a prior run's captures can
 * NEVER leak into this run's assertions. The synchronous {@code subscribe} + a flushed readiness
 * line ({@code [OBSERVE] subscribed ...}) form the gate's readiness handshake.
 */
public final class MuninnConsumer implements MqttCallback {

    private final Path birthOutFile;
    private final Path ndataOutFile;
    private final int expectNdata;
    private final CountDownLatch latch = new CountDownLatch(1);
    private final SparkplugBPayloadDecoder decoder = new SparkplugBPayloadDecoder();
    private final MqttClient sub;

    private boolean birthSeen;
    private int ndataCount;

    public MuninnConsumer(String broker, String group, Path birthOutFile, Path ndataOutFile,
            int expectNdata) throws Exception {
        this.birthOutFile = birthOutFile;
        this.ndataOutFile = ndataOutFile;
        this.expectNdata = expectNdata;

        // Truncate/create both capture files empty so no prior run leaks in.
        Files.write(birthOutFile, new byte[0]);
        Files.write(ndataOutFile, new byte[0]);

        MqttConnectOptions o = new MqttConnectOptions();
        o.setCleanSession(true);
        sub = new MqttClient(broker, "muninn-observer", new MemoryPersistence());
        sub.setCallback(this);
        sub.connect(o);
        sub.subscribe("spBv1.0/" + group + "/#", 1);
        // Readiness handshake: subscribe is synchronous, so the observer is genuinely ready here.
        System.out.println("[OBSERVE] subscribed spBv1.0/" + group + "/#");
        System.out.flush();
    }

    @Override
    public void connectionLost(Throwable cause) {
        // no-op
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // no-op
    }

    @Override
    public void messageArrived(String topic, MqttMessage m) throws Exception {
        String[] parts = topic.split("/");
        if (parts.length < 3) return;
        String type = parts[2];

        SparkplugBPayload payload;
        try {
            payload = decoder.buildFromByteArray(m.getPayload(), null);
        } catch (Exception e) {
            System.out.println("[OBSERVE] decode failed " + topic + ": " + e.getMessage());
            return;
        }

        if (type.equals("NBIRTH")) {
            for (Metric metric : payload.getMetrics()) {
                if (SparkplugCodec.GOVERNED_DEFINITION.equals(metric.getName())) {
                    Files.write(birthOutFile, (byte[]) metric.getValue());
                    birthSeen = true;
                    break;
                }
            }
        } else if (type.equals("NDATA")) {
            for (Metric metric : payload.getMetrics()) {
                Files.write(ndataOutFile, (metric.getName() + "\n").getBytes(),
                        StandardOpenOption.APPEND);
                ndataCount++;
            }
        }

        if (birthSeen && ndataCount >= expectNdata) {
            latch.countDown();
        }
    }

    /** Block up to {@code timeoutMs} for a birth + expected NDATA member-lines. */
    public boolean awaitCapture(long timeoutMs) throws InterruptedException {
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public void close() throws Exception {
        if (sub != null) {
            if (sub.isConnected()) {
                sub.disconnect();
            }
            sub.close();
        }
    }
}
