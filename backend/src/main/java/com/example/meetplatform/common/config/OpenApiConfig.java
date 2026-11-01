package com.example.meetplatform.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI document served at {@code /v3/api-docs} and rendered by Swagger UI at {@code /swagger-ui.html}.
 * Use "Authorize" with the {@code accessToken} returned by {@code POST /api/v1/auth/login}.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    public OpenAPI meetPlatformOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Meetly API")
                        .version("v1")
                        .contact(new Contact().name("Meetly platform team"))
                        .description("""
                                Meetings, real-time media orchestration (LiveKit), recordings and scheduling.

                                **How to try it:** call `POST /api/v1/auth/register` (or log in with an existing account) \
                                via `POST /api/v1/auth/login`, copy `accessToken` from the response, click **Authorize** \
                                and paste it. Guests use the `X-Participant-Token` header (the `waitToken` returned by \
                                `POST /api/v1/meetings/{meetingId}/join`).

                                Errors use RFC 9457 problem details with a machine-readable `code`."""))
                .servers(List.of(new Server().url("/").description("This server")))
                .tags(List.of(
                        new Tag().name("Authentication").description("Register, login, refresh, logout, e-mail verification, password reset"),
                        new Tag().name("Users").description("Profile, password and session management"),
                        new Tag().name("Organizations").description("Organizations and member roles"),
                        new Tag().name("Meetings").description("Create, list, edit, cancel meetings and invitations"),
                        new Tag().name("Meeting participation").description("Join, waiting-room status, leave, end, participants"),
                        new Tag().name("Rooms").description("Personal meeting room and live session history"),
                        new Tag().name("Moderation").description("Host controls: admit, mute, remove, lock, co-hosts, settings, audit"),
                        new Tag().name("Recordings").description("Cloud recording control, library, signed playback/download"),
                        new Tag().name("Chat").description("In-meeting chat"),
                        new Tag().name("Files").description("In-meeting file sharing"),
                        new Tag().name("Availability").description("Weekly availability and date exceptions"),
                        new Tag().name("Booking pages").description("Booking page setup and public slot search"),
                        new Tag().name("Bookings").description("Public booking, cancel, reschedule"),
                        new Tag().name("Calendar integrations").description("Google / Microsoft calendar connections"),
                        new Tag().name("Notifications").description("In-app notifications"),
                        new Tag().name("Administration").description("Platform administration (SYSTEM_ADMIN only)"),
                        new Tag().name("Webhooks").description("Signed media-server webhooks (server to server)")))
                .components(new Components().addSecuritySchemes("bearer", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                        .description("Access token from POST /api/v1/auth/login")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
