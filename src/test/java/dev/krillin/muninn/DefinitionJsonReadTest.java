package dev.krillin.muninn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

class DefinitionJsonReadTest {

    @Test
    void readsGovernedDefinitionFromBytes() {
        // Governed def JSON matching the gate's promoted shape (3 members).
        // Includes a stray "extra" prop to prove unknown props are tolerated.
        String json = "{\n" +
                "  \"templateRef\": \"Line1-Mixer\",\n" +
                "  \"version\": \"1.0.0\",\n" +
                "  \"members\": [\n" +
                "    {\"name\": \"Rpm\", \"type\": \"Double\", \"semanticId\": \"urn:bifrost:sem:Mixer/Rpm\", \"range\": {\"low\": 0.0, \"high\": 3000.0}},\n" +
                "    {\"name\": \"Running\", \"type\": \"Boolean\", \"semanticId\": \"urn:bifrost:sem:Mixer/Running\", \"range\": null},\n" +
                "    {\"name\": \"Secret\", \"type\": \"Double\", \"semanticId\": \"urn:bifrost:sem:Mixer/Secret\", \"range\": null}\n" +
                "  ],\n" +
                "  \"params\": [],\n" +
                "  \"conformsTo\": null,\n" +
                "  \"extra\": 1\n" +
                "}";

        UdtDefinition def = DefinitionJson.fromBytes(json.getBytes(StandardCharsets.UTF_8));

        assertNotNull(def);
        assertEquals("Line1-Mixer", def.templateRef());
        assertEquals("1.0.0", def.version());

        List<Member> members = def.members();
        assertEquals(3, members.size());

        Member rpm = members.get(0);
        assertEquals("Rpm", rpm.name());
        assertEquals("Double", rpm.type());

        Member running = members.get(1);
        assertEquals("Running", running.name());
        assertEquals("Boolean", running.type());
        assertNull(running.range());
    }
}
