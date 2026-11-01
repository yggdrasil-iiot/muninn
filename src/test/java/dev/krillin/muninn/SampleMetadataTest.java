package dev.krillin.muninn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigInteger;
import java.util.List;

import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.tahu.message.SparkplugBPayloadDecoder;
import org.eclipse.tahu.message.SparkplugBPayloadEncoder;
import org.eclipse.tahu.message.model.Metric;
import org.eclipse.tahu.message.model.MetricDataType;
import org.eclipse.tahu.message.model.SparkplugBPayload;
import org.junit.jupiter.api.Test;

/**
 * Each sample metric carries what the OPC-UA read returned: the source timestamp as the metric
 * timestamp, the raw StatusCode, the server timestamp and the Sparkplug {@code Quality}
 * projection, through a real protobuf encode/decode. Absent timestamps stay absent; nothing is
 * filled in with the send time.
 */
class SampleMetadataTest {

    static final long SOURCE = 1_762_000_000_123L;
    static final long SERVER = 1_762_000_000_456L;
    static final long BAD_SENSOR_FAILURE = 0x808B0000L;
    static final long UNCERTAIN_LAST_USABLE = 0x40900000L;

    @Test
    void qualityProjectsTheSeverityBits() {
        assertEquals(192, SparkplugCodec.quality(0L));
        assertEquals(0, SparkplugCodec.quality(BAD_SENSOR_FAILURE));
        assertEquals(0, SparkplugCodec.quality(0x80000000L));
        assertEquals(0, SparkplugCodec.quality(0xC0000000L), "reserved severity is BAD");
        assertEquals(500, SparkplugCodec.quality(UNCERTAIN_LAST_USABLE));
        assertEquals(500, SparkplugCodec.quality(UNCERTAIN_LAST_USABLE | 0x0400L), "info bits ignored");
        assertEquals(192, SparkplugCodec.quality(0x40000000L), "other Uncertain codes project to GOOD");
        assertEquals(192, SparkplugCodec.quality(0x408F0000L));
    }

    @Test
    void ddataCarriesSourceTimeStatusCodeAndServerTime() throws Exception {
        Metric temp = roundTrip(SparkplugCodec.buildDdata(List.of(
                new SampledMember("Temp", MetricDataType.Double, 200.0, BAD_SENSOR_FAILURE, SOURCE, SERVER)), 2L));

        assertEquals(200.0, temp.getValue());
        assertEquals(SOURCE, temp.getTimestamp().getTime());
        assertEquals(0, temp.getProperties().getPropertyValue(SparkplugCodec.QUALITY).getValue());
        assertEquals(BAD_SENSOR_FAILURE,
                ((Number) temp.getProperties().getPropertyValue(SparkplugCodec.UA_STATUSCODE).getValue()).longValue());
        assertEquals(BigInteger.valueOf(SERVER),
                temp.getProperties().getPropertyValue(SparkplugCodec.UA_SERVER_TIMESTAMP).getValue());
    }

    @Test
    void birthAndNdataCarryTheSameMetadata() throws Exception {
        List<SampledMember> s = List.of(
                new SampledMember("Temp", MetricDataType.Double, 200.0, UNCERTAIN_LAST_USABLE, SOURCE, SERVER));
        for (SparkplugBPayload p : List.of(SparkplugCodec.buildDbirth(s, 1L), SparkplugCodec.buildNdata(s, 1L))) {
            Metric temp = roundTrip(p);
            assertEquals(SOURCE, temp.getTimestamp().getTime());
            assertEquals(500, temp.getProperties().getPropertyValue(SparkplugCodec.QUALITY).getValue());
            assertEquals(UNCERTAIN_LAST_USABLE,
                    ((Number) temp.getProperties().getPropertyValue(SparkplugCodec.UA_STATUSCODE).getValue()).longValue());
        }
    }

    @Test
    void absentTimestampsAreOmittedNotFilledWithSendTime() throws Exception {
        Metric temp = roundTrip(SparkplugCodec.buildDdata(List.of(
                new SampledMember("Temp", MetricDataType.Double, 200.0, 0L, null, null)), 2L));

        assertNull(temp.getTimestamp(), "no source timestamp, no metric timestamp");
        assertFalse(temp.getProperties().containsKey(SparkplugCodec.UA_SERVER_TIMESTAMP));
        assertEquals(192, temp.getProperties().getPropertyValue(SparkplugCodec.QUALITY).getValue());
        assertEquals(0L,
                ((Number) temp.getProperties().getPropertyValue(SparkplugCodec.UA_STATUSCODE).getValue()).longValue());
    }

    @Test
    void injectBogusKeepsTheMetadata() {
        SampledMember s = new SampledMember("Temp", MetricDataType.Double, 200.0, BAD_SENSOR_FAILURE, SOURCE, SERVER);
        assertEquals(new SampledMember("Temp", MetricDataType.String, 200.0, BAD_SENSOR_FAILURE, SOURCE, SERVER),
                s.withType(MetricDataType.String));
    }

    @Test
    void nullOpcUaTimestampsBecomeNull() {
        assertNull(OpcUaInstanceBrowser.epochMillis(null));
        assertNull(OpcUaInstanceBrowser.epochMillis(DateTime.NULL_VALUE));
        DateTime t = new DateTime(new java.util.Date(SOURCE));
        assertEquals(SOURCE, OpcUaInstanceBrowser.epochMillis(t));
    }

    @Test
    void observerRendersTheMetadata() throws Exception {
        Metric bad = roundTrip(SparkplugCodec.buildDdata(List.of(
                new SampledMember("Temp", MetricDataType.Double, 200.0, BAD_SENSOR_FAILURE, SOURCE, SERVER)), 2L));
        assertEquals("[OBSERVE] sample Temp quality=0 ua_statuscode=0x808B0000 ts=" + SOURCE + " server=" + SERVER,
                MuninnConsumer.metadataLine(bad));
        Metric bare = roundTrip(SparkplugCodec.buildDdata(List.of(
                new SampledMember("Temp", MetricDataType.Double, 200.0, 0L, null, null)), 2L));
        assertEquals("[OBSERVE] sample Temp quality=192 ua_statuscode=0x00000000 ts=- server=-",
                MuninnConsumer.metadataLine(bare));
    }

    private static Metric roundTrip(SparkplugBPayload p) throws Exception {
        byte[] wire = new SparkplugBPayloadEncoder().getBytes(p, false);
        SparkplugBPayload rt = new SparkplugBPayloadDecoder().buildFromByteArray(wire, null);
        assertEquals(1, rt.getMetrics().size());
        return rt.getMetrics().get(0);
    }
}
