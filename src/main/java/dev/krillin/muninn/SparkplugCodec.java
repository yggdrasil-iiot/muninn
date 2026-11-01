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

    /** Sparkplug 3.0 birth/death sequence metric: same value in the CONNECT Will (NDEATH) and NBIRTH. */
    public static final String BD_SEQ = "bdSeq";

    /** Sparkplug 3.0 node control metric every NBIRTH carries ({@code false}); NCMD {@code true} asks for a rebirth. */
    public static final String REBIRTH = "Node Control/Rebirth";

    private SparkplugCodec() {
    }

    /**
     * Session NBIRTH ({@code seq == 0}): the {@link #GOVERNED_DEFINITION} metric with the same exact
     * bytes as {@link #buildNbirth}, then {@link #BD_SEQ} (Int64) and {@link #REBIRTH} ({@code false}),
     * both required by Sparkplug 3.0 for a node that keeps its session.
     */
    public static SparkplugBPayload buildSessionNbirth(byte[] defBytes, long bdSeq) throws Exception {
        return new SparkplugBPayloadBuilder().setTimestamp(new Date()).setSeq(0L)
                .addMetric(new MetricBuilder(GOVERNED_DEFINITION, MetricDataType.Bytes, defBytes).createMetric())
                .addMetric(new MetricBuilder(BD_SEQ, MetricDataType.Int64, bdSeq).createMetric())
                .addMetric(new MetricBuilder(REBIRTH, MetricDataType.Boolean, false).createMetric())
                .createPayload();
    }

    /** NDEATH: the {@link #BD_SEQ} metric only, and no {@code seq} (Sparkplug 3.0 forbids one). */
    public static SparkplugBPayload buildNdeath(long bdSeq) throws Exception {
        return new SparkplugBPayloadBuilder().setTimestamp(new Date())
                .addMetric(new MetricBuilder(BD_SEQ, MetricDataType.Int64, bdSeq).createMetric())
                .createPayload();
    }

    /** NCMD a Primary Host sends to ask for a rebirth: {@link #REBIRTH} = {@code true}. */
    public static SparkplugBPayload buildRebirthCommand() throws Exception {
        return new SparkplugBPayloadBuilder().setTimestamp(new Date())
                .addMetric(new MetricBuilder(REBIRTH, MetricDataType.Boolean, true).createMetric())
                .createPayload();
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
        return buildSamples(conformant, seq);
    }

    /**
     * DBIRTH at the given {@code seq}: declares every conformant member the device will report,
     * with its current value. Same metric shape as NDATA; the device level lives in the topic.
     */
    public static SparkplugBPayload buildDbirth(List<SampledMember> conformant, long seq) throws Exception {
        return buildSamples(conformant, seq);
    }

    /** DDATA at the given {@code seq} carrying one metric per conformant sample. */
    public static SparkplugBPayload buildDdata(List<SampledMember> conformant, long seq) throws Exception {
        return buildSamples(conformant, seq);
    }

    private static SparkplugBPayload buildSamples(List<SampledMember> conformant, long seq) throws Exception {
        SparkplugBPayloadBuilder b = new SparkplugBPayloadBuilder().setTimestamp(new Date()).setSeq(seq);
        for (SampledMember s : conformant) {
            b.addMetric(new MetricBuilder(s.name(), s.type(), s.value()).createMetric());
        }
        return b.createPayload();
    }
}
