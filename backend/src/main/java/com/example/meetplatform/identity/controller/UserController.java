package com.example.meetplatform.identity.controller;

import com.example.meetplatform.common.security.CurrentUserProvider;
import com.example.meetplatform.identity.dto.UserDtos.ChangePasswordRequest;
import com.example.meetplatform.identity.dto.UserDtos.SessionView;
import com.example.meetplatform.identity.dto.UserDtos.UpdateProfileRequest;
import com.example.meetplatform.identity.dto.UserDtos.UserProfile;
import com.example.meetplatform.identity.service.UserService;
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

@Tag(name = "Users")
@RestController
@RequestMapping("/api/v1/users/me")
public class UserController {

    private final UserService userService;
    private final CurrentUserProvider currentUser;

    public UserController(UserService userService, CurrentUserProvider currentUser) {
        this.userService = userService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public UserProfile me() {
        return userService.profile(currentUser.require().userId());
    }

    @PatchMapping
    public UserProfile update(@Valid @RequestBody UpdateProfileRequest request) {
        return userService.updateProfile(currentUser.require().userId(), request);
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(currentUser.require(), request);
    }

    @GetMapping("/sessions")
    public List<SessionView> sessions() {
        return userService.sessions(currentUser.require());
    }

    @DeleteMapping("/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeSession(@PathVariable UUID sessionId) {
        userService.revokeSession(currentUser.require(), sessionId);
    }
}
