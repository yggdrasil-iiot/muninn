package dev.krillin.muninn;

import org.eclipse.tahu.message.model.MetricDataType;

/**
 * A live equipment sample already resolved to a Tahu {@link MetricDataType}
 * (via {@link UaMetricTypes}) at read time. A later task's {@code --inject-bogus}
 * may deliberately set a wrong {@code type} to exercise the egress drop path.
 */
public record SampledMember(String name, MetricDataType type, Object value) {
}
