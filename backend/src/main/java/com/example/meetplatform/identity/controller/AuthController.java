package com.example.meetplatform.identity.controller;

import com.example.meetplatform.common.audit.AuditActions;
import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.common.observability.ClientInfo;
import com.example.meetplatform.common.security.CurrentUserProvider;
import com.example.meetplatform.identity.dto.AuthDtos.AuthResponse;
import com.example.meetplatform.identity.dto.AuthDtos.ClientType;
import com.example.meetplatform.identity.dto.AuthDtos.EmailRequest;
import com.example.meetplatform.identity.dto.AuthDtos.LoginRequest;
import com.example.meetplatform.identity.dto.AuthDtos.OAuthProvider;
import com.example.meetplatform.identity.dto.AuthDtos.PasswordResetConfirmRequest;
import com.example.meetplatform.identity.dto.AuthDtos.RefreshRequest;
import com.example.meetplatform.identity.dto.AuthDtos.RegisterRequest;
import com.example.meetplatform.identity.dto.AuthDtos.RegisterResponse;
import com.example.meetplatform.identity.dto.AuthDtos.TokenRequest;
import com.example.meetplatform.identity.mapper.UserMapper;
import com.example.meetplatform.identity.service.AuthService;
import com.example.meetplatform.identity.service.RefreshCookies;
import com.example.meetplatform.identity.service.SessionService;
import com.example.meetplatform.identity.service.SessionService.IssuedSession;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@Tag(name = "Authentication")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String CSRF_HEADER = "X-Requested-With";

    private final AuthService authService;
    private final SessionService sessionService;
    private final RefreshCookies cookies;
    private final CurrentUserProvider currentUser;
    private final ObjectProvider<ClientRegistrationRepository> oauthClients;

    public AuthController(AuthService authService, SessionService sessionService, RefreshCookies cookies,
                          CurrentUserProvider currentUser, ObjectProvider<ClientRegistrationRepository> oauthClients) {
        this.authService = authService;
        this.sessionService = sessionService;
        this.cookies = cookies;
        this.currentUser = currentUser;
        this.oauthClients = oauthClients;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        return authService.register(request, ClientInfo.from(http));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return respond(authService.login(request, ClientInfo.from(http)));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@RequestBody(required = false) @Valid RefreshRequest body,
                                                HttpServletRequest http) {
        String token = refreshTokenFrom(body, http);
        return respond(sessionService.rotate(token, ClientInfo.from(http)));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) @Valid RefreshRequest body, HttpServletRequest http) {
        String token = refreshTokenFrom(body, http);
        sessionService.revokeByRefreshToken(token, AuditActions.USER_LOGOUT);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear().toString()).build();
    }

    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll() {
        sessionService.revokeAll(currentUser.require().userId(), "LOGOUT_ALL");
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear().toString()).build();
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@Valid @RequestBody TokenRequest request) {
        authService.verifyEmail(request.token());
    }

    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resendVerification(@Valid @RequestBody EmailRequest request, HttpServletRequest http) {
        authService.resendVerification(request.email(), ClientInfo.from(http));
    }

    @PostMapping("/password-reset/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestPasswordReset(@Valid @RequestBody EmailRequest request, HttpServletRequest http) {
        authService.requestPasswordReset(request.email(), ClientInfo.from(http));
    }

    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        authService.confirmPasswordReset(request.token(), request.newPassword());
    }

    @GetMapping("/oauth2/providers")
    public List<OAuthProvider> oauthProviders() {
        List<OAuthProvider> providers = new ArrayList<>();
        if (oauthClients.getIfAvailable() instanceof InMemoryClientRegistrationRepository repo) {
            for (ClientRegistration registration : repo) {
                providers.add(new OAuthProvider(registration.getRegistrationId(),
                        "/oauth2/authorization/" + registration.getRegistrationId()));
            }
        }
        return providers;
    }

    /**
     * Native clients send the refresh token in the body. Browsers rely on the HttpOnly cookie, in which case the
     * custom header is required: a cross-site form post cannot set it, which closes the CSRF vector.
     */
    private String refreshTokenFrom(RefreshRequest body, HttpServletRequest http) {
        if (body != null && body.refreshToken() != null && !body.refreshToken().isBlank()) {
            return body.refreshToken();
        }
        String token = cookies.read(http)
                .orElseThrow(() -> new ApiException(ErrorCode.TOKEN_INVALID, "No refresh token was presented."));
        if (http.getHeader(CSRF_HEADER) == null) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Missing " + CSRF_HEADER + " header.");
        }
        return token;
    }

    private ResponseEntity<AuthResponse> respond(IssuedSession issued) {
        boolean web = issued.clientType() == ClientType.WEB;
        AuthResponse body = new AuthResponse(issued.accessToken(), "Bearer", issued.expiresIn(),
                web ? null : issued.refreshToken(), UserMapper.toProfile(issued.user()));
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (web) {
            response.header(HttpHeaders.SET_COOKIE, cookies.issue(issued.refreshToken()).toString());
        }
        return response.body(body);
    }
}
