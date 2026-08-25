package com.pulseink.domain.content;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

public record ReviewIssue(
        ReviewIssueType type,
        Set<String> affectedTaskIds,
        String message) {

    public ReviewIssue {
        if (type == null) {
            throw new IllegalArgumentException("review issue type must not be null");
        }
        if (affectedTaskIds == null) {
            throw new IllegalArgumentException("affectedTaskIds must not be null");
        }
        var sorted = new TreeSet<String>();
        for (var taskId : affectedTaskIds) {
            if (taskId == null || taskId.isBlank()) {
                throw new IllegalArgumentException("affected taskId must not be blank");
            }
            sorted.add(taskId.strip());
        }
        affectedTaskIds = Collections.unmodifiableSet(sorted);
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("review issue message must not be blank");
        }
        message = message.strip();
        if (message.codePointCount(0, message.length()) > 1_000) {
            throw new IllegalArgumentException("review issue message exceeds 1000 code points");
        }
    }
}
