package dev.krillin.muninn;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Lenient reader for the governed UDT definition JSON that Bifrost promotes.
 * Muninn owns its own AAS-shaped value types and re-parses the published
 * contract independently (zero shared code with the producer).
 */
public final class DefinitionJson {

    private DefinitionJson() {
    }

    public static UdtDefinition fromBytes(byte[] bytes) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            return mapper.readValue(bytes, UdtDefinition.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse governed definition JSON", e);
        }
    }
}
