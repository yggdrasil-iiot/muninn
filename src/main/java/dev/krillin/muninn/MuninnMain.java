package dev.krillin.muninn;

import java.nio.file.Path;
import java.util.List;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.tahu.message.SparkplugBPayloadEncoder;
import org.eclipse.tahu.message.model.MetricDataType;

/**
 * Muninn CLI entrypoint (positional args; exit {@code 0} ok / {@code 1} governance-refuse /
 * {@code 2} usage-or-error). Mirrors mimir's live-shell discipline.
 *
 * <pre>
 *   feed    &lt;registryDir&gt; &lt;ref&gt; &lt;version&gt; &lt;opcEndpoint&gt; &lt;nsUri&gt; &lt;instanceNodeIdent&gt;
 *           &lt;mqttUrl&gt; &lt;group&gt; &lt;edge&gt; [--inject-bogus &lt;member&gt;]
 *   observe &lt;mqttUrl&gt; &lt;group&gt; &lt;birthOutFile&gt; &lt;ndataOutFile&gt;
 *           [--expect-ndata N] [--timeout-ms M]
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
        for (int i = 10; i < args.length; i++) {
            if ("--inject-bogus".equals(args[i]) && i + 1 < args.length) {
                injectBogus = args[++i];
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
                        ? new SampledMember(s.name(), MetricDataType.String, s.value())
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

        // 7. NDATA — only conformant samples.
        pub.publish(MqttPublisher.topic(group, "NDATA", edge),
                new SparkplugBPayloadEncoder().getBytes(SparkplugCodec.buildNdata(ok, 1L), false),
                0, false);
        System.out.println("[MUNINN] birthed " + v.bytes().length + " def bytes; published "
                + ok.size() + "/" + injected.size() + " members");

        c.disconnect();
        pub.close();
        System.exit(0);
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

    private static void usage() {
        System.err.println("Usage:");
        System.err.println("  feed <registryDir> <ref> <version> <opcEndpoint> <nsUri> "
                + "<instanceNodeIdent> <mqttUrl> <group> <edge> [--inject-bogus <member>]");
        System.err.println("  observe <mqttUrl> <group> <birthOutFile> <ndataOutFile> "
                + "[--expect-ndata N] [--timeout-ms M] [--ndata-values <file>]");
    }
}
