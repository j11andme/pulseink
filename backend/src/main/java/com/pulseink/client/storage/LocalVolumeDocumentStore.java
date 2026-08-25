package com.pulseink.client.storage;

import com.pulseink.service.knowledge.OriginalDocumentStore;
import com.pulseink.service.knowledge.StoredDocument;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Local-volume original file store. Files live under a configured root using server-generated
 * UUID storage keys; callers can never supply paths. Writes are temp-file + atomic move, reads
 * and deletes re-normalize and containment-check the key.
 */
public final class LocalVolumeDocumentStore implements OriginalDocumentStore {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "md", "txt");

    private final Path root;

    public LocalVolumeDocumentStore(Path root) {
        this.root = Objects.requireNonNull(root, "storage root must not be null")
                .toAbsolutePath()
                .normalize();
    }

    @Override
    public StoredDocument save(String originalFilename, String declaredMimeType,
                               long maxBytes, InputStream content) {
        Objects.requireNonNull(content, "content must not be null");
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        if (declaredMimeType == null || declaredMimeType.isBlank()) {
            throw new IllegalArgumentException("declaredMimeType must not be blank");
        }
        String extension = validateFilename(originalFilename);
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new IllegalStateException("knowledge storage root is not writable", ex);
        }

        String storageKey = UUID.randomUUID() + "." + extension;
        Path target = containedPath(storageKey);
        Path temp;
        try {
            temp = Files.createTempFile(root, "upload-", ".tmp");
        } catch (IOException ex) {
            throw new IllegalStateException("knowledge storage is not writable", ex);
        }

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }

        long total = 0;
        try (InputStream in = content; OutputStream out = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IllegalArgumentException("file exceeds maximum size");
                }
                out.write(buffer, 0, read);
                digest.update(buffer, 0, read);
            }
            if (total == 0) {
                throw new IllegalArgumentException("file is empty");
            }
        } catch (IllegalArgumentException ex) {
            deleteQuietly(temp);
            throw ex;
        } catch (IOException ex) {
            deleteQuietly(temp);
            throw new IllegalStateException("knowledge file write failed", ex);
        }

        try {
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            deleteQuietly(temp);
            throw new IllegalStateException("knowledge file finalize failed", ex);
        }

        return new StoredDocument(
                storageKey,
                originalFilename,
                declaredMimeType,
                total,
                HexFormat.of().formatHex(digest.digest()));
    }

    @Override
    public InputStream open(String storageKey) {
        try {
            return Files.newInputStream(containedPath(storageKey));
        } catch (IOException ex) {
            throw new IllegalArgumentException("knowledge file could not be opened", ex);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(containedPath(storageKey));
        } catch (IOException ex) {
            throw new IllegalStateException("knowledge file delete failed", ex);
        }
    }

    private String validateFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("original filename must not be blank");
        }
        if (filename.indexOf('\u0000') >= 0
                || filename.indexOf('/') >= 0
                || filename.indexOf('\\') >= 0
                || ".".equals(filename)
                || "..".equals(filename)) {
            throw new IllegalArgumentException("original filename is not allowed");
        }
        var normalized = Path.of(filename).normalize().toString();
        if (normalized.isEmpty() || ".".equals(normalized) || "..".equals(normalized)) {
            throw new IllegalArgumentException("original filename is not allowed");
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            throw new IllegalArgumentException("file extension is missing");
        }
        String extension = filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("file type is not supported");
        }
        return extension;
    }

    private Path containedPath(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("storage key must not be blank");
        }
        Path resolved = root.resolve(storageKey).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IllegalArgumentException("storage key escapes the knowledge root");
        }
        return resolved;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // cleanup best effort
        }
    }
}
