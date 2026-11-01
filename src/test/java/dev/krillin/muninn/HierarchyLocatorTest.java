package dev.krillin.muninn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Muninn reads the ISA-95 equipment hierarchy from a mapping release Bifrost actually published.
 * The fixture under {@code bifrost-mappings/} is Bifrost's output, copied byte for byte (head,
 * {@code release.json}, the payload's {@code proposal.json} and {@code hierarchy/0.json}); its
 * hierarchy is Bifrost's own synthetic plant: {@code synthetic-site} with two production lines.
 */
class HierarchyLocatorTest {

    private static final String STREAM = "hierarchy-line";
    private static final String HIERARCHY = "releases/release/1.0.0/payload/hierarchy/0.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    private static Path fixture() throws Exception {
        return Path.of(HierarchyLocatorTest.class.getResource("/bifrost-mappings").toURI());
    }

    /** A writable copy of the fixture, so a test can break one link of the chain. */
    private Path copy() throws Exception {
        Path src = fixture();
        try (Stream<Path> files = Files.walk(src)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                Path dest = tmp.resolve(src.relativize(f).toString());
                if (Files.isDirectory(f)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(f, dest);
                }
            }
        }
        return tmp;
    }

    private static void replace(Path file, String from, String to) throws Exception {
        String s = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(s.contains(from), from + " not in " + file);
        Files.writeString(file, s.replace(from, to), StandardCharsets.UTF_8);
    }

    private static String refuse(Path mappings, String stream, String equipment) {
        return assertThrows(ProvenanceException.class,
                () -> HierarchyLocator.locate(mappings, stream, equipment)).getMessage();
    }

    @Test
    void lineResolvesToRootToNodePath() throws Exception {
        assertEquals("synthetic-site:record-line-a",
                HierarchyLocator.locate(fixture(), STREAM, "record-line-a"));
        assertEquals("synthetic-site:record-line-b",
                HierarchyLocator.locate(fixture(), STREAM, "record-line-b"));
    }

    @Test
    void rootResolvesToItself() throws Exception {
        assertEquals("synthetic-site", HierarchyLocator.locate(fixture(), STREAM, "synthetic-site"));
    }

    /** UMH's Sparkplug B input turns a device ID's colons into the dots of its location path. */
    @Test
    void deviceIdReadsAsUmhLocationPath() throws Exception {
        String device = HierarchyLocator.locate(fixture(), STREAM, "record-line-a");
        assertEquals("spBv1.0/Bifrost:Line1/DDATA/recipe-edge/synthetic-site:record-line-a",
                MqttPublisher.topic("Bifrost:Line1", "DDATA", "recipe-edge", device));
        assertEquals("synthetic-site.record-line-a", device.replace(':', '.'));
    }

    @Test
    void unknownEquipmentRefuses() throws Exception {
        assertTrue(refuse(fixture(), STREAM, "record-line-z")
                .contains("equipment record-line-z is not in the hierarchy"));
    }

    @Test
    void unknownStreamRefuses() throws Exception {
        assertTrue(refuse(fixture(), "no-such-stream", "record-line-a").contains("missing"));
    }

    @Test
    void streamThatLeavesTheRegistryRefuses() throws Exception {
        assertTrue(refuse(fixture(), "../heads/hierarchy-line", "record-line-a")
                .contains("single path segment"));
    }

    /** A head naming {@code ..} would read a release outside {@code releases/}, even a valid one. */
    @Test
    void headThatLeavesReleasesRefuses() throws Exception {
        Path m = copy();
        Path outside = m.resolve("1.0.0");
        Files.createDirectories(outside.resolve("payload/hierarchy"));
        for (String f : new String[] {"release.json", "payload/proposal.json", "payload/hierarchy/0.json"}) {
            Files.copy(m.resolve("releases/release/1.0.0/" + f), outside.resolve(f));
        }
        replace(m.resolve("heads/" + STREAM + ".json"), "\"id\":\"release\"", "\"id\":\"..\"");
        assertTrue(refuse(m, STREAM, "record-line-a").contains("'..' is not a single path segment"));
    }

    @Test
    void tamperedHierarchyRefuses() throws Exception {
        Path m = copy();
        replace(m.resolve(HIERARCHY), "\"Line A\"", "\"Line X\"");
        assertTrue(refuse(m, STREAM, "record-line-a").contains("payload/hierarchy/0.json"));
    }

    @Test
    void tamperedReleaseRefuses() throws Exception {
        Path m = copy();
        replace(m.resolve("releases/release/1.0.0/release.json"), "\"publishedBy\":\"bifrost\"",
                "\"publishedBy\":\"someone\"");
        assertTrue(refuse(m, STREAM, "record-line-a").contains("release.json"));
    }

    @Test
    void tamperedProposalRefuses() throws Exception {
        Path m = copy();
        replace(m.resolve("releases/release/1.0.0/payload/proposal.json"), "\"local-workspace\"",
                "\"other-workspace\"");
        assertTrue(refuse(m, STREAM, "record-line-a").contains("payload/proposal.json"));
    }

    @Test
    void missingHierarchyFileRefuses() throws Exception {
        Path m = copy();
        Files.delete(m.resolve(HIERARCHY));
        assertTrue(refuse(m, STREAM, "record-line-a").contains("missing"));
    }

    /**
     * Rewrites one payload file and re-seals the chain above it (its digest in {@code release.json},
     * then the head's digest of {@code release.json}) — a consistent release whose content is wrong.
     */
    private static void reseal(Path m, String payloadRel, String from, String to) throws Exception {
        Path file = m.resolve("releases/release/1.0.0/payload/" + payloadRel);
        String before = Sha256.hex(Files.readAllBytes(file));
        replace(file, from, to);
        Path release = m.resolve("releases/release/1.0.0/release.json");
        String releaseBefore = Sha256.hex(Files.readAllBytes(release));
        replace(release, before, Sha256.hex(Files.readAllBytes(file)));
        replace(m.resolve("heads/" + STREAM + ".json"), releaseBefore,
                Sha256.hex(Files.readAllBytes(release)));
    }

    @Test
    void resealedChainStillResolves() throws Exception {
        Path m = copy();
        reseal(m, "hierarchy/0.json", "\"Line A\"", "\"Line X\"");
        assertEquals("synthetic-site:record-line-a", HierarchyLocator.locate(m, STREAM, "record-line-a"));
    }

    @Test
    void releaseWithoutHierarchyRefuses() throws Exception {
        Path m = copy();
        reseal(m, "proposal.json", "\"kind\": \"equipment-hierarchy\"", "\"kind\": \"evidence-artifact\"");
        assertTrue(refuse(m, STREAM, "record-line-a").contains("0 equipment hierarchies"));
    }

    @Test
    void releaseWithSeveralHierarchiesRefuses() throws Exception {
        Path m = copy();
        // Every evidence entry relabelled: 1 + 6 hierarchy entries, none of them chosen.
        reseal(m, "proposal.json", "\"kind\": \"evidence-artifact\"", "\"kind\": \"equipment-hierarchy\"");
        assertTrue(refuse(m, STREAM, "record-line-a").contains("7 equipment hierarchies"));
    }

    @Test
    void otherSchemaVersionRefuses() throws Exception {
        Path m = copy();
        reseal(m, "hierarchy/0.json", "\"schemaVersion\": \"2.23.0\"", "\"schemaVersion\": \"2.24.0\"");
        assertTrue(refuse(m, STREAM, "record-line-a").contains("not an equipment-hierarchy 2.23.0"));
    }

    @Test
    void documentOfAnotherKindRefuses() throws Exception {
        Path m = copy();
        reseal(m, "hierarchy/0.json", "\"kind\": \"equipment-hierarchy\"", "\"kind\": \"evidence-artifact\"");
        assertTrue(refuse(m, STREAM, "record-line-a").contains("not an equipment-hierarchy 2.23.0"));
    }

    @Test
    void hierarchyNotListedInReleaseRefuses() throws Exception {
        Path m = copy();
        Path release = m.resolve("releases/release/1.0.0/release.json");
        String before = Sha256.hex(Files.readAllBytes(release));
        replace(release, "\"path\":\"payload/hierarchy/0.json\"", "\"path\":\"payload/hierarchy/9.json\"");
        replace(m.resolve("heads/" + STREAM + ".json"), before, Sha256.hex(Files.readAllBytes(release)));
        assertTrue(refuse(m, STREAM, "record-line-a").contains("does not list payload/hierarchy/0.json"));
    }

    @Test
    void headWithoutDigestRefuses() throws Exception {
        Path m = copy();
        replace(m.resolve("heads/" + STREAM + ".json"), "\"sha256\"", "\"digest\"");
        assertTrue(refuse(m, STREAM, "record-line-a").contains("missing field sha256"));
    }

    private static JsonNode hierarchy(String nodes) throws Exception {
        return MAPPER.readTree("{\"kind\":\"equipment-hierarchy\",\"schemaVersion\":\"2.23.0\",\"nodes\":["
                + nodes + "]}");
    }

    private static String node(String id, String parent) {
        return "{\"equipmentInstanceId\":\"" + id + "\",\"parentEquipmentInstanceId\":"
                + (parent == null ? "null" : "\"" + parent + "\"") + "}";
    }

    @Test
    void missingParentRefuses() throws Exception {
        JsonNode h = hierarchy(node("line", "site"));
        assertTrue(assertThrows(ProvenanceException.class, () -> HierarchyLocator.path(h, "line"))
                .getMessage().contains("parent site"));
    }

    @Test
    void cycleRefuses() throws Exception {
        JsonNode h = hierarchy(node("a", "b") + "," + node("b", "a"));
        assertTrue(assertThrows(ProvenanceException.class, () -> HierarchyLocator.path(h, "a"))
                .getMessage().contains("cycle"));
    }

    @Test
    void duplicateNodeRefuses() throws Exception {
        JsonNode h = hierarchy(node("a", null) + "," + node("a", null));
        assertTrue(assertThrows(ProvenanceException.class, () -> HierarchyLocator.path(h, "a"))
                .getMessage().contains("duplicate"));
    }

    @Test
    void depthOverSixtyFourRefuses() throws Exception {
        StringBuilder nodes = new StringBuilder(node("n0", null));
        for (int i = 1; i <= HierarchyLocator.MAX_DEPTH; i++) {
            nodes.append(',').append(node("n" + i, "n" + (i - 1)));
        }
        JsonNode h = hierarchy(nodes.toString());
        assertEquals(HierarchyLocator.MAX_DEPTH - 1,
                HierarchyLocator.path(h, "n" + (HierarchyLocator.MAX_DEPTH - 1)).chars()
                        .filter(c -> c == ':').count());
        assertTrue(assertThrows(ProvenanceException.class,
                () -> HierarchyLocator.path(h, "n" + HierarchyLocator.MAX_DEPTH))
                .getMessage().contains("deeper"));
    }

    /** The contract's ID pattern allows '/' and '.'; neither can stand as one location level. */
    @Test
    void idThatCannotBeALevelRefuses() throws Exception {
        for (String bad : new String[] {"site/a", "site.a"}) {
            JsonNode h = hierarchy(node(bad, null) + "," + node("line", bad));
            assertTrue(assertThrows(ProvenanceException.class, () -> HierarchyLocator.path(h, "line"))
                    .getMessage().contains("cannot be a location level"), bad);
        }
    }
}
