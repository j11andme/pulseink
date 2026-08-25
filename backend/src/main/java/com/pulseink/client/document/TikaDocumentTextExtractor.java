package com.pulseink.client.document;

import com.pulseink.service.knowledge.DocumentTextExtractor;
import com.pulseink.service.knowledge.ExtractedDocument;
import com.pulseink.service.knowledge.ExtractedSection;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

/**
 * Tika-based text extractor. Embedded attachments are never extracted recursively; unsupported
 * extensions, MIME/extension mismatches, protected/corrupt documents, blank text and oversized
 * text all fail with stable business errors.
 */
public final class TikaDocumentTextExtractor implements DocumentTextExtractor {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "md", "txt");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");

    private final Parser parser = new AutoDetectParser();

    @Override
    public ExtractedDocument extract(String originalFilename, InputStream content,
                                     long maxExtractedCharacters) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("original filename must not be blank");
        }
        if (maxExtractedCharacters <= 0) {
            throw new IllegalArgumentException("maxExtractedCharacters must be positive");
        }
        String extension = extensionOf(originalFilename);

        var metadata = new Metadata();
        var context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                return false;
            }

            @Override
            public void parseEmbedded(InputStream stream, ContentHandler handler,
                                      Metadata metadata, boolean isEmbedded) {
                // never recurse into embedded attachments
            }
        });
        var handler = new BodyContentHandler(safeInt(maxExtractedCharacters));
        String text;
        try {
            parser.parse(content, handler, metadata, context);
            text = handler.toString();
        } catch (WriteLimitReachedException ex) {
            throw new IllegalArgumentException("extracted text exceeds the character limit");
        } catch (EncryptedDocumentException ex) {
            throw new IllegalArgumentException("document is protected or corrupt");
        } catch (TikaException | SAXException ex) {
            throw new IllegalArgumentException("document is protected or corrupt");
        } catch (IOException ex) {
            throw new IllegalArgumentException("document could not be read");
        }
        if (text.isBlank()) {
            throw new IllegalArgumentException("document contains no extractable text");
        }

        String detected = metadata.get(org.apache.tika.metadata.HttpHeaders.CONTENT_TYPE);
        if (detected == null || detected.isBlank()) {
            throw new IllegalArgumentException("document type could not be detected");
        }
        requireCompatibleMime(extension, detected.toLowerCase(Locale.ROOT));

        String title = metadata.get(org.apache.tika.metadata.TikaCoreProperties.TITLE);
        if (title == null || title.isBlank()) {
            title = "Document";
        }
        var sections = extractSections(extension, text, title);
        return new ExtractedDocument(title, detected, sections);
    }

    private static List<ExtractedSection> extractSections(
            String extension, String text, String title) {
        if ("md".equals(extension)) {
            return markdownSections(text, title);
        }
        var firstLine = text.lines().findFirst().orElse("").strip();
        var heading = firstLine.startsWith("#") ? firstLine : title;
        return List.of(new ExtractedSection(heading, text.strip(), 0));
    }

    private static List<ExtractedSection> markdownSections(String text, String title) {
        var paths = new ArrayList<String>();
        var buffers = new ArrayList<StringBuilder>();
        var sectionPaths = new ArrayList<List<String>>();
        int ordinal = 0;
        for (var line : text.lines().toList()) {
            Matcher matcher = HEADING.matcher(line.strip());
            if (matcher.matches()) {
                int level = matcher.group(1).length();
                while (paths.size() >= level) {
                    paths.remove(paths.size() - 1);
                }
                paths.add(matcher.group(2).strip());
                buffers.add(new StringBuilder());
                sectionPaths.add(List.copyOf(paths));
            } else if (!line.isBlank()) {
                if (buffers.isEmpty()) {
                    paths.add(title);
                    buffers.add(new StringBuilder());
                    sectionPaths.add(List.copyOf(paths));
                }
                buffers.get(buffers.size() - 1).append(line).append('\n');
            }
        }
        var sections = new ArrayList<ExtractedSection>();
        for (int i = 0; i < buffers.size(); i++) {
            sections.add(new ExtractedSection(
                    String.join(" > ", sectionPaths.get(i)),
                    buffers.get(i).toString().strip(),
                    ordinal++));
        }
        if (sections.isEmpty()) {
            sections.add(new ExtractedSection(title, text.strip(), 0));
        }
        return List.copyOf(sections);
    }

    private static void requireCompatibleMime(String extension, String detected) {
        boolean compatible = switch (extension) {
            case "pdf" -> detected.contains("application/pdf");
            case "docx" -> detected.contains("wordprocessingml") || detected.contains("application/zip");
            case "md", "txt" -> detected.startsWith("text/")
                    || detected.contains("markdown");
            default -> false;
        };
        if (!compatible) {
            throw new IllegalArgumentException(
                    "file extension does not match detected content type");
        }
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            throw new IllegalArgumentException("file extension is missing");
        }
        String extension = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("file type is not supported");
        }
        return extension;
    }

    private static int safeInt(long value) {
        return (int) Math.min(value, Integer.MAX_VALUE);
    }
}
