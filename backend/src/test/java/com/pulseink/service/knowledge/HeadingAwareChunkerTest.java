package com.pulseink.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class HeadingAwareChunkerTest {

    private HeadingAwareChunker chunker(int maxSize, int overlap, int maxChunks) {
        return new HeadingAwareChunker(maxSize, overlap, maxChunks);
    }

    private ExtractedDocument document(String title, List<ExtractedSection> sections) {
        return new ExtractedDocument(title, "text/markdown", sections);
    }

    @Test
    void splitsLongParagraphsAtHardBoundary() {
        var chunker = chunker(10, 0, 100);
        var text = "abcdefghijklmnopqrstuvwxyz";
        var chunks = chunker.chunk(document("t", List.of(new ExtractedSection("H", text, 0))));
        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.get(0).text()).hasSizeLessThanOrEqualTo(10);
        assertThat(chunks.get(0).headingPath()).isEqualTo("H");
        assertThat(chunks.get(0).ordinal()).isZero();
        assertThat(chunks.get(1).ordinal()).isEqualTo(1);
    }

    @Test
    void neverSplitsSurrogatePairs() {
        var chunker = chunker(10, 0, 100);
        var emoji = "\uD83D\uDE00"; // U+1F600
        var text = ("abc" + emoji + "defghij" + emoji + "klmnop");
        var chunks = chunker.chunk(document("t", List.of(new ExtractedSection("H", text, 0))));
        for (var chunk : chunks) {
            String value = chunk.text();
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    assertThat(i + 1 < value.length()
                            && Character.isLowSurrogate(value.charAt(i + 1)))
                            .as("high surrogate must be followed by low surrogate")
                            .isTrue();
                    i++;
                } else {
                    assertThat(Character.isLowSurrogate(c)).isFalse();
                }
            }
        }
    }

    @Test
    void overlapIsHonouredBetweenChunks() {
        var chunker = chunker(20, 5, 100);
        var text = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        var chunks = chunker.chunk(document("t", List.of(new ExtractedSection("H", text, 0))));
        assertThat(chunks.size()).isGreaterThan(1);
        for (int i = 1; i < chunks.size(); i++) {
            var previousTail = chunks.get(i - 1).text();
            var current = chunks.get(i).text();
            assertThat(current).contains(
                    previousTail.substring(Math.max(0, previousTail.length() - 5)));
        }
    }

    @Test
    void sameInputProducesIdenticalOutput() {
        var chunker = chunker(30, 5, 100);
        var doc = document("t", List.of(
                new ExtractedSection("H1", "Some content here for chunking tests. ", 0),
                new ExtractedSection("H2", "More content in the second section. ", 1)));
        var first = chunker.chunk(doc);
        var second = chunker.chunk(doc);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void rejectsInvalidParametersAndEmptyDocument() {
        assertThatThrownBy(() -> chunker(0, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chunker(10, 10, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chunker(10, 11, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chunker(10, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chunker(10, 0, 100)
                .chunk(document("t", List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exceedsMaxChunksFailsClosed() {
        var chunker = chunker(5, 0, 2);
        var text = "abcdefghijklmnopqrstuvwxyz";
        assertThatThrownBy(() -> chunker.chunk(
                document("t", List.of(new ExtractedSection("H", text, 0)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tracksCodePointRanges() {
        var chunker = chunker(10, 0, 100);
        var chunks = chunker.chunk(document("t",
                List.of(new ExtractedSection("H", "abcdefghijklmnopqrst", 0))));
        assertThat(chunks.get(0).endCodePoint()).isEqualTo(chunks.get(0).startCodePoint()
                + chunker.countCodePoints(chunks.get(0).text()));
        assertThat(chunks.get(1).startCodePoint()).isEqualTo(chunks.get(0).endCodePoint());
    }

    @Test
    void sectionsKeepHeadingHierarchy() {
        var chunker = chunker(100, 0, 100);
        var doc = document("Root", List.of(
                new ExtractedSection("Root > A", "aaa", 0),
                new ExtractedSection("Root > B", "bbb", 1)));
        var chunks = chunker.chunk(doc);
        assertThat(chunks).extracting(KnowledgeChunk::headingPath)
                .containsExactly("Root > A", "Root > B");
    }
}
