package com.pulseink.domain.content;

import com.pulseink.agent.artifact.ArtifactStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ContentVersion(
        long id,
        long contentItemId,
        int versionNo,
        Map<String, Object> content,
        List<String> sourceRefs,
        ContentOrigin origin,
        String sourceArtifactId,
        Integer sourceArtifactVersion,
        ArtifactStatus sourceArtifactStatus,
        Long createdBy,
        Instant createdAt) {

    public ContentVersion {
        content = Map.copyOf(content);
        sourceRefs = List.copyOf(sourceRefs);
    }
}
