package dev.krillin.muninn;

/**
 * Checked failure of the provenance gate: missing pair, malformed manifest, or
 * a content-hash mismatch (tamper). Callers MUST catch this and exit without
 * birthing the unverified definition downstream.
 */
public class ProvenanceException extends Exception {

    public ProvenanceException(String message) {
        super(message);
    }

    public ProvenanceException(String message, Throwable cause) {
        super(message, cause);
    }
}
