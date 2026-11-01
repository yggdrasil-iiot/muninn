package dev.krillin.muninn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves an equipment instance to its ISA-95 location path from the equipment hierarchy
 * (contract 2.23.0) Bifrost publishes in a mapping release. Muninn's own reader — no Bifrost code.
 *
 * <p>Chain, each link checked by raw-byte SHA-256 the way any consumer outside Bifrost verifies a
 * release: {@code heads/<stream>.json} names {@code releases/<id>/<version>/release.json} and its
 * digest; {@code release.json} lists every payload file with its digest; the payload's
 * {@code proposal.json} names the one {@code equipment-hierarchy} file. Any break refuses.
 *
 * <p>The path is the {@code equipmentInstanceId}s from the root down to the instance, joined by
 * {@code ':'} — the Sparkplug {@code device_id} form UMH's Sparkplug B input reads as a location
 * path (Modified Parris Method: {@code a:b:c} becomes {@code a.b.c}). An ID that contains
 * {@code '/'} cannot be a Sparkplug topic token and one that contains {@code '.'} would be read as
 * an extra level, so either refuses rather than being rewritten.
 */
public final class HierarchyLocator {

    static final String KIND = "equipment-hierarchy";
    static final String SCHEMA_VERSION = "2.23.0";
    static final int MAX_DEPTH = 64;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HierarchyLocator() {
    }

    /** Location path of {@code equipmentInstanceId} in the hierarchy published on {@code stream}. */
    public static String locate(Path mappingsDir, String stream, String equipmentInstanceId)
            throws ProvenanceException {
        segment(stream);
        JsonNode head = json(read(mappingsDir.resolve("heads").resolve(stream + ".json")), "head " + stream);
        String id = segment(text(head, "id"));
        String version = segment(text(head, "version"));
        Path releaseDir = mappingsDir.resolve("releases").resolve(id).resolve(version);

        byte[] releaseBytes = read(releaseDir.resolve("release.json"));
        verify(releaseBytes, text(head, "sha256"), "release.json of " + id + "@" + version);
        JsonNode release = json(releaseBytes, "release.json");

        Map<String, String> files = new HashMap<>();
        for (JsonNode f : release.path("files")) {
            files.put(text(f, "path"), text(f, "sha256"));
        }

        JsonNode proposal = json(payload(releaseDir, files, "proposal.json"), "proposal.json");
        List<JsonNode> hierarchies = new ArrayList<>();
        for (JsonNode e : proposal.path("files")) {
            if (KIND.equals(e.path("kind").asText())) {
                hierarchies.add(e);
            }
        }
        if (hierarchies.size() != 1) {
            throw new ProvenanceException("release " + id + "@" + version + " has "
                    + hierarchies.size() + " equipment hierarchies, expected 1");
        }
        JsonNode hierarchy = json(payload(releaseDir, files, text(hierarchies.get(0), "path")), KIND);
        if (!KIND.equals(hierarchy.path("kind").asText())
                || !SCHEMA_VERSION.equals(hierarchy.path("schemaVersion").asText())) {
            throw new ProvenanceException("not an equipment-hierarchy " + SCHEMA_VERSION + " document");
        }
        return path(hierarchy, equipmentInstanceId);
    }

    /** Root-to-node IDs joined by {@code ':'}; refuses unknown nodes, broken parents, cycles, bad IDs. */
    static String path(JsonNode hierarchy, String equipmentInstanceId) throws ProvenanceException {
        Map<String, JsonNode> byId = new HashMap<>();
        for (JsonNode n : hierarchy.path("nodes")) {
            if (byId.putIfAbsent(n.path("equipmentInstanceId").asText(), n) != null) {
                throw new ProvenanceException("duplicate equipment node " + n.path("equipmentInstanceId").asText());
            }
        }
        if (!byId.containsKey(equipmentInstanceId)) {
            throw new ProvenanceException("equipment " + equipmentInstanceId + " is not in the hierarchy");
        }
        Deque<String> ids = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        String current = equipmentInstanceId;
        while (current != null) {
            JsonNode node = byId.get(current);
            if (node == null) {
                throw new ProvenanceException("parent " + current + " is not in the hierarchy");
            }
            if (!seen.add(current)) {
                throw new ProvenanceException("cycle in the hierarchy at " + current);
            }
            if (seen.size() > MAX_DEPTH) {
                throw new ProvenanceException("hierarchy deeper than " + MAX_DEPTH);
            }
            if (current.isEmpty() || current.contains("/") || current.contains(".")
                    || current.contains(":") || current.contains("+") || current.contains("#")) {
                throw new ProvenanceException("equipment id '" + current + "' cannot be a location level");
            }
            ids.addFirst(current);
            JsonNode parent = node.path("parentEquipmentInstanceId");
            current = parent.isNull() || parent.isMissingNode() ? null : parent.asText();
        }
        return String.join(":", ids);
    }

    private static byte[] payload(Path releaseDir, Map<String, String> files, String rel)
            throws ProvenanceException {
        String listed = files.get("payload/" + rel);
        if (listed == null) {
            throw new ProvenanceException("release.json does not list payload/" + rel);
        }
        byte[] bytes = read(releaseDir.resolve("payload").resolve(rel));
        verify(bytes, listed, "payload/" + rel);
        return bytes;
    }

    /** A head names one directory level; anything that could leave {@code mappingsDir} refuses. */
    private static String segment(String s) throws ProvenanceException {
        if (s.isEmpty() || s.contains("/") || s.contains("\\") || s.contains("..")) {
            throw new ProvenanceException("'" + s + "' is not a single path segment");
        }
        return s;
    }

    private static void verify(byte[] bytes, String expected, String what) throws ProvenanceException {
        String computed = Sha256.hex(bytes);
        if (!computed.equals(expected)) {
            throw new ProvenanceException("provenance mismatch on " + what + ": computed " + computed
                    + " != listed " + expected);
        }
    }

    private static byte[] read(Path file) throws ProvenanceException {
        if (!Files.isRegularFile(file)) {
            throw new ProvenanceException("missing " + file);
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new ProvenanceException("failed to read " + file, e);
        }
    }

    private static JsonNode json(byte[] bytes, String what) throws ProvenanceException {
        try {
            return MAPPER.readTree(bytes);
        } catch (IOException e) {
            throw new ProvenanceException("malformed " + what, e);
        }
    }

    private static String text(JsonNode node, String field) throws ProvenanceException {
        JsonNode v = node.path(field);
        if (!v.isTextual() || v.asText().isEmpty()) {
            throw new ProvenanceException("missing field " + field);
        }
        return v.asText();
    }
}
