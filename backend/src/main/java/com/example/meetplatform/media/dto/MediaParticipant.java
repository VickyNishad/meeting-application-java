package com.example.meetplatform.media.dto;

import java.util.Set;

public record MediaParticipant(String identity, String name, Set<TrackSource> unmutedSources) {
}
