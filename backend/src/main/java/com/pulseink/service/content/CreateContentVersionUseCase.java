package com.pulseink.service.content;

import com.pulseink.domain.content.ContentVersion;
import java.util.List;
import java.util.Map;

public interface CreateContentVersionUseCase {

    ContentVersion createVersion(Command command);

    record Command(long contentId, int expectedCurrentVersionNo,
                   long expectedItemVersion, Map<String, Object> content,
                   List<String> sourceRefs, long actorUserId) {
        public Command {
            content = content == null ? Map.of() : Map.copyOf(content);
            sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        }
    }
}
