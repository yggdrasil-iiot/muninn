package dev.krillin.muninn;

/**
 * Egress governance: a sample is conformant (birthable) iff the governed def
 * has a member matching by NAME and TYPE. Type match compares the def's String
 * literal (e.g. "Double") against the Tahu constant's {@code .toString()} —
 * case-sensitive string equality. Non-conformant samples are dropped.
 */
public final class EgressValidator {

    private EgressValidator() {
    }

    public static boolean conformant(SampledMember s, UdtDefinition def) {
        for (Member m : def.members()) {
            if (m.name().equals(s.name()) && m.type().equals(s.type().toString())) {
                return true;
            }
        }
        return false;
    }
}
