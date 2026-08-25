package com.pulseink.service.knowledge;

import java.io.InputStream;

/**
 * Extracts structured text from an original document without recursive embedded attachment
 * extraction. Failures are stable business errors, never provider stack traces.
 */
public interface DocumentTextExtractor {

    ExtractedDocument extract(String originalFilename, InputStream content,
                              long maxExtractedCharacters);
}
