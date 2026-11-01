package dev.krillin.muninn;

import org.eclipse.tahu.message.model.MetricDataType;

/**
 * A live equipment sample already resolved to a Tahu {@link MetricDataType}
 * (via {@link UaMetricTypes}) at read time, with the OPC-UA metadata the read returned:
 * the raw 32-bit {@code statusCode} and the source and server timestamps in epoch
 * milliseconds ({@code null} when the server returned none). A later task's
 * {@code --inject-bogus} may deliberately set a wrong {@code type} to exercise the egress
 * drop path; it keeps the metadata ({@link #withType}).
 */
public record SampledMember(String name, MetricDataType type, Object value,
        long statusCode, Long sourceTimestamp, Long serverTimestamp) {

    /** The same sample with another type: the value and its metadata are never re-invented. */
    public SampledMember withType(MetricDataType other) {
        return new SampledMember(name, other, value, statusCode, sourceTimestamp, serverTimestamp);
    }
}
