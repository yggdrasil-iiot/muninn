package dev.krillin.muninn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.eclipse.tahu.message.model.MetricDataType;
import org.junit.jupiter.api.Test;

/**
 * Vocabulary lock (CF-A/V): the governed def carries type literals that are
 * exactly {@link MetricDataType#toString()} values. Muninn's OPC-UA-id → Tahu
 * type map MUST produce constants whose {@code .toString()} matches those exact,
 * case-sensitive strings — otherwise a legit sample would be wrongly dropped.
 * If a future Tahu quirk changed the literal, these assertions go RED and catch it.
 */
class UaMetricTypesTest {

    @Test
    void mapsDoubleIdToTheDoubleLiteral() {
        assertEquals("Double", UaMetricTypes.of(11).toString());
    }

    @Test
    void mapsBooleanIdToTheBooleanLiteral() {
        assertEquals("Boolean", UaMetricTypes.of(1).toString());
    }

    @Test
    void throwsOnUnmappedId() {
        assertThrows(IllegalArgumentException.class, () -> UaMetricTypes.of(999));
    }
}
