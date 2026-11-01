package dev.krillin.muninn;

import java.math.BigInteger;
import java.util.Date;
import java.util.List;

import org.eclipse.tahu.message.model.Metric.MetricBuilder;
import org.eclipse.tahu.message.model.MetricDataType;
import org.eclipse.tahu.message.model.PropertyDataType;
import org.eclipse.tahu.message.model.PropertySet;
import org.eclipse.tahu.message.model.PropertySet.PropertySetBuilder;
import org.eclipse.tahu.message.model.PropertyValue;
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
 *
 * <p>Every sample metric (DBIRTH, NDATA, DDATA) carries its OPC-UA metadata: the metric
 * {@code timestamp} is the OPC-UA source timestamp, never the send time (the payload
 * {@code timestamp} is the send time), and is omitted when the server returned none. Properties:
 * {@link #QUALITY} (the Sparkplug 3.0 well-known property, a lossy projection, see
 * {@link #quality}), {@link #UA_STATUSCODE} (the raw 32-bit StatusCode, lossless) and
 * {@link #UA_SERVER_TIMESTAMP} (omitted when the server returned none).
 */
public final class SparkplugCodec {

    /** Name of the {@code Bytes} metric that carries the governed definition's exact bytes. */
    public static final String GOVERNED_DEFINITION = "governed_definition";

    /** Sparkplug 3.0 birth/death sequence metric: same value in the CONNECT Will (NDEATH) and NBIRTH. */
    public static final String BD_SEQ = "bdSeq";

    /** Sparkplug 3.0 node control metric every NBIRTH carries ({@code false}); NCMD {@code true} asks for a rebirth. */
    public static final String REBIRTH = "Node Control/Rebirth";

    /** Sparkplug 3.0 well-known metric property: Int32, 192 GOOD / 0 BAD / 500 STALE. */
    public static final String QUALITY = "Quality";

    /** Raw 32-bit OPC-UA StatusCode (UInt32): severity, sub-code and flag bits, no loss. */
    public static final String UA_STATUSCODE = "ua_statuscode";

    /** OPC-UA server timestamp in epoch milliseconds (UInt64). */
    public static final String UA_SERVER_TIMESTAMP = "ua_server_timestamp";

    static final int QUALITY_GOOD = 192;
    static final int QUALITY_BAD = 0;
    static final int QUALITY_STALE = 500;

    private SparkplugCodec() {
    }

    /**
     * Project a StatusCode onto the Sparkplug {@code Quality} property, the same rule as the
     * predecessor lab's {@code UaSideChannel}: severity Bad or reserved is BAD;
     * {@code Uncertain_LastUsableValue} (info bits ignored) is STALE; Good and every other
     * Uncertain code is GOOD, because Sparkplug 3.0 has no Uncertain value. The truth stays in
     * {@link #UA_STATUSCODE}.
     */
    static int quality(long statusCode) {
        long severity = statusCode & 0xC0000000L;
        if (severity == 0x80000000L || severity == 0xC0000000L) {
            return QUALITY_BAD;
        }
        if ((statusCode & 0xFFFF0000L) == 0x40900000L) {
            return QUALITY_STALE;
        }
        return QUALITY_GOOD;
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
            b.addMetric(new MetricBuilder(s.name(), s.type(), s.value())
                    .timestamp(s.sourceTimestamp() != null ? new Date(s.sourceTimestamp()) : null)
                    .properties(sampleProperties(s))
                    .createMetric());
        }
        return b.createPayload();
    }

    private static PropertySet sampleProperties(SampledMember s) throws Exception {
        PropertySetBuilder p = new PropertySetBuilder()
                .addProperty(QUALITY, new PropertyValue<>(PropertyDataType.Int32, quality(s.statusCode())))
                .addProperty(UA_STATUSCODE, new PropertyValue<>(PropertyDataType.UInt32, s.statusCode()));
        if (s.serverTimestamp() != null) {
            p.addProperty(UA_SERVER_TIMESTAMP, new PropertyValue<>(PropertyDataType.UInt64,
                    BigInteger.valueOf(s.serverTimestamp())));
        }
        return p.createPropertySet();
    }
}
