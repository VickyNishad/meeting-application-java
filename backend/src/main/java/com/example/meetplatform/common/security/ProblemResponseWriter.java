package com.example.meetplatform.common.security;

import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.common.observability.TraceContext;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/** Renders 401/403 from the security filter chain in the same problem-details format as controller errors. */
@Component
public class ProblemResponseWriter implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JsonMapper jsonMapper;
    private final TraceContext traceContext;

    public ProblemResponseWriter(JsonMapper jsonMapper, TraceContext traceContext) {
        this.jsonMapper = jsonMapper;
        this.traceContext = traceContext;
    }

    @Override
    public void commence(jakarta.servlet.http.HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException ex) throws IOException {
        ErrorCode code = ex instanceof InvalidBearerTokenException ? ErrorCode.TOKEN_INVALID : ErrorCode.UNAUTHENTICATED;
        if (code == ErrorCode.TOKEN_INVALID) {
            response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
        }
        write(response, code, code == ErrorCode.TOKEN_INVALID
                ? "The access token is invalid, expired or revoked." : "Authentication is required.");
    }

    @Override
    public void handle(jakarta.servlet.http.HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException ex) throws IOException {
        write(response, ErrorCode.FORBIDDEN, "You are not allowed to perform this action.");
    }

    private void write(HttpServletResponse response, ErrorCode code, String detail) throws IOException {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(code.status(), detail);
        pd.setProperty("code", code.name());
        pd.setProperty("traceId", traceContext.currentTraceId());
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(jsonMapper.writeValueAsString(pd));
    }
}
