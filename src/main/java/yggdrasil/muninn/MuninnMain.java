package yggdrasil.muninn;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.tahu.message.SparkplugBPayloadEncoder;
import org.eclipse.tahu.message.model.MetricDataType;

/**
 * Muninn CLI entrypoint (positional args; exit {@code 0} ok / {@code 1} governance-refuse /
 * {@code 2} usage-or-error). Mirrors mimir's live-shell discipline.
 *
 * <pre>
 *   feed    &lt;registryDir&gt; &lt;ref&gt; &lt;version&gt; &lt;opcEndpoint&gt; &lt;nsUri&gt; &lt;instanceNodeIdent&gt;
 *           &lt;mqttUrl&gt; &lt;group&gt; &lt;edge&gt; [--inject-bogus &lt;member&gt;]
 *           [--hierarchy &lt;mappingsDir&gt; &lt;stream&gt; &lt;equipmentInstanceId&gt;]
 *           [--session &lt;periodMs&gt; [--max-cycles N] [--primary-host &lt;hostId&gt;]]
 *   observe &lt;mqttUrl&gt; &lt;group&gt; &lt;birthOutFile&gt; &lt;ndataOutFile&gt;
 *           [--expect-ndata N] [--timeout-ms M]
 *   host    &lt;mqttUrl&gt; state &lt;hostId&gt; &lt;online&gt; &lt;timestamp&gt;
 *   host    &lt;mqttUrl&gt; rebirth &lt;group&gt; &lt;edge&gt;
 * </pre>
 */
public final class MuninnMain {

    private MuninnMain() {
    }

    public static void main(String[] args) {
        try {
            if (args.length == 0) {
                usage();
                System.exit(2);
            }
            switch (args[0]) {
                case "feed" -> feed(args);
                case "observe" -> observe(args);
                case "host" -> host(args);
                default -> {
                    usage();
                    System.exit(2);
                }
            }
        } catch (Exception e) {
            System.err.println("[MUNINN] error: " + e.getMessage());
            System.exit(2);
        }
    }

    private static void feed(String[] args) throws Exception {
        // feed <registryDir> <ref> <version> <opcEndpoint> <nsUri> <instanceNodeIdent> <mqttUrl> <group> <edge>
        if (args.length < 10) {
            usage();
            System.exit(2);
        }
        String registryDir = args[1];
        String ref = args[2];
        String version = args[3];
        String opcEndpoint = args[4];
        String nsUri = args[5];
        String instanceNodeIdent = args[6];
        String mqttUrl = args[7];
        String group = args[8];
        String edge = args[9];

        String injectBogus = null;
        String[] hierarchy = null;
        long sessionPeriod = 0;
        int maxCycles = 0;
        String primaryHost = null;
        for (int i = 10; i < args.length; i++) {
            if ("--inject-bogus".equals(args[i]) && i + 1 < args.length) {
                injectBogus = args[++i];
            } else if ("--hierarchy".equals(args[i]) && i + 3 < args.length) {
                hierarchy = new String[] {args[i + 1], args[i + 2], args[i + 3]};
                i += 3;
            } else if ("--session".equals(args[i]) && i + 1 < args.length) {
                sessionPeriod = Long.parseLong(args[++i]);
            } else if ("--max-cycles".equals(args[i]) && i + 1 < args.length) {
                maxCycles = Integer.parseInt(args[++i]);
            } else if ("--primary-host".equals(args[i]) && i + 1 < args.length) {
                primaryHost = args[++i];
            } else {
                System.err.println("unknown argument: " + args[i]);
                usage();
                System.exit(2);
            }
        }

        // 1. Provenance gate — refuse BEFORE any MQTT/OPC connect (CF-P2).
        VerifiedDefinition v = null;
        try {
            v = ProvenanceVerifier.verify(Path.of(registryDir), ref, version);
        } catch (ProvenanceException e) {
            System.err.println("[MUNINN] refuse: " + e.getMessage());
            System.exit(1);
        }

        // 1b. Optional ISA-95 location — resolved and verified before any connect, like provenance.
        String device = null;
        if (hierarchy != null) {
            try {
                device = HierarchyLocator.locate(Path.of(hierarchy[0]), hierarchy[1], hierarchy[2]);
            } catch (ProvenanceException e) {
                System.err.println("[MUNINN] refuse: " + e.getMessage());
                System.exit(1);
            }
        }

        if (sessionPeriod <= 0 && (maxCycles != 0 || primaryHost != null)) {
            System.err.println("--max-cycles and --primary-host need --session");
            usage();
            System.exit(2);
        }
        if (sessionPeriod > 0) {
            System.exit(session(registryDir, ref, version, hierarchy, opcEndpoint, nsUri, instanceNodeIdent,
                    mqttUrl, group, edge, injectBogus, sessionPeriod, maxCycles, primaryHost));
        }

        // 2. Re-parse the governed contract from the verified bytes.
        UdtDefinition def = DefinitionJson.fromBytes(v.bytes());

        // 3. NBIRTH — governed_definition carries the EXACT verified bytes (CF-P2/P4/B).
        MqttPublisher pub = new MqttPublisher(mqttUrl, edge);
        pub.publish(MqttPublisher.topic(group, "NBIRTH", edge),
                new SparkplugBPayloadEncoder().getBytes(SparkplugCodec.buildNbirth(v.bytes()), false),
                0, false);

        // 4. Sample the live equipment INSTANCE over OPC-UA (faithful browse).
        OpcUaClient c = OpcUaClient.create(opcEndpoint);
        c.connect();
        List<SampledMember> raw = OpcUaInstanceBrowser.sample(c, nsUri, instanceNodeIdent);

        // 5. Apply --inject-bogus HERE (assembly layer — CF-DROP): swap the named member's
        //    type to a deliberately WRONG MetricDataType.String; leave all others intact.
        final String bogus = injectBogus;
        List<SampledMember> injected = raw.stream()
                .map(s -> bogus != null && bogus.equals(s.name())
                        ? s.withType(MetricDataType.String)
                        : s)
                .toList();

        // 6. Egress-validate; drop + log non-conformant samples.
        List<SampledMember> ok = injected.stream()
                .filter(s -> EgressValidator.conformant(s, def))
                .toList();
        for (SampledMember s : injected) {
            if (!EgressValidator.conformant(s, def)) {
                System.out.println("[MUNINN] drop " + s.name()
                        + " (type " + s.type() + " not conformant)");
            }
        }

        // 7. Only conformant samples. Without a hierarchy: NDATA on the node, as before. With one:
        //    the equipment is a Sparkplug device named by its ISA-95 path — DBIRTH then DDATA.
        if (device == null) {
            pub.publish(MqttPublisher.topic(group, "NDATA", edge),
                    new SparkplugBPayloadEncoder().getBytes(SparkplugCodec.buildNdata(ok, 1L), false),
                    0, false);
        } else {
            pub.publish(MqttPublisher.topic(group, "DBIRTH", edge, device),
                    new SparkplugBPayloadEncoder().getBytes(SparkplugCodec.buildDbirth(ok, 1L), false),
                    0, false);
            pub.publish(MqttPublisher.topic(group, "DDATA", edge, device),
                    new SparkplugBPayloadEncoder().getBytes(SparkplugCodec.buildDdata(ok, 2L), false),
                    0, false);
            System.out.println("[MUNINN] device " + device);
        }
        System.out.println("[MUNINN] birthed " + v.bytes().length + " def bytes; published "
                + ok.size() + "/" + injected.size() + " members");

        c.disconnect();
        pub.close();
        System.exit(0);
    }

    /**
     * {@code feed --session}: the inputs already verified once (refusal before any connect); the
     * session verifies them again at every (re)birth. Ctrl-C or a stop signal ends it with NDEATH.
     */
    private static int session(String registryDir, String ref, String version, String[] hierarchy,
            String opcEndpoint, String nsUri, String instanceNodeIdent, String mqttUrl, String group,
            String edge, String injectBogus, long periodMs, int maxCycles, String primaryHost) throws Exception {
        EdgeSession.Verifier verifier = () -> {
            VerifiedDefinition v = ProvenanceVerifier.verify(Path.of(registryDir), ref, version);
            String device = hierarchy == null ? null
                    : HierarchyLocator.locate(Path.of(hierarchy[0]), hierarchy[1], hierarchy[2]);
            try {
                return new EdgeSession.Birth(v.bytes(), DefinitionJson.fromBytes(v.bytes()), device);
            } catch (Exception e) {
                throw new ProvenanceException("governed definition does not parse", e);
            }
        };
        OpcUaClient c = OpcUaClient.create(opcEndpoint);
        c.connect();
        EdgeSession.Sampler sampler = () -> OpcUaInstanceBrowser.sample(c, nsUri, instanceNodeIdent).stream()
                .map(s -> injectBogus != null && injectBogus.equals(s.name())
                        ? s.withType(MetricDataType.String)
                        : s)
                .toList();

        BlockingQueue<EdgeSession.Event> events = new LinkedBlockingQueue<>();
        EdgeSession session = new EdgeSession(new PahoTransport(mqttUrl, edge, events), verifier, sampler,
                group, edge, primaryHost);
        CountDownLatch done = new CountDownLatch(1);
        Thread hook = shutdownStop(events, done);
        Runtime.getRuntime().addShutdownHook(hook);
        int code;
        try {
            System.out.println("[MUNINN] session up: period " + periodMs + " ms"
                    + (primaryHost == null ? "" : ", waiting for host " + primaryHost));
            code = session.run(events, periodMs, maxCycles);
        } finally {
            done.countDown();
            c.disconnect();
        }
        System.out.println("[MUNINN] session ended after " + session.dataCycles() + " data cycles, exit " + code);
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException alreadyShuttingDown) {
            // The hook asked for this stop; the JVM is exiting anyway.
        }
        return code;
    }

    /**
     * The shutdown hook (Ctrl-C, SIGTERM): asks the session loop to stop — NDEATH, then disconnect —
     * and holds the JVM up to 5 s until {@code done} says the loop has finished.
     */
    static Thread shutdownStop(BlockingQueue<EdgeSession.Event> events, CountDownLatch done) {
        return new Thread(() -> {
            events.add(new EdgeSession.Stop());
            try {
                done.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private static void observe(String[] args) throws Exception {
        // observe <mqttUrl> <group> <birthOutFile> <ndataOutFile>
        //         [--expect-ndata N] [--timeout-ms M] [--ndata-values <file>]
        if (args.length < 5) {
            usage();
            System.exit(2);
        }
        String mqttUrl = args[1];
        String group = args[2];
        String birthOutFile = args[3];
        String ndataOutFile = args[4];

        int expectNdata = 4;
        long timeoutMs = 20000L;
        String valuesOutFile = null;
        for (int i = 5; i < args.length; i++) {
            if ("--expect-ndata".equals(args[i]) && i + 1 < args.length) {
                expectNdata = Integer.parseInt(args[++i]);
            } else if ("--timeout-ms".equals(args[i]) && i + 1 < args.length) {
                timeoutMs = Long.parseLong(args[++i]);
            } else if ("--ndata-values".equals(args[i]) && i + 1 < args.length) {
                valuesOutFile = args[++i];
            } else {
                System.err.println("unknown argument: " + args[i]);
                usage();
                System.exit(2);
            }
        }

        MuninnConsumer consumer = new MuninnConsumer(mqttUrl, group,
                Path.of(birthOutFile), Path.of(ndataOutFile),
                valuesOutFile != null ? Path.of(valuesOutFile) : null, expectNdata);
        boolean got = consumer.awaitCapture(timeoutMs);
        consumer.close();
        System.exit(got ? 0 : 2);
    }

    /**
     * Gate harness acting as a Primary Host Application: publishes its STATE (QoS 1, retained,
     * as Sparkplug 3.0 requires) or an NCMD {@code Node Control/Rebirth = true} to one edge node.
     */
    private static void host(String[] args) throws Exception {
        if (args.length == 6 && "state".equals(args[2])) {
            String json = "{\"online\":" + Boolean.parseBoolean(args[4]) + ",\"timestamp\":"
                    + Long.parseLong(args[5]) + "}";
            hostPublish(args[1], EdgeSession.stateTopic(args[3]), json.getBytes(StandardCharsets.UTF_8), true);
        } else if (args.length == 5 && "rebirth".equals(args[2])) {
            hostPublish(args[1], MqttPublisher.topic(args[3], "NCMD", args[4]),
                    new SparkplugBPayloadEncoder().getBytes(SparkplugCodec.buildRebirthCommand(), false), false);
        } else {
            usage();
            System.exit(2);
        }
        System.exit(0);
    }

    private static void hostPublish(String mqttUrl, String topic, byte[] payload, boolean retained) throws Exception {
        MqttClient client = new MqttClient(mqttUrl, "muninn-host", new MemoryPersistence());
        MqttConnectOptions o = new MqttConnectOptions();
        o.setCleanSession(true);
        client.connect(o);
        client.publish(topic, payload, 1, retained);
        client.disconnect();
        client.close();
        System.out.println("[HOST] published " + topic);
    }

    private static void usage() {
        System.err.println("Usage:");
        System.err.println("  feed <registryDir> <ref> <version> <opcEndpoint> <nsUri> "
                + "<instanceNodeIdent> <mqttUrl> <group> <edge> [--inject-bogus <member>] "
                + "[--hierarchy <mappingsDir> <stream> <equipmentInstanceId>] "
                + "[--session <periodMs> [--max-cycles N] [--primary-host <hostId>]]");
        System.err.println("  observe <mqttUrl> <group> <birthOutFile> <ndataOutFile> "
                + "[--expect-ndata N] [--timeout-ms M] [--ndata-values <file>]");
        System.err.println("  host <mqttUrl> state <hostId> <online> <timestamp>");
        System.err.println("  host <mqttUrl> rebirth <group> <edge>");
    }
}
