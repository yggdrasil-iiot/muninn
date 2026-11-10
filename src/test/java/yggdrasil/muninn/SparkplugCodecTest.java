package yggdrasil.muninn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.tahu.message.SparkplugBPayloadDecoder;
import org.eclipse.tahu.message.SparkplugBPayloadEncoder;
import org.eclipse.tahu.message.model.Metric;
import org.eclipse.tahu.message.model.MetricDataType;
import org.eclipse.tahu.message.model.SparkplugBPayload;
import org.junit.jupiter.api.Test;

/**
 * Hermetic (no MQTT) codec round-trip. The whole point (CF-B): the NBIRTH must
 * carry the governed definition's EXACT bytes so a downstream assertion can prove
 * "NBIRTH bytes == the governed registry file, byte-for-byte". A Tahu UDT Template
 * is lossy (no slot for semanticId/range), so muninn carries the raw verified bytes
 * in a dedicated {@code Bytes} metric named {@code governed_definition} that
 * protobuf-round-trips byte-exact. These tests encode then decode and assert exactness.
 */
class SparkplugCodecTest {

    @Test
    void nbirthCarriesGovernedDefinitionBytesExactThroughProtobuf() throws Exception {
        byte[] defBytes = "{\"templateRef\":\"Line1-Mixer\"}".getBytes(StandardCharsets.UTF_8);

        SparkplugBPayload birth = SparkplugCodec.buildNbirth(defBytes);
        byte[] wire = new SparkplugBPayloadEncoder().getBytes(birth, false);
        SparkplugBPayload rt = new SparkplugBPayloadDecoder().buildFromByteArray(wire, null);

        Metric governed = null;
        for (Metric m : rt.getMetrics()) {
            if (SparkplugCodec.GOVERNED_DEFINITION.equals(m.getName())) {
                governed = m;
                break;
            }
        }
        assertNotNull(governed, "decoded payload must contain the governed_definition metric");

        // CF-B: byte-exact through protobuf.
        assertTrue(Arrays.equals(defBytes, (byte[]) governed.getValue()),
                "governed_definition bytes must survive protobuf round-trip byte-for-byte");
        // Value-equality (not reference ==) — robust if the decoder rebuilds the type.
        assertEquals("Bytes", governed.getDataType().toString());
        assertEquals(0L, birth.getSeq());
    }

    @Test
    void ndataCarriesConformantSamplesWithGivenSeq() throws Exception {
        List<SampledMember> samples = List.of(
                new SampledMember("Rpm", MetricDataType.Double, 1535.0, 0L, null, null),
                new SampledMember("Running", MetricDataType.Boolean, true, 0L, null, null));

        SparkplugBPayload ndata = SparkplugCodec.buildNdata(samples, 1L);
        byte[] wire = new SparkplugBPayloadEncoder().getBytes(ndata, false);
        SparkplugBPayload rt = new SparkplugBPayloadDecoder().buildFromByteArray(wire, null);

        Set<String> names = rt.getMetrics().stream().map(Metric::getName).collect(Collectors.toSet());
        assertEquals(Set.of("Rpm", "Running"), names);

        Double rpm = null;
        for (Metric m : rt.getMetrics()) {
            if ("Rpm".equals(m.getName())) {
                rpm = (Double) m.getValue();
            }
        }
        assertEquals(1535.0, rpm);
        assertEquals(1L, ndata.getSeq());
    }

    /** --hierarchy feed: DBIRTH (seq 1) declares the members, DDATA (seq 2) reports them. */
    @Test
    void dbirthThenDdataCarryTheSameMembersInSequence() throws Exception {
        List<SampledMember> samples = List.of(
                new SampledMember("Rpm", MetricDataType.Double, 1535.0, 0L, null, null),
                new SampledMember("Running", MetricDataType.Boolean, true, 0L, null, null));

        for (SparkplugBPayload p : List.of(SparkplugCodec.buildDbirth(samples, 1L),
                SparkplugCodec.buildDdata(samples, 2L))) {
            byte[] wire = new SparkplugBPayloadEncoder().getBytes(p, false);
            SparkplugBPayload rt = new SparkplugBPayloadDecoder().buildFromByteArray(wire, null);
            assertEquals(Set.of("Rpm", "Running"),
                    rt.getMetrics().stream().map(Metric::getName).collect(Collectors.toSet()));
        }
        assertEquals(1L, SparkplugCodec.buildDbirth(samples, 1L).getSeq());
        assertEquals(2L, SparkplugCodec.buildDdata(samples, 2L).getSeq());
        assertEquals("spBv1.0/g/DBIRTH/e/site:line",
                MqttPublisher.topic("g", "DBIRTH", "e", "site:line"));
    }
}
