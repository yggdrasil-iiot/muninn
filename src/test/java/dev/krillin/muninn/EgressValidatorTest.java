package dev.krillin.muninn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.tahu.message.model.MetricDataType;
import org.junit.jupiter.api.Test;

/**
 * Egress governance: a sample is birthed only if the governed def has a member
 * matching by NAME and TYPE. Type match compares the def's String literal (e.g.
 * "Double") against the Tahu constant's {@code .toString()} — case-sensitive.
 */
class EgressValidatorTest {

    private static final UdtDefinition MIXER = new UdtDefinition(
            "Line1-Mixer", "1.0.0",
            List.of(
                    new Member("Rpm", "Double", null, null),
                    new Member("Running", "Boolean", null, null),
                    new Member("Secret", "Double", null, null)),
            List.of(), null);

    @Test
    void conformantSampleIsAdmitted() {
        assertTrue(EgressValidator.conformant(
                new SampledMember("Rpm", MetricDataType.Double, 1535.0), MIXER));
    }

    @Test
    void unknownMemberIsDropped() {
        assertFalse(EgressValidator.conformant(
                new SampledMember("Ghost", MetricDataType.Double, 0.0), MIXER));
    }

    @Test
    void typeMismatchIsDropped() {
        // The --inject-bogus shape: right name, wrong type.
        assertFalse(EgressValidator.conformant(
                new SampledMember("Secret", MetricDataType.String, "x"), MIXER));
    }
}
