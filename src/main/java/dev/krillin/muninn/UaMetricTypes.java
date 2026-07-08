package dev.krillin.muninn;

import java.util.Map;

import org.eclipse.tahu.message.model.MetricDataType;

/**
 * Muninn's OWN OPC-UA DataType numeric id → Tahu {@link MetricDataType} map.
 * The resulting constant's {@code .toString()} is the vocabulary the governed
 * def carries (CF-A/V lock). Never guesses: an unmapped id throws.
 */
public final class UaMetricTypes {

    private static final Map<Integer, MetricDataType> BY_ID = Map.of(
            1, MetricDataType.Boolean,
            6, MetricDataType.Int32,
            7, MetricDataType.UInt32,
            8, MetricDataType.Int64,
            10, MetricDataType.Float,
            11, MetricDataType.Double,
            12, MetricDataType.String,
            13, MetricDataType.DateTime);

    private UaMetricTypes() {
    }

    public static MetricDataType of(int opcuaId) {
        MetricDataType type = BY_ID.get(opcuaId);
        if (type == null) {
            throw new IllegalArgumentException("Unmapped OPC-UA data type id: " + opcuaId);
        }
        return type;
    }
}
