package com.pulseink.service.knowledge;

import java.io.InputStream;

/**
 * Stores original knowledge files on the local volume under server-generated keys. Callers can
 * never supply a path; the storage key is normalized and containment-checked on every access.
 */
public interface OriginalDocumentStore {

    StoredDocument save(String originalFilename, String declaredMimeType,
                        long maxBytes, InputStream content);

    InputStream open(String storageKey);

    void delete(String storageKey);
}
