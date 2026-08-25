package com.pulseink.client.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.service.knowledge.OriginalDocumentStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalVolumeDocumentStoreTest {

    @TempDir
    Path tempDir;

    private LocalVolumeDocumentStore store() {
        return new LocalVolumeDocumentStore(tempDir);
    }

    private InputStream content(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void savesFileWithServerGeneratedStorageKey() {
        var store = store();

        var saved = store.save("guide.md", "text/markdown", 10_485_760L,
                content("# Title\n\nBody"));

        assertThat(saved.storageKey()).isNotBlank().doesNotContain("guide");
        assertThat(saved.originalFilename()).isEqualTo("guide.md");
        assertThat(saved.declaredMimeType()).isEqualTo("text/markdown");
        assertThat(saved.sizeBytes()).isEqualTo(13L);
        assertThat(saved.checksumSha256()).hasSize(64);
        assertThat(Files.exists(tempDir.resolve(saved.storageKey()))).isTrue();
    }

    @Test
    void sameFilenameTwiceProducesDifferentStorageKeys() {
        var store = store();
        var first = store.save("a.md", "text/markdown", 10_485_760L, content("one"));
        var second = store.save("a.md", "text/markdown", 10_485_760L, content("two"));
        assertThat(first.storageKey()).isNotEqualTo(second.storageKey());
    }

    @Test
    void rejectsPathTraversalFilenames() {
        var store = store();
        assertThatThrownBy(() -> store.save(
                "../evil.md", "text/markdown", 10_485_760L, content("x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.save(
                "a\\b.md", "text/markdown", 10_485_760L, content("x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.save(
                ".", "text/markdown", 10_485_760L, content("x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.save(
                "..", "text/markdown", 10_485_760L, content("x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.save(
                "evil\u0000.md", "text/markdown", 10_485_760L, content("x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.save(
                "", "text/markdown", 10_485_760L, content("x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsupportedExtensions() {
        var store = store();
        assertThatThrownBy(() -> store.save(
                "evil.exe", "application/x-msdownload", 10_485_760L, content("x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.save(
                "noext", "text/plain", 10_485_760L, content("x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOversizedStreamWithoutLeavingTempFiles() throws IOException {
        var store = store();
        var oversized = new InputStream() {
            int remaining = 100;

            @Override
            public int read() {
                if (remaining-- > 0) {
                    return 'x';
                }
                return -1;
            }
        };
        assertThatThrownBy(() -> store.save("big.md", "text/markdown", 50L, oversized))
                .isInstanceOf(IllegalArgumentException.class);
        try (var files = Files.list(tempDir)) {
            assertThat(files.findAny()).isEmpty();
        }
    }

    @Test
    void openAndDeleteRoundTripWithContainmentChecks() throws IOException {
        var store = store();
        var saved = store.save("guide.md", "text/markdown", 10_485_760L,
                content("# Hello"));

        try (var in = store.open(saved.storageKey())) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("# Hello");
        }
        store.delete(saved.storageKey());
        assertThat(Files.exists(tempDir.resolve(saved.storageKey()))).isFalse();
    }

    @Test
    void openRejectsTraversalKeys() {
        var store = store();
        assertThatThrownBy(() -> store.open("../outside"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.delete("/absolute/path"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void storageRootIsCreatedWhenMissing() {
        var nested = tempDir.resolve("a").resolve("b");
        var store = new LocalVolumeDocumentStore(nested);
        store.save("a.md", "text/markdown", 10_485_760L, content("x"));
        assertThat(Files.isDirectory(nested)).isTrue();
    }
}
