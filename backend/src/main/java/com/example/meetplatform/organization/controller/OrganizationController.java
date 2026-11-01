package com.example.meetplatform.organization.controller;

import com.example.meetplatform.common.security.CurrentUserProvider;
import com.example.meetplatform.organization.dto.OrganizationDtos.AddMemberRequest;
import com.example.meetplatform.organization.dto.OrganizationDtos.CreateOrganizationRequest;
import com.example.meetplatform.organization.dto.OrganizationDtos.MemberView;
import com.example.meetplatform.organization.dto.OrganizationDtos.OrganizationView;
import com.example.meetplatform.organization.dto.OrganizationDtos.UpdateMemberRequest;
import com.example.meetplatform.organization.dto.OrganizationDtos.UpdateOrganizationRequest;
import com.example.meetplatform.organization.service.OrganizationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Organizations")
@RestController
@RequestMapping("/api/v1/organizations")
public class OrganizationController {

    private final OrganizationService service;
    private final CurrentUserProvider currentUser;

    public OrganizationController(OrganizationService service, CurrentUserProvider currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrganizationView create(@Valid @RequestBody CreateOrganizationRequest request) {
        return service.create(currentUser.require(), request);
    }

    @GetMapping
    public List<OrganizationView> mine() {
        return service.mine(currentUser.require());
    }

    @GetMapping("/{orgId}")
    public OrganizationView get(@PathVariable UUID orgId) {
        return service.get(currentUser.require(), orgId);
    }

    @PatchMapping("/{orgId}")
    public OrganizationView update(@PathVariable UUID orgId, @Valid @RequestBody UpdateOrganizationRequest request) {
        return service.update(currentUser.require(), orgId, request);
    }

    @GetMapping("/{orgId}/members")
    public List<MemberView> members(@PathVariable UUID orgId) {
        return service.members(currentUser.require(), orgId);
    }

    @PostMapping("/{orgId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public void addMember(@PathVariable UUID orgId, @Valid @RequestBody AddMemberRequest request) {
        service.addMember(currentUser.require(), orgId, request);
    }

    @PatchMapping("/{orgId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeRole(@PathVariable UUID orgId, @PathVariable UUID userId,
                           @Valid @RequestBody UpdateMemberRequest request) {
        service.changeRole(currentUser.require(), orgId, userId, request.role());
    }

    @DeleteMapping("/{orgId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID orgId, @PathVariable UUID userId) {
        service.removeMember(currentUser.require(), orgId, userId);
    }
}
