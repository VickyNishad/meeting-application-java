package com.example.meetplatform.media.dto;

import java.util.Set;

/**
 * What a participant may do in one media room. Clients can never grant themselves more than this.
 * {@code hidden}: the participant is not shown to anyone else in that room (it can still subscribe if allowed).
 */
public record MediaPermissions(boolean canSubscribe, boolean canPublishData, Set<TrackSource> publishSources,
                               boolean hidden) {

    public MediaPermissions(boolean canSubscribe, boolean canPublishData, Set<TrackSource> publishSources) {
        this(canSubscribe, canPublishData, publishSources, false);
    }

    public boolean canPublish() {
        return !publishSources.isEmpty();
    }
}
