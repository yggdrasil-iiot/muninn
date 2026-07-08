package dev.krillin.muninn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.tahu.message.SparkplugBPayloadDecoder;
import org.eclipse.tahu.message.SparkplugBPayloadEncoder;
import org.eclipse.tahu.message.model.Metric;
import org.eclipse.tahu.message.model.MetricDataType;
import org.eclipse.tahu.message.model.SparkplugBPayload;
import org.junit.jupiter.api.Test;

/**
 * The closed-loop gate asserts the UNS observation's VALUE changes (Rpm 1535 -> 1500). That
 * requires the observer to render each NDATA metric as {@code name=value}, and the value must
 * survive the Sparkplug B protobuf round-trip. This locks both.
 */
class NdataValueLineTest {

    @Test
    void doubleMetricRendersAsNameEqualsValue() throws Exception {
        Metric m = new Metric.MetricBuilder("Rpm", MetricDataType.Double, 1500.0).createMetric();
        assertEquals("Rpm=1500.0", MuninnConsumer.valueLine(m));
    }

    @Test
    void valueSurvivesSparkplugRoundTripThenRenders() throws Exception {
        SparkplugBPayload ndata = SparkplugCodec.buildNdata(
                List.of(new SampledMember("Rpm", MetricDataType.Double, 1500.0)), 1L);
        byte[] wire = new SparkplugBPayloadEncoder().getBytes(ndata, false);
        SparkplugBPayload decoded = new SparkplugBPayloadDecoder().buildFromByteArray(wire, null);

        List<String> lines = decoded.getMetrics().stream().map(MuninnConsumer::valueLine).toList();
        assertTrue(lines.contains("Rpm=1500.0"), "expected Rpm=1500.0 in " + lines);
    }
}
