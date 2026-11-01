package com.example.meetplatform.identity;

import com.example.meetplatform.identity.service.MicrosoftIssuerValidator;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issuer validation for multi-tenant Microsoft sign-in. Uses hand-built tokens: an end-to-end check against
 * login.microsoftonline.com needs a real app registration and tenant and is not part of the automated suite.
 */
class MicrosoftIssuerValidatorTest {

    private static final String TENANT = "72f988bf-86f1-41af-91ab-2d7cd011db47";
    private static final String OTHER_TENANT = "0b5e5f1a-1111-4c2d-9e3f-123456789abc";
    private static final String CONSUMERS = "9188040d-6c67-4c5b-b112-36a304b66dad";

    @Test
    void commonAcceptsAnyTenantWhoseIssuerMatchesItsTid() {
        MicrosoftIssuerValidator common = new MicrosoftIssuerValidator("common");
        assertThat(valid(common, issuer(TENANT), TENANT)).isTrue();
        assertThat(valid(common, issuer(CONSUMERS), CONSUMERS)).isTrue();
        // Upper-case GUIDs are the same tenant.
        assertThat(valid(common, issuer(TENANT.toUpperCase()), TENANT)).isTrue();
    }

    @Test
    void issuerMustBeAMicrosoftTenantIssuerForTheTokensOwnTenant() {
        MicrosoftIssuerValidator common = new MicrosoftIssuerValidator("common");
        assertThat(valid(common, issuer(TENANT), OTHER_TENANT)).isFalse();
        assertThat(valid(common, issuer(TENANT), null)).isFalse();
        assertThat(valid(common, "https://login.microsoftonline.com/common/v2.0", TENANT)).isFalse();
        assertThat(valid(common, "https://login.microsoftonline.com/{tenantid}/v2.0", TENANT)).isFalse();
        assertThat(valid(common, "https://evil.example/" + TENANT + "/v2.0", TENANT)).isFalse();
        assertThat(valid(common, "https://sts.windows.net/" + TENANT + "/", TENANT)).isFalse();
        assertThat(valid(common, issuer(TENANT) + "/extra", TENANT)).isFalse();
    }

    @Test
    void restrictedAuthoritiesOnlyAdmitTheirTenants() {
        assertThat(valid(new MicrosoftIssuerValidator("organizations"), issuer(TENANT), TENANT)).isTrue();
        assertThat(valid(new MicrosoftIssuerValidator("organizations"), issuer(CONSUMERS), CONSUMERS)).isFalse();
        assertThat(valid(new MicrosoftIssuerValidator("consumers"), issuer(CONSUMERS), CONSUMERS)).isTrue();
        assertThat(valid(new MicrosoftIssuerValidator("consumers"), issuer(TENANT), TENANT)).isFalse();
        assertThat(valid(new MicrosoftIssuerValidator(TENANT), issuer(TENANT), TENANT)).isTrue();
        assertThat(valid(new MicrosoftIssuerValidator(TENANT), issuer(OTHER_TENANT), OTHER_TENANT)).isFalse();
    }

    private static boolean valid(MicrosoftIssuerValidator validator, String iss, String tid) {
        Jwt.Builder token = Jwt.withTokenValue("id-token").header("alg", "RS256").claim("iss", iss)
                .claim("sub", "subject").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300));
        if (tid != null) {
            token.claim("tid", tid);
        }
        return !validator.validate(token.build()).hasErrors();
    }

    private static String issuer(String tenant) {
        return "https://login.microsoftonline.com/" + tenant + "/v2.0";
    }
}
