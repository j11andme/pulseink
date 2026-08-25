package com.pulseink.controller.knowledge;

import com.pulseink.domain.knowledge.EvidenceAuthority;
import com.pulseink.domain.knowledge.KnowledgeDocumentStatus;
import com.pulseink.domain.knowledge.KnowledgeType;
import com.pulseink.service.knowledge.IngestKnowledgeUseCase;
import com.pulseink.service.knowledge.IngestKnowledgeUseCase.UploadCommand;
import com.pulseink.service.knowledge.QueryKnowledgeUseCase;
import com.pulseink.service.knowledge.QueryKnowledgeUseCase.DocumentItem;
import com.pulseink.service.knowledge.QueryKnowledgeUseCase.DocumentPage;
import com.pulseink.config.properties.KnowledgeProperties;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final IngestKnowledgeUseCase ingestUseCase;
    private final QueryKnowledgeUseCase queryUseCase;
    private final KnowledgeProperties knowledgeProperties;

    public KnowledgeController(IngestKnowledgeUseCase ingestUseCase,
                               QueryKnowledgeUseCase queryUseCase,
                               KnowledgeProperties knowledgeProperties) {
        this.ingestUseCase = Objects.requireNonNull(ingestUseCase);
        this.queryUseCase = Objects.requireNonNull(queryUseCase);
        this.knowledgeProperties = Objects.requireNonNull(knowledgeProperties);
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("file") MultipartFile file,
            @RequestParam("knowledgeType") KnowledgeType knowledgeType,
            @RequestParam("authority") EvidenceAuthority authority) throws IOException {
        var result = ingestUseCase.upload(new UploadCommand(
                file.getOriginalFilename(),
                file.getContentType(),
                knowledgeType,
                authority,
                extractActorUserId(jwt),
                knowledgeProperties.maxFileBytes(),
                file.getInputStream()));
        return ResponseEntity.accepted().body(new UploadResponse(
                result.documentId(), result.sourceId(), result.jobId(), result.status()));
    }

    @GetMapping("/documents")
    public DocumentPage list(
            @RequestParam(required = false) KnowledgeDocumentStatus status,
            @RequestParam(required = false) KnowledgeType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and 100");
        }
        return queryUseCase.list(status, type, page, size);
    }

    @PostMapping("/documents/{id}/retry")
    public ResponseEntity<Void> retry(@PathVariable long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("document id must be positive");
        }
        ingestUseCase.retry(id);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/search-test")
    public SearchTestResponse searchTest(@RequestBody SearchTestRequest request) {
        if (request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        var result = queryUseCase.search(
                request.query(),
                request.types() == null ? List.of() : request.types(),
                request.authorities() == null ? List.of() : request.authorities(),
                request.updatedAfter(),
                request.topK() == null ? 5 : request.topK());
        return new SearchTestResponse(
                result.retrievalMode().name(),
                result.degradedReasonCode(),
                result.evidence().stream()
                        .map(chunk -> new EvidenceDto(
                                chunk.sourceId(),
                                chunk.title(),
                                chunk.headingPath(),
                                chunk.snippet(),
                                chunk.score(),
                                chunk.channels(),
                                chunk.knowledgeType().name(),
                                chunk.authority().name(),
                                chunk.updatedAt()))
                        .toList());
    }

    private long extractActorUserId(Jwt jwt) {
        if (jwt == null) {
            throw new IllegalStateException("missing authentication principal");
        }
        var uid = jwt.getClaim("uid");
        if (uid instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("jwt uid claim is missing or not a number");
    }

    public record UploadResponse(long documentId, String sourceId, String jobId, String status) {
    }

    public record SearchTestRequest(
            String query,
            List<KnowledgeType> types,
            List<EvidenceAuthority> authorities,
            Instant updatedAfter,
            Integer topK) {
    }

    public record SearchTestResponse(
            String retrievalMode,
            String degradedReasonCode,
            List<EvidenceDto> evidence) {
    }

    public record EvidenceDto(
            String sourceId,
            String title,
            String heading,
            String snippet,
            double score,
            java.util.Set<String> channels,
            String type,
            String authority,
            Instant updatedAt) {
    }
}
