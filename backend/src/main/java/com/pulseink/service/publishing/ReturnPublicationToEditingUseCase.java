package com.pulseink.service.publishing;

/** Returns a permanently failed publication to the human content-correction workflow. */
public interface ReturnPublicationToEditingUseCase {

    void returnToEditing(long publicationId);
}
