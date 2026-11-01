package dev.krillin.muninn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.tahu.message.SparkplugBPayloadDecoder;
import org.eclipse.tahu.message.SparkplugBPayloadEncoder;
import org.eclipse.tahu.message.model.Metric;
import org.eclipse.tahu.message.model.Metric.MetricBuilder;
import org.eclipse.tahu.message.model.MetricDataType;
import org.eclipse.tahu.message.model.SparkplugBPayload;
import org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The session's Sparkplug 3.0 behaviour against a recording transport: bdSeq in Will and NBIRTH,
 * seq order and wrap, rebirth with re-verification, Primary Host STATE, and NDEATH on every stop.
 */
// A session that never ends is a failure here, not a hung build.
@Timeout(10)
class EdgeSessionTest {

    private static final byte[] DEF = ("{\"templateRef\":\"Line1-Mixer\",\"version\":\"1.0.0\",\"members\":["
            + "{\"name\":\"Rpm\",\"type\":\"Double\",\"semanticId\":\"s\",\"range\":null},"
            + "{\"name\":\"Running\",\"type\":\"Boolean\",\"semanticId\":\"s\",\"range\":null}],"
            + "\"params\":[],\"conformsTo\":null}").getBytes(StandardCharsets.UTF_8);

    private static byte[] def() {
        return DEF;
    }

    private static UdtDefinition parsed() throws ProvenanceException {
        try {
            return DefinitionJson.fromBytes(DEF);
        } catch (Exception e) {
            throw new ProvenanceException("parse", e);
        }
    }

    /** One transport call: connect / subscribe / publish / disconnect, with a decoded payload if any. */
    record Call(String op, String topic, SparkplugBPayload payload, int qos) {
        Long seq() {
            return payload.getSeq();
        }

        Object metric(String name) {
            for (Metric m : payload.getMetrics()) {
                if (name.equals(m.getName())) {
                    return m.getValue();
                }
            }
            return null;
        }

        List<String> names() {
            return payload.getMetrics().stream().map(Metric::getName).toList();
        }
    }

    static final class Recording implements EdgeSession.Transport {
        final List<Call> calls = new ArrayList<>();

        private static SparkplugBPayload decode(byte[] b) throws Exception {
            return new SparkplugBPayloadDecoder().buildFromByteArray(b, null);
        }

        @Override
        public void connect(String willTopic, byte[] willPayload) throws Exception {
            calls.add(new Call("connect", willTopic, decode(willPayload), 1));
        }

        @Override
        public void subscribe(String topicFilter) {
            calls.add(new Call("subscribe", topicFilter, null, 1));
        }

        @Override
        public void publish(String topic, byte[] payload, int qos) throws Exception {
            calls.add(new Call("publish", topic, decode(payload), qos));
        }

        @Override
        public void disconnect() {
            calls.add(new Call("disconnect", null, null, 0));
        }

        List<Call> published(String type) {
            return calls.stream().filter(c -> c.op().equals("publish") && c.topic().contains("/" + type + "/"))
                    .toList();
        }

        /** Every seq the node sent, in order, from NBIRTH on. */
        List<Long> seqs() {
            return calls.stream().filter(c -> c.op().equals("publish") && !c.topic().contains("/NDEATH/"))
                    .map(Call::seq).toList();
        }
    }

    private static final List<SampledMember> SAMPLE = List.of(
            new SampledMember("Rpm", MetricDataType.Double, 1535.0),
            new SampledMember("Running", MetricDataType.Boolean, true));

    private int verifications;

    private EdgeSession.Verifier verifier(String device) {
        return () -> {
            verifications++;
            return new EdgeSession.Birth(def(), parsed(), device);
        };
    }

    private EdgeSession session(Recording t, String device, String host) {
        return new EdgeSession(t, verifier(device), () -> SAMPLE, "g", "e", host);
    }

    @Test
    void willNbirthDataThenNdeathOnStop() throws Exception {
        Recording t = new Recording();
        assertEquals(0, session(t, null, null).run(new LinkedBlockingQueue<>(), 1, 3));

        Call connect = t.calls.get(0);
        assertEquals("connect", connect.op());
        assertEquals("spBv1.0/g/NDEATH/e", connect.topic());
        assertEquals(List.of(SparkplugCodec.BD_SEQ), connect.names());
        assertEquals(0L, connect.metric(SparkplugCodec.BD_SEQ));
        assertNull(connect.seq(), "NDEATH carries no seq");
        assertEquals("spBv1.0/g/NCMD/e", t.calls.get(1).topic());

        Call nbirth = t.published("NBIRTH").get(0);
        assertEquals(0L, nbirth.seq());
        assertArrayEquals(def(), (byte[]) nbirth.metric(SparkplugCodec.GOVERNED_DEFINITION));
        assertEquals(0L, nbirth.metric(SparkplugCodec.BD_SEQ));
        assertEquals(Boolean.FALSE, nbirth.metric(SparkplugCodec.REBIRTH));

        assertEquals(List.of(0L, 1L, 2L, 3L), t.seqs());
        Call death = t.published("NDEATH").get(0);
        assertEquals(1, death.qos());
        assertEquals(0L, death.metric(SparkplugCodec.BD_SEQ));
        assertNull(death.seq());
        assertEquals("disconnect", t.calls.get(t.calls.size() - 1).op());
    }

    @Test
    void deviceBirthFollowsNbirthAndCarriesTheData() throws Exception {
        Recording t = new Recording();
        session(t, "site:line", null).run(new LinkedBlockingQueue<>(), 1, 2);

        assertEquals("spBv1.0/g/DBIRTH/e/site:line", t.published("DBIRTH").get(0).topic());
        assertEquals(2, t.published("DDATA").size());
        assertTrue(t.published("NDATA").isEmpty());
        assertEquals(List.of(0L, 1L, 2L, 3L), t.seqs());
    }

    @Test
    void seqWrapsAfter255() throws Exception {
        Recording t = new Recording();
        session(t, null, null).run(new LinkedBlockingQueue<>(), 0, 257);
        List<Long> seqs = t.seqs();
        assertEquals(255L, seqs.get(255));
        assertEquals(0L, seqs.get(256));
        assertEquals(1L, seqs.get(257));
    }

    @Test
    void rebirthReverifiesAndRestartsSeqWithSameBdSeq() throws Exception {
        Recording t = new Recording();
        EdgeSession s = session(t, "site:line", null);
        s.connect();
        s.birth();
        s.data();
        assertTrue(s.handle(new EdgeSession.Rebirth()));

        assertEquals(2, verifications);
        assertEquals(2, t.published("NBIRTH").size());
        Call again = t.published("NBIRTH").get(1);
        assertEquals(0L, again.seq());
        assertEquals(0L, again.metric(SparkplugCodec.BD_SEQ));
        assertEquals(2, t.published("DBIRTH").size());
        assertEquals(List.of(0L, 1L, 2L, 0L, 1L), t.seqs());
    }

    @Test
    void rebirthWhoseDefinitionNoLongerVerifiesEndsWithNdeath() throws Exception {
        Recording t = new Recording();
        int[] n = {0};
        EdgeSession s = new EdgeSession(t, () -> {
            if (n[0]++ > 0) {
                throw new ProvenanceException("provenance mismatch");
            }
            return new EdgeSession.Birth(def(), parsed(), null);
        }, () -> SAMPLE, "g", "e", null);
        BlockingQueue<EdgeSession.Event> events = new LinkedBlockingQueue<>();
        events.add(new EdgeSession.Rebirth());

        assertEquals(1, s.run(events, 60_000, 0));
        assertEquals(1, t.published("NBIRTH").size());
        assertEquals(1, t.published("NDEATH").size());
        assertEquals("disconnect", t.calls.get(t.calls.size() - 1).op());
    }

    @Test
    void firstBirthThatDoesNotVerifyEndsWithNdeath() throws Exception {
        Recording t = new Recording();
        EdgeSession s = new EdgeSession(t, () -> {
            throw new ProvenanceException("provenance mismatch");
        }, () -> SAMPLE, "g", "e", null);
        assertEquals(1, s.run(new LinkedBlockingQueue<>(), 1, 0));
        assertTrue(t.published("NBIRTH").isEmpty());
        assertEquals(1, t.published("NDEATH").size());
    }

    @Test
    void rebirthBeforeBirthIsIgnored() throws Exception {
        Recording t = new Recording();
        EdgeSession s = session(t, null, "host");
        s.connect();
        assertTrue(s.handle(new EdgeSession.Rebirth()));
        assertTrue(t.published("NBIRTH").isEmpty());
        assertEquals(0, verifications);
    }

    @Test
    void stopPublishesNdeathAndEnds() throws Exception {
        Recording t = new Recording();
        BlockingQueue<EdgeSession.Event> events = new LinkedBlockingQueue<>();
        events.add(new EdgeSession.Stop());
        assertEquals(0, session(t, null, null).run(events, 60_000, 0));
        assertEquals(1, t.published("NDEATH").size());
        assertTrue(t.published("NDATA").isEmpty());
    }

    @Test
    void sessionStillDropsNonConformantMembers() throws Exception {
        Recording t = new Recording();
        new EdgeSession(t, verifier(null), () -> List.of(
                new SampledMember("Rpm", MetricDataType.String, "1535"),
                new SampledMember("Running", MetricDataType.Boolean, true)), "g", "e", null)
                .run(new LinkedBlockingQueue<>(), 1, 1);
        assertEquals(List.of("Running"), t.published("NDATA").get(0).names());
    }

    @Test
    void primaryHostGatesBirthAndOfflineStartsANewSession() throws Exception {
        Recording t = new Recording();
        EdgeSession s = session(t, null, "host");
        s.connect();
        assertEquals("spBv1.0/STATE/host", t.calls.get(2).topic());
        assertFalse(s.born());

        assertTrue(s.handle(new EdgeSession.HostState(true, 100)));
        assertTrue(s.born());
        assertEquals(1, t.published("NBIRTH").size());

        // An offline older than the online it follows is stale: ignored.
        assertTrue(s.handle(new EdgeSession.HostState(false, 99)));
        assertTrue(s.born());
        assertTrue(t.published("NDEATH").isEmpty());

        assertTrue(s.handle(new EdgeSession.HostState(false, 100)));
        assertFalse(s.born());
        assertEquals(1, t.published("NDEATH").size());
        Call reconnect = t.calls.stream().filter(c -> c.op().equals("connect")).toList().get(1);
        assertEquals(1L, reconnect.metric(SparkplugCodec.BD_SEQ));

        // A stale online (older than the last one seen) does not birth.
        assertTrue(s.handle(new EdgeSession.HostState(true, 50)));
        assertFalse(s.born());

        assertTrue(s.handle(new EdgeSession.HostState(true, 200)));
        assertEquals(1L, t.published("NBIRTH").get(1).metric(SparkplugCodec.BD_SEQ));
    }

    @Test
    void noDataWhileWaitingForTheHost() throws Exception {
        Recording t = new Recording();
        BlockingQueue<EdgeSession.Event> events = new LinkedBlockingQueue<>();
        EdgeSession s = session(t, null, "host");
        Thread stopper = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            events.add(new EdgeSession.Stop());
        });
        stopper.start();
        assertEquals(0, s.run(events, 1, 0));
        assertTrue(t.published("NBIRTH").isEmpty());
        assertTrue(t.published("NDATA").isEmpty());
    }

    @Test
    void shutdownHookStopsTheSessionWithNdeathAndWaitsForIt() throws Exception {
        Recording t = new Recording();
        BlockingQueue<EdgeSession.Event> events = new LinkedBlockingQueue<>();
        EdgeSession s = session(t, null, null);
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger code = new AtomicInteger(-1);
        Thread runner = new Thread(() -> {
            try {
                code.set(s.run(events, 60_000, 0));
            } catch (Exception e) {
                code.set(-2);
            } finally {
                done.countDown();
            }
        });
        runner.start();
        Thread hook = MuninnMain.shutdownStop(events, done);
        hook.start();
        hook.join(5_000);
        assertFalse(hook.isAlive(), "the hook returns once the session has finished");
        assertEquals(0, code.get());
        assertEquals("NDEATH", t.calls.get(t.calls.size() - 2).topic().split("/")[2]);
        assertEquals("disconnect", t.calls.get(t.calls.size() - 1).op());
    }

    @Test
    void lostConnectionWhileWaitingForTheHostEndsTheSession() throws Exception {
        Recording t = new Recording();
        BlockingQueue<EdgeSession.Event> events = new LinkedBlockingQueue<>();
        events.add(new EdgeSession.ConnectionLost("reset"));
        EdgeSession s = session(t, null, "host");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> s.run(events, 60_000, 0));
        assertTrue(e.getMessage().contains("reset"));
        assertTrue(t.published("NBIRTH").isEmpty());
        assertFalse(s.born());
    }

    @Test
    void lostConnectionWhileBornEndsTheSessionWithoutNdeath() throws Exception {
        Recording t = new Recording();
        BlockingQueue<EdgeSession.Event> events = new LinkedBlockingQueue<>();
        events.add(new EdgeSession.ConnectionLost("reset"));
        EdgeSession s = session(t, null, null);
        assertThrows(IllegalStateException.class, () -> s.run(events, 60_000, 0));
        assertEquals(1, t.published("NBIRTH").size());
        assertTrue(t.published("NDEATH").isEmpty(), "the broker publishes the Will; the session sends none");
        assertFalse(s.born());
    }

    @Test
    void bdSeqWrapsAfter255() throws Exception {
        Recording t = new Recording();
        EdgeSession s = session(t, null, "host");
        s.connect();
        for (int i = 0; i < 256; i++) {
            s.handle(new EdgeSession.HostState(true, 2L * i));
            s.handle(new EdgeSession.HostState(false, 2L * i));
        }
        List<Call> connects = t.calls.stream().filter(c -> c.op().equals("connect")).toList();
        assertEquals(255L, connects.get(255).metric(SparkplugCodec.BD_SEQ));
        assertEquals(0L, connects.get(256).metric(SparkplugCodec.BD_SEQ));
    }

    private static byte[] ncmd(String metric, Object value, MetricDataType type) throws Exception {
        return new SparkplugBPayloadEncoder().getBytes(new SparkplugBPayloadBuilder().setTimestamp(new java.util.Date())
                .addMetric(new MetricBuilder(metric, type, value).createMetric()).createPayload(), false);
    }

    @Test
    void transportTranslatesRebirthAndStateOnly() throws Exception {
        assertInstanceOf(EdgeSession.Rebirth.class,
                PahoTransport.translate("spBv1.0/g/NCMD/e", ncmd(SparkplugCodec.REBIRTH, true, MetricDataType.Boolean)));
        assertInstanceOf(EdgeSession.Rebirth.class, PahoTransport.translate("spBv1.0/g/NCMD/e",
                new SparkplugBPayloadEncoder().getBytes(SparkplugCodec.buildRebirthCommand(), false)));
        assertNull(PahoTransport.translate("spBv1.0/g/NCMD/e", ncmd(SparkplugCodec.REBIRTH, false, MetricDataType.Boolean)));
        assertNull(PahoTransport.translate("spBv1.0/g/NCMD/e", ncmd("Rpm", 9999.0, MetricDataType.Double)));
        assertNull(PahoTransport.translate("spBv1.0/g/DCMD/e/d", ncmd(SparkplugCodec.REBIRTH, true, MetricDataType.Boolean)));

        EdgeSession.Event s = PahoTransport.translate("spBv1.0/STATE/host",
                "{\"online\":true,\"timestamp\":1700000000000}".getBytes(StandardCharsets.UTF_8));
        assertEquals(new EdgeSession.HostState(true, 1700000000000L), s);
        assertNull(PahoTransport.translate("spBv1.0/STATE/host", "{\"online\":\"yes\"}".getBytes(StandardCharsets.UTF_8)));
        // A non-boolean online must not read as offline and tear the session down.
        assertNull(PahoTransport.translate("spBv1.0/STATE/host",
                "{\"online\":\"yes\",\"timestamp\":1700000000000}".getBytes(StandardCharsets.UTF_8)));
        assertNull(PahoTransport.translate("spBv1.0/STATE/host", "not json".getBytes(StandardCharsets.UTF_8)));
    }
}
