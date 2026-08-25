package com.pulseink.client.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.domain.publication.PublishReceipt;
import com.pulseink.service.publishing.ChannelPort;
import com.pulseink.service.publishing.ChannelRejectedException;
import com.pulseink.service.publishing.ChannelUnavailableException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * HTTP adapter for the Channel Sandbox contract. POST with an Idempotency-Key header; on
 * read/connect timeouts it first looks the key up and retries the POST only when the sandbox
 * has no record yet, so a lost response never creates a second post.
 */
public class ChannelHttpAdapter implements ChannelPort {

    private static final Logger log = LoggerFactory.getLogger(ChannelHttpAdapter.class);

    private final RestClient client;
    private final ObjectMapper objectMapper;

    public ChannelHttpAdapter(RestClient client, ObjectMapper objectMapper) {
        this.client = Objects.requireNonNull(client);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public PublishReceipt publish(PublishRequest request) {
        long started = System.nanoTime();
        try {
            var response = client.post().uri("/posts")
                    .header("Idempotency-Key", request.idempotencyKey().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body(request))
                    .retrieve()
                    .toEntity(String.class);
            log.debug("channel publish ok status={} durationMs={}",
                    response.getStatusCode().value(), durationMs(started));
            return parse(request, response.getBody());
        } catch (RestClientResponseException responseError) {
            return handleResponseError(responseError, started);
        } catch (ResourceAccessException accessError) {
            return recoverByLookup(request, started);
        } catch (RestClientException extractionError) {
            if (hasTimeoutCause(extractionError)) {
                return recoverByLookup(request, started);
            }
            throw extractionError;
        }
    }

    private PublishReceipt recoverByLookup(PublishRequest request, long started) {
        try {
            var lookup = client.get()
                    .uri("/posts/by-idempotency-key/{key}", request.idempotencyKey())
                    .retrieve()
                    .toEntity(String.class);
            log.debug("channel lookup after timeout ok status={} durationMs={}",
                    lookup.getStatusCode().value(), durationMs(started));
            return parse(request, lookup.getBody());
        } catch (HttpClientErrorException.NotFound notFound) {
            log.debug("channel lookup after timeout returned 404; retrying POST with same key");
            return postAgain(request, started);
        } catch (RestClientResponseException responseError) {
            return handleResponseError(responseError, started);
        } catch (ResourceAccessException accessError) {
            throw new ChannelUnavailableException(
                    "channel is unavailable after POST timeout and lookup failure", accessError);
        } catch (RestClientException extractionError) {
            throw new ChannelUnavailableException(
                    "channel lookup failed while reading the receipt", extractionError);
        }
    }

    private PublishReceipt postAgain(PublishRequest request, long started) {
        try {
            var response = client.post().uri("/posts")
                    .header("Idempotency-Key", request.idempotencyKey().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body(request))
                    .retrieve()
                    .toEntity(String.class);
            log.debug("channel POST retry ok status={} durationMs={}",
                    response.getStatusCode().value(), durationMs(started));
            return parse(request, response.getBody());
        } catch (RestClientResponseException responseError) {
            return handleResponseError(responseError, started);
        } catch (ResourceAccessException accessError) {
            throw new ChannelUnavailableException("channel POST retry failed", accessError);
        } catch (RestClientException extractionError) {
            if (hasTimeoutCause(extractionError)) {
                throw new ChannelUnavailableException(
                        "channel POST retry timed out while reading the receipt", extractionError);
            }
            throw extractionError;
        }
    }

    private PublishReceipt handleResponseError(RestClientResponseException error, long started) {
        log.debug("channel call failed status={} durationMs={}",
                error.getStatusCode().value(), durationMs(started));
        if (error.getStatusCode().is5xxServerError()) {
            throw new ChannelUnavailableException(
                    "channel returned " + error.getStatusCode().value());
        }
        var details = extractErrorDetails(error);
        throw new ChannelRejectedException(details.code(), details.message());
    }

    private ChannelErrorDetails extractErrorDetails(RestClientResponseException error) {
        try {
            JsonNode body = objectMapper.readTree(
                    new String(error.getResponseBodyAsByteArray(), StandardCharsets.UTF_8));
            String code = body.hasNonNull("code") && !body.get("code").asText().isBlank()
                    ? body.get("code").asText() : "CHANNEL_REJECTED";
            String message = body.hasNonNull("message")
                    ? safeChannelMessage(body.get("message").asText()) : "";
            if (!message.isBlank()) {
                return new ChannelErrorDetails(code, message);
            }
        } catch (Exception ignored) {
            // fall through to the generic code
        }
        return new ChannelErrorDetails("CHANNEL_REJECTED",
                "channel rejected the publication with status "
                        + error.getStatusCode().value());
    }

    private static String safeChannelMessage(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("[\\r\\n]+", " ").strip();
        return normalized.length() <= 300 ? normalized : normalized.substring(0, 300);
    }

    private record ChannelErrorDetails(String code, String message) {}

    private PublishReceipt parse(PublishRequest request, String body) {
        try {
            JsonNode json = objectMapper.readTree(body);
            return new PublishReceipt(
                    UUID.fromString(json.get("externalPostId").asText()),
                    request.idempotencyKey(),
                    CampaignChannel.valueOf(json.get("channel").asText()),
                    Instant.parse(json.get("publishedAt").asText()),
                    json.get("replayed").asBoolean());
        } catch (Exception failure) {
            throw new ChannelUnavailableException("channel receipt cannot be parsed", failure);
        }
    }

    private byte[] body(PublishRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourcePublicationId", request.sourcePublicationId());
        payload.put("contentVersionId", request.contentVersionId());
        payload.put("channel", request.channel().name());
        payload.put("content", request.content());
        payload.put("sourceRefs", List.copyOf(request.sourceRefs()));
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (Exception failure) {
            throw new IllegalStateException("publish request cannot be serialized", failure);
        }
    }

    private static long durationMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static boolean hasTimeoutCause(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
