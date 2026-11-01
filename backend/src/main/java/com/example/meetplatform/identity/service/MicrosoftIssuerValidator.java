package com.example.meetplatform.identity.service;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Issuer check for Microsoft identity platform (v2.0) ID tokens. With the multi-tenant authorities
 * ({@code common}, {@code organizations}, {@code consumers}) the issuer is not fixed but
 * {@code https://login.microsoftonline.com/{tenant id}/v2.0} for the user's tenant, so the standard "issuer equals
 * configured issuer" check cannot be used. Instead the issuer must have that form and name the same tenant as the
 * token's {@code tid} claim, and the tenant must be one the configured authority admits.
 */
public class MicrosoftIssuerValidator implements OAuth2TokenValidator<Jwt> {

    /** Tenant id of personal Microsoft accounts. */
    static final String CONSUMERS_TENANT_ID = "9188040d-6c67-4c5b-b112-36a304b66dad";

    private static final String GUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern ISSUER =
            Pattern.compile("^https://login\\.microsoftonline\\.com/(" + GUID + ")/v2\\.0$");
    private static final Pattern TENANT_ID = Pattern.compile("^" + GUID + "$");

    private final String configuredTenant;

    /** @param configuredTenant authority segment ({@code MICROSOFT_TENANT}): common, organizations, consumers, id, domain */
    public MicrosoftIssuerValidator(String configuredTenant) {
        this.configuredTenant = configuredTenant == null || configuredTenant.isBlank()
                ? "common" : configuredTenant.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String issuer = token.getClaimAsString("iss");
        String tid = token.getClaimAsString("tid");
        Matcher m = issuer == null ? null : ISSUER.matcher(issuer.toLowerCase(Locale.ROOT));
        if (m == null || !m.matches() || tid == null || !m.group(1).equals(tid.toLowerCase(Locale.ROOT))) {
            return invalid("The ID token issuer is not a Microsoft tenant issuer matching its tid claim.");
        }
        String tenant = m.group(1);
        boolean allowed = switch (configuredTenant) {
            case "common" -> true;
            case "consumers" -> tenant.equals(CONSUMERS_TENANT_ID);
            case "organizations" -> !tenant.equals(CONSUMERS_TENANT_ID);
            // A tenant id must match exactly. A domain-name authority (contoso.onmicrosoft.com) cannot be mapped
            // to its id offline; such tokens only come from that tenant's own token endpoint.
            default -> !TENANT_ID.matcher(configuredTenant).matches() || tenant.equals(configuredTenant);
        };
        return allowed ? OAuth2TokenValidatorResult.success()
                : invalid("The ID token was issued by a tenant this application does not accept.");
    }

    private static OAuth2TokenValidatorResult invalid(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_id_token", description, null));
    }
}
