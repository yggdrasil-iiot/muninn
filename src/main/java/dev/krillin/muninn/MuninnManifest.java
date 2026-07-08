package dev.krillin.muninn;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Muninn's independent view of Bifrost's provenance manifest.json. Bifrost
 * writes 7 fields; muninn re-parses only the 4 it governs on and drops the
 * rest via {@code @JsonIgnoreProperties} (zero shared code with the producer).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MuninnManifest(String ref, String version, String defRef, String contentSha256) {
}
