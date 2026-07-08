package dev.krillin.muninn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Muninn provenance-verifies the governed definition Bifrost publishes as a
 * PROVENANCE PAIR (canonical bytes + sibling manifest.json). It recomputes
 * SHA-256 over the RAW bytes and refuses (throws) on any mismatch — a tampered
 * file cannot be birthed downstream.
 */
class ProvenanceVerifierTest {

    private static final String DEF_JSON = "{\n" +
            "  \"templateRef\": \"Line1-Mixer\",\n" +
            "  \"version\": \"1.0.0\",\n" +
            "  \"members\": [\n" +
            "    {\"name\": \"Rpm\", \"type\": \"Double\", \"semanticId\": \"urn:bifrost:sem:Mixer/Rpm\", \"range\": {\"low\": 0.0, \"high\": 3000.0}}\n" +
            "  ],\n" +
            "  \"params\": [],\n" +
            "  \"conformsTo\": null\n" +
            "}";

    /** Independent sha256 over raw bytes — same algo as production, computed here in the test. */
    private static String sha256Hex(byte[] bytes) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder s = new StringBuilder();
        for (byte x : d) {
            s.append(String.format("%02x", x));
        }
        return s.toString();
    }

    private static Path publishPair(Path registryDir, byte[] defBytes, String contentSha256) throws Exception {
        Path dir = registryDir.resolve("recipe").resolve("Line1-Mixer").resolve("1.0.0");
        Files.createDirectories(dir);
        Path canonical = dir.resolve("recipe-setpoints.yaml");
        Files.write(canonical, defBytes);
        // Sibling manifest with all 7 bifrost fields — muninn's 4-field record must drop the 3 extras.
        String manifest = "{\n" +
                "  \"kind\": \"recipe-setpoints\",\n" +
                "  \"ref\": \"Line1-Mixer\",\n" +
                "  \"version\": \"1.0.0\",\n" +
                "  \"defRef\": \"0123456789abcdef0123456789abcdef01234567\",\n" +
                "  \"contentSha256\": \"" + contentSha256 + "\",\n" +
                "  \"sourcePath\": \"Line1-Mixer-1.0.0.json\",\n" +
                "  \"publishedAt\": 123\n" +
                "}";
        Files.write(dir.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8));
        return canonical;
    }

    @Test
    void verifiesConformantPairAndReturnsHashedBytes(@TempDir Path registryDir) throws Exception {
        byte[] defBytes = DEF_JSON.getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(defBytes);
        publishPair(registryDir, defBytes, sha);

        VerifiedDefinition v = ProvenanceVerifier.verify(registryDir, "Line1-Mixer", "1.0.0");

        assertNotNull(v);
        // Returned bytes are exactly the bytes that were hashed (no re-read / TOCTOU).
        assertArrayEquals(defBytes, v.bytes());
        assertEquals(sha, v.manifest().contentSha256());
        // The 3 extra bifrost fields were dropped by @JsonIgnoreProperties.
        assertEquals("Line1-Mixer", v.manifest().ref());
        assertEquals("1.0.0", v.manifest().version());
        assertEquals("0123456789abcdef0123456789abcdef01234567", v.manifest().defRef());
    }

    @Test
    void refusesOnTamperedCanonicalBytes(@TempDir Path registryDir) throws Exception {
        byte[] defBytes = DEF_JSON.getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(defBytes);
        Path canonical = publishPair(registryDir, defBytes, sha);

        // Tamper: append one byte AFTER the manifest was written with the original sha.
        Files.write(canonical, new byte[]{'X'}, StandardOpenOption.APPEND);

        assertThrows(ProvenanceException.class,
                () -> ProvenanceVerifier.verify(registryDir, "Line1-Mixer", "1.0.0"));
    }

    @Test
    void refusesWhenManifestMissing(@TempDir Path registryDir) throws Exception {
        byte[] defBytes = DEF_JSON.getBytes(StandardCharsets.UTF_8);
        Path dir = registryDir.resolve("recipe").resolve("Line1-Mixer").resolve("1.0.0");
        Files.createDirectories(dir);
        Files.write(dir.resolve("recipe-setpoints.yaml"), defBytes);
        // No manifest.json.

        assertThrows(ProvenanceException.class,
                () -> ProvenanceVerifier.verify(registryDir, "Line1-Mixer", "1.0.0"));
    }

    @Test
    void refusesWhenRecipeFileMissing(@TempDir Path registryDir) throws Exception {
        byte[] defBytes = DEF_JSON.getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(defBytes);
        Path dir = registryDir.resolve("recipe").resolve("Line1-Mixer").resolve("1.0.0");
        Files.createDirectories(dir);
        // Only a manifest, no canonical recipe file.
        String manifest = "{\"ref\":\"Line1-Mixer\",\"version\":\"1.0.0\","
                + "\"defRef\":\"0123456789abcdef0123456789abcdef01234567\",\"contentSha256\":\"" + sha + "\"}";
        Files.write(dir.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8));

        assertThrows(ProvenanceException.class,
                () -> ProvenanceVerifier.verify(registryDir, "Line1-Mixer", "1.0.0"));
    }
}
