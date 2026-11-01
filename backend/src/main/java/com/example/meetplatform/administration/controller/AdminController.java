package com.example.meetplatform.administration.controller;

import com.example.meetplatform.administration.service.AdminService;
import com.example.meetplatform.administration.service.AdminService.AuditView;
import com.example.meetplatform.administration.service.AdminService.LiveMeeting;
import com.example.meetplatform.administration.service.AdminService.Stats;
import com.example.meetplatform.common.response.PageResponse;
import com.example.meetplatform.common.security.CurrentUserProvider;
import com.example.meetplatform.identity.dto.UserDtos.UserProfile;
import com.example.meetplatform.identity.entity.SystemRole;
import com.example.meetplatform.identity.entity.UserStatus;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Platform administration. Access is restricted to SYSTEM_ADMIN by the security filter chain. */
@Tag(name = "Administration")
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminService service;
    private final CurrentUserProvider currentUser;

    public AdminController(AdminService service, CurrentUserProvider currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping("/stats")
    public Stats stats() {
        return service.stats();
    }

    @GetMapping("/users")
    public PageResponse<UserProfile> users(@RequestParam(required = false) String q,
                                           @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size) {
        return service.users(q, page, size);
    }

    @PatchMapping("/users/{userId}")
    public UserProfile updateUser(@PathVariable UUID userId, @RequestBody UpdateUserRequest request) {
        return service.updateUser(currentUser.require(), userId, request.status(), request.systemRole());
    }

    @GetMapping("/meetings/live")
    public List<LiveMeeting> live() {
        return service.liveMeetings();
    }

    @GetMapping("/audit-logs")
    public PageResponse<AuditView> auditLogs(@RequestParam(required = false) UUID actorId,
                                             @RequestParam(required = false) String action,
                                             @RequestParam(required = false) Instant from,
                                             @RequestParam(required = false) Instant to,
                                             @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        return service.auditLogs(actorId, action, from, to, page, size);
    }

    public record UpdateUserRequest(UserStatus status, SystemRole systemRole) {
    }
}
