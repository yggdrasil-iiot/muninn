package dev.krillin.muninn;

/**
 * The outcome of a successful provenance check: the EXACT canonical bytes that
 * were hashed (not a re-read — no TOCTOU) alongside the verified manifest.
 */
public record VerifiedDefinition(byte[] bytes, MuninnManifest manifest) {
}
