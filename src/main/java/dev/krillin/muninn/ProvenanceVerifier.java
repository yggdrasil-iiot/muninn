package dev.krillin.muninn;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verifies the PROVENANCE PAIR Bifrost publishes for a governed recipe:
 * {@code recipe/<ref>/<version>/recipe-setpoints.yaml} (canonical bytes —
 * the {@code .yaml} name is OPAQUE, the bytes are JSON) plus a sibling
 * {@code manifest.json}. Recomputes SHA-256 over the RAW canonical bytes and
 * refuses (throws {@link ProvenanceException}) on any mismatch, so a tampered
 * file cannot be birthed downstream.
 */
public final class ProvenanceVerifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProvenanceVerifier() {
    }

    public static VerifiedDefinition verify(Path registryDir, String ref, String version)
            throws ProvenanceException {
        Path dir = registryDir.resolve("recipe").resolve(ref).resolve(version);
        Path canonical = dir.resolve("recipe-setpoints.yaml");
        Path manifestFile = dir.resolve("manifest.json");

        if (!Files.isRegularFile(canonical) || !Files.isRegularFile(manifestFile)) {
            throw new ProvenanceException("no published recipe for " + ref + "@" + version);
        }

        final byte[] bytes;
        final MuninnManifest m;
        try {
            // Read the canonical bytes ONCE — these exact bytes are hashed AND returned.
            bytes = Files.readAllBytes(canonical);
            m = MAPPER.readValue(manifestFile.toFile(), MuninnManifest.class);
        } catch (IOException e) {
            throw new ProvenanceException("failed to read provenance pair for " + ref + "@" + version, e);
        }

        String computed = Sha256.hex(bytes);
        if (m.contentSha256() == null || m.contentSha256().isBlank()
                || !computed.equals(m.contentSha256())) {
            throw new ProvenanceException(
                    "provenance mismatch: computed " + computed + " != manifest " + m.contentSha256());
        }

        return new VerifiedDefinition(bytes, m);
    }
}
