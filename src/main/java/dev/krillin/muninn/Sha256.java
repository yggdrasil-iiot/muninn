package dev.krillin.muninn;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 over RAW bytes, rendered lowercase zero-padded 64-char hex.
 * Byte-identical to Bifrost's / resequence-twin-lab's algo — hash the raw
 * bytes as read, NEVER a String (no charset / CRLF transform).
 */
public final class Sha256 {

    private Sha256() {
    }

    public static String hex(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder s = new StringBuilder();
            for (byte x : d) {
                s.append(String.format("%02x", x));
            }
            return s.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandated JCA algorithm; absence is unrecoverable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
