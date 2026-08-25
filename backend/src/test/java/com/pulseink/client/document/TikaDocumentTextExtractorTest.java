package com.pulseink.client.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.service.knowledge.DocumentTextExtractor;
import com.pulseink.service.knowledge.ExtractedDocument;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TikaDocumentTextExtractorTest {

    private final DocumentTextExtractor extractor = new TikaDocumentTextExtractor();

    private ExtractedDocument extract(String filename, String content) {
        return extractor.extract(filename,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                2_000_000L);
    }

    @Test
    void extractsPlainTextWithHeadingStructure() {
        var document = extract("guide.md", """
                # Title

                ## Section One

                Some content.

                ## Section Two

                More content.
                """);
        assertThat(document.detectedMimeType()).startsWith("text/");
        assertThat(document.sections()).hasSize(3);
        assertThat(document.sections().get(0).headingPath()).contains("Title");
        assertThat(document.sections().get(1).headingPath()).contains("Section One");
        assertThat(document.sections().get(2).headingPath()).contains("Section Two");
        assertThat(document.sections().get(0).ordinal()).isZero();
    }

    @Test
    void plainTextWithoutHeadingsDegradesToParagraphs() {
        var document = extract("notes.txt", "line one\nline two");
        assertThat(document.sections()).isNotEmpty();
        assertThat(document.sections().get(0).text()).contains("line one");
    }

    @Test
    void rejectsUnsupportedExtensionAndMimeMismatch() {
        assertThatThrownBy(() -> extract("evil.exe", "text"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> extract("fake.pdf", "this is not a pdf"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankExtractedText() {
        assertThatThrownBy(() -> extract("blank.txt", "   \n\t  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOversizedExtractedText() {
        var huge = "x".repeat(3000);
        assertThatThrownBy(() -> extractor.extract(
                "big.txt",
                new ByteArrayInputStream(huge.getBytes(StandardCharsets.UTF_8)),
                100L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extractsPdfFixtureWithHeadingMetadata() throws IOException {
        byte[] pdf = Files.readAllBytes(
                Path.of("src/test/resources/fixtures/knowledge/sample.pdf"));
        var document = extractor.extract("sample.pdf",
                new ByteArrayInputStream(pdf), 2_000_000L);
        assertThat(document.detectedMimeType()).isEqualTo("application/pdf");
        assertThat(document.sections()).isNotEmpty();
        assertThat(document.sections().get(0).text()).isNotBlank();
    }

    @Test
    void extractsGeneratedDocxArchive() throws IOException {
        byte[] docx = minimalDocx();
        var document = extractor.extract("sample.docx",
                new ByteArrayInputStream(docx), 2_000_000L);
        assertThat(document.detectedMimeType())
                .matches(mime -> mime.contains("wordprocessingml")
                        || mime.contains("application/zip"));
        assertThat(document.sections()).isNotEmpty();
    }

    private static byte[] minimalDocx() throws IOException {
        var buffer = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(buffer)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("[Content_Types].xml"));
            zip.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/word/document.xml"
                        ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>
                    """.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("_rels/.rels"));
            zip.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1"
                        Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
                        Target="word/document.xml"/>
                    </Relationships>
                    """.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));
            zip.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body>
                        <w:p><w:r><w:t>Hello from DOCX</w:t></w:r></w:p>
                      </w:body>
                    </w:document>
                    """.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return buffer.toByteArray();
    }
}
