package com.pulseink.service.publishing;

import com.pulseink.domain.publication.Publication;
import java.util.List;

public interface QueryPublicationUseCase {

    Publication get(long publicationId);

    List<Publication> findByRunId(long runId);
}
