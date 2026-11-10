package yggdrasil.muninn;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.tahu.message.SparkplugBPayloadEncoder;
import org.eclipse.tahu.message.model.SparkplugBPayload;

/**
 * A persistent Sparkplug B 3.0 edge-node session for the governed equipment ({@code feed --session}).
 *
 * <p>One thread owns every decision. MQTT callbacks only put {@link Event}s on a queue; this loop
 * takes them between data cycles, so a rebirth or a host going offline never interleaves with a
 * publish. Per Sparkplug 3.0:
 * <ul>
 *   <li>each MQTT CONNECT carries an NDEATH Will (QoS 1, not retained) with {@code bdSeq}; the
 *       NBIRTH repeats that {@code bdSeq}; the next CONNECT increments it, 255 wraps to 0;</li>
 *   <li>NBIRTH is {@code seq} 0; every later message is one more, 255 wraps to 0; NDEATH has none;</li>
 *   <li>NCMD {@code Node Control/Rebirth = true} stops data and sends a complete birth sequence with
 *       the same {@code bdSeq} — here only after the registry (and hierarchy) verify again; a
 *       definition that no longer verifies ends the session with NDEATH and exit 1;</li>
 *   <li>with a Primary Host Application, birth waits for its STATE {@code online=true}; an
 *       {@code online=false} at least as new as the last online makes the node publish NDEATH,
 *       disconnect, and connect again with the next {@code bdSeq};</li>
 *   <li>an intentional stop publishes NDEATH before disconnecting; a lost connection ends the
 *       session (the broker publishes the Will) without reconnecting.</li>
 * </ul>
 * Every other NCMD metric and every DCMD is ignored: Muninn reads the plant, it never writes to it.
 */
public final class EdgeSession {

    /** What the session needs from MQTT. {@link PahoTransport} is the real one. */
    public interface Transport {
        /** Clean-session connect with the given NDEATH Will (QoS 1, not retained). */
        void connect(String willTopic, byte[] willPayload) throws Exception;

        void subscribe(String topicFilter) throws Exception;

        void publish(String topic, byte[] payload, int qos) throws Exception;

        void disconnect() throws Exception;
    }

    /** One verification of the governed inputs: definition bytes, parsed contract, device or null. */
    public record Birth(byte[] definitionBytes, UdtDefinition definition, String device) {
    }

    /** Re-reads and re-verifies the registry (and the hierarchy, when given). */
    @FunctionalInterface
    public interface Verifier {
        Birth verify() throws ProvenanceException;
    }

    /** Samples the live instance; egress validation is applied by the session. */
    @FunctionalInterface
    public interface Sampler {
        List<SampledMember> sample() throws Exception;
    }

    /** Inputs the loop reacts to. */
    public sealed interface Event {
    }

    /** NCMD carried {@code Node Control/Rebirth = true}. */
    public record Rebirth() implements Event {
    }

    /** STATE of the configured Primary Host Application. */
    public record HostState(boolean online, long timestamp) implements Event {
    }

    /** Stop intentionally (NDEATH, then disconnect). */
    public record Stop() implements Event {
    }

    /** The broker connection dropped; the broker publishes the Will and the session ends. */
    public record ConnectionLost(String cause) implements Event {
    }

    private final Transport transport;
    private final Verifier verifier;
    private final Sampler sampler;
    private final String group;
    private final String edge;
    private final String primaryHost;

    private long bdSeq;
    private long seq;
    private Birth birth;
    private boolean born;
    private long lastOnline = Long.MIN_VALUE;
    private int dataCycles;

    public EdgeSession(Transport transport, Verifier verifier, Sampler sampler, String group, String edge,
            String primaryHost) {
        this.transport = transport;
        this.verifier = verifier;
        this.sampler = sampler;
        this.group = group;
        this.edge = edge;
        this.primaryHost = primaryHost;
    }

    public static String stateTopic(String hostId) {
        return "spBv1.0/STATE/" + hostId;
    }

    long bdSeq() {
        return bdSeq;
    }

    boolean born() {
        return born;
    }

    int dataCycles() {
        return dataCycles;
    }

    /**
     * Runs until {@code maxCycles} data messages were sent ({@code 0} = until a {@link Stop}),
     * returning the exit code: {@code 0} after an intentional stop, {@code 1} when the governed
     * inputs no longer verify at a (re)birth. A lost broker connection throws.
     */
    public int run(BlockingQueue<Event> events, long periodMs, int maxCycles) throws Exception {
        connect();
        if (primaryHost == null && !birth()) {
            return 1;
        }
        long next = System.currentTimeMillis() + periodMs;
        while (true) {
            Event e = events.poll(Math.max(0, next - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            if (e == null) {
                next += periodMs;
                if (born) {
                    data();
                    if (maxCycles > 0 && dataCycles >= maxCycles) {
                        stop();
                        return 0;
                    }
                }
            } else if (!handle(e)) {
                return e instanceof Stop ? 0 : 1;
            }
        }
    }

    /** Applies one event; {@code false} when the session has ended. */
    boolean handle(Event e) throws Exception {
        if (e instanceof Stop) {
            stop();
            return false;
        }
        if (e instanceof Rebirth) {
            return !born || birth();
        }
        if (e instanceof ConnectionLost lost) {
            // No reconnect here: a restart is a new session with a fresh birth (exit 2).
            born = false;
            throw new IllegalStateException("broker connection lost: " + lost.cause());
        }
        HostState s = (HostState) e;
        if (primaryHost == null) {
            return true;
        }
        if (s.online()) {
            if (s.timestamp() >= lastOnline) {
                lastOnline = s.timestamp();
                if (!born) {
                    return birth();
                }
            }
        } else if (born && s.timestamp() >= lastOnline) {
            // The host went away: NDEATH, drop the connection, start a new session and wait for it.
            stop();
            bdSeq = (bdSeq + 1) % 256;
            connect();
        }
        return true;
    }

    void connect() throws Exception {
        transport.connect(topic("NDEATH", null), encode(SparkplugCodec.buildNdeath(bdSeq)));
        transport.subscribe(topic("NCMD", null));
        if (primaryHost != null) {
            transport.subscribe(stateTopic(primaryHost));
        }
        born = false;
    }

    /** Complete birth sequence after re-verifying; on refusal publishes NDEATH and ends. */
    boolean birth() throws Exception {
        try {
            birth = verifier.verify();
        } catch (ProvenanceException ex) {
            System.err.println("[MUNINN] refuse: " + ex.getMessage());
            stop();
            return false;
        }
        seq = 0;
        transport.publish(topic("NBIRTH", null),
                encode(SparkplugCodec.buildSessionNbirth(birth.definitionBytes(), bdSeq)), 0);
        if (birth.device() != null) {
            transport.publish(topic("DBIRTH", birth.device()),
                    encode(SparkplugCodec.buildDbirth(conformant(), nextSeq())), 0);
        }
        born = true;
        return true;
    }

    void data() throws Exception {
        List<SampledMember> ok = conformant();
        if (birth.device() == null) {
            transport.publish(topic("NDATA", null), encode(SparkplugCodec.buildNdata(ok, nextSeq())), 0);
        } else {
            transport.publish(topic("DDATA", birth.device()), encode(SparkplugCodec.buildDdata(ok, nextSeq())), 0);
        }
        dataCycles++;
    }

    void stop() throws Exception {
        transport.publish(topic("NDEATH", null), encode(SparkplugCodec.buildNdeath(bdSeq)), 1);
        transport.disconnect();
        born = false;
    }

    private List<SampledMember> conformant() throws Exception {
        List<SampledMember> ok = sampler.sample().stream()
                .filter(s -> EgressValidator.conformant(s, birth.definition()))
                .toList();
        return ok;
    }

    private long nextSeq() {
        seq = (seq + 1) % 256;
        return seq;
    }

    private String topic(String type, String device) {
        return device == null ? MqttPublisher.topic(group, type, edge) : MqttPublisher.topic(group, type, edge, device);
    }

    private static byte[] encode(SparkplugBPayload p) throws Exception {
        return new SparkplugBPayloadEncoder().getBytes(p, false);
    }
}
