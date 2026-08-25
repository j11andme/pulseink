package com.pulseink.controller.campaign;

import com.pulseink.domain.campaign.Campaign;
import com.pulseink.domain.campaign.CampaignBrief;
import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.service.campaign.CreateCampaignUseCase;
import com.pulseink.service.campaign.CreateCampaignUseCase.CreateCampaignCommand;
import com.pulseink.service.campaign.QueryCampaignUseCase;
import com.pulseink.service.campaign.QueryCampaignUseCase.CampaignPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
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

@RestController
@RequestMapping("/api/campaigns")
public class CampaignController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;

    private final CreateCampaignUseCase createCampaignUseCase;
    private final QueryCampaignUseCase queryCampaignUseCase;

    public CampaignController(
            CreateCampaignUseCase createCampaignUseCase,
            QueryCampaignUseCase queryCampaignUseCase) {
        this.createCampaignUseCase = Objects.requireNonNull(createCampaignUseCase);
        this.queryCampaignUseCase = Objects.requireNonNull(queryCampaignUseCase);
    }

    @PostMapping
    public ResponseEntity<CampaignResponse> create(
            @Valid @RequestBody CreateCampaignRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        var actorUserId = extractActorUserId(jwt);
        var command = new CreateCampaignCommand(
                request.name(),
                request.objective(),
                request.audience(),
                request.channels(),
                request.constraints());
        var campaign = createCampaignUseCase.create(command, actorUserId);
        return ResponseEntity
                .created(URI.create("/api/campaigns/" + campaign.id()))
                .body(toResponse(campaign));
    }

    @GetMapping
    public CampaignPageResponse list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < MIN_PAGE_SIZE || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and 100");
        }
        var pageResult = queryCampaignUseCase.list(page, size);
        return toPageResponse(pageResult);
    }

    @GetMapping("/{id}")
    public CampaignResponse detail(@PathVariable long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("campaign id must be positive");
        }
        var campaign = queryCampaignUseCase.get(id);
        return toResponse(campaign);
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

    private static CampaignResponse toResponse(Campaign campaign) {
        return new CampaignResponse(
                campaign.id(),
                campaign.name(),
                campaign.brief().objective(),
                campaign.brief().audience(),
                campaign.brief().channels().stream().map(CampaignChannel::name).toList(),
                campaign.brief().constraints(),
                campaign.status().name(),
                campaign.createdBy(),
                campaign.version(),
                campaign.createdAt().orElse(null),
                campaign.updatedAt().orElse(null));
    }

    private static CampaignPageResponse toPageResponse(CampaignPage page) {
        var items = page.items().stream()
                .map(CampaignController::toResponse)
                .toList();
        return new CampaignPageResponse(
                items,
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }

    public record CreateCampaignRequest(
            @NotBlank String name,
            @NotBlank String objective,
            @NotBlank String audience,
            @NotEmpty List<CampaignChannel> channels,
            List<String> constraints) {
    }

    public record CampaignResponse(
            long id,
            String name,
            String objective,
            String audience,
            List<String> channels,
            List<String> constraints,
            String status,
            long createdBy,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record CampaignPageResponse(
            List<CampaignResponse> items,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }
}
