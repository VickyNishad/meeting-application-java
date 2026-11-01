package com.example.meetplatform.identity.mapper;

import com.example.meetplatform.identity.dto.UserDtos.UserProfile;
import com.example.meetplatform.identity.dto.UserDtos.UserSummary;
import com.example.meetplatform.identity.entity.User;

public final class UserMapper {

    private UserMapper() {
    }

    public static UserSummary toSummary(User user) {
        return new UserSummary(user.getId(), user.getDisplayName(), user.getEmail(), user.getAvatarUrl());
    }

    public static UserProfile toProfile(User user) {
        return new UserProfile(user.getId(), user.getDisplayName(), user.getEmail(), user.getAvatarUrl(),
                user.getTimeZone(), user.isEmailVerified(), user.getSystemRole().name(), user.getStatus().name(),
                user.getCreatedAt());
    }
}
