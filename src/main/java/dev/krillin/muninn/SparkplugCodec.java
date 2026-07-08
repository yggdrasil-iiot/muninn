package dev.krillin.muninn;

import java.util.Date;
import java.util.List;

import org.eclipse.tahu.message.model.Metric.MetricBuilder;
import org.eclipse.tahu.message.model.MetricDataType;
import org.eclipse.tahu.message.model.SparkplugBPayload;
import org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder;

/**
 * Builds Sparkplug B payloads for the governed-equipment node.
 *
 * <p>CF-B: the NBIRTH carries the governed definition's EXACT bytes so a downstream
 * assertion can prove "NBIRTH bytes == the governed registry file, byte-for-byte".
 * A Tahu UDT {@code Template} is structured and lossy (no slot for the def's
 * {@code semanticId}/range), so it cannot be byte-equal. Instead the raw verified
 * bytes ride in a dedicated {@link MetricDataType#Bytes} metric named
 * {@link #GOVERNED_DEFINITION}, which protobuf-round-trips byte-exact.
 */
public final class SparkplugCodec {

    /** Name of the {@code Bytes} metric that carries the governed definition's exact bytes. */
    public static final String GOVERNED_DEFINITION = "governed_definition";

    private SparkplugCodec() {
    }

    /**
     * NBIRTH ({@code seq == 0}) carrying the governed definition's exact bytes in the
     * {@link #GOVERNED_DEFINITION} {@link MetricDataType#Bytes} metric (CF-B byte-exact).
     */
    public static SparkplugBPayload buildNbirth(byte[] defBytes) throws Exception {
        return new SparkplugBPayloadBuilder().setTimestamp(new Date()).setSeq(0L)
                .addMetric(new MetricBuilder(GOVERNED_DEFINITION, MetricDataType.Bytes, defBytes).createMetric())
                .createPayload();
    }

    /**
     * NDATA at the given {@code seq} carrying one metric per conformant sample
     * (name + already-resolved Tahu type + value).
     */
    public static SparkplugBPayload buildNdata(List<SampledMember> conformant, long seq) throws Exception {
        SparkplugBPayloadBuilder b = new SparkplugBPayloadBuilder().setTimestamp(new Date()).setSeq(seq);
        for (SampledMember s : conformant) {
            b.addMetric(new MetricBuilder(s.name(), s.type(), s.value()).createMetric());
        }
        return b.createPayload();
    }
}
