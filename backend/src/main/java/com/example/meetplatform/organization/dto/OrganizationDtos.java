package com.example.meetplatform.organization.dto;

import com.example.meetplatform.identity.dto.UserDtos.UserSummary;
import com.example.meetplatform.organization.entity.OrganizationMember;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class OrganizationDtos {

    private OrganizationDtos() {
    }

    public record CreateOrganizationRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,78}[a-z0-9]$",
                    message = "must be 3-80 lowercase letters, digits or hyphens") String slug) {
    }

    public record UpdateOrganizationRequest(
            @Size(min = 1, max = 200) String name,
            @Min(1) @Max(3650) Integer defaultRecordingRetentionDays,
            Boolean allowGuests,
            Long version) {
    }

    public record OrganizationView(UUID id, String name, String slug, int defaultRecordingRetentionDays,
                                   boolean allowGuests, Instant createdAt, long version, String myRole) {
    }

    public record MemberView(UserSummary user, OrganizationMember.Role role, Instant joinedAt) {
    }

    public record AddMemberRequest(@NotBlank @Email String email, @NotNull OrganizationMember.Role role) {
    }

    public record UpdateMemberRequest(@NotNull OrganizationMember.Role role) {
    }
}
