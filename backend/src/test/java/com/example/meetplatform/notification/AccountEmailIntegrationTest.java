package com.example.meetplatform.notification;

import com.example.meetplatform.notification.entity.Notification;
import com.example.meetplatform.notification.repository.NotificationRepository;
import com.example.meetplatform.notification.service.AccountEmailTemplate;
import com.example.meetplatform.notification.service.EmailSender;
import com.example.meetplatform.support.AbstractIntegrationTest;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The designed verification and password reset e-mails, from the request to the message handed to SMTP. */
@TestPropertySource(properties = "management.health.mail.enabled=false")
class AccountEmailIntegrationTest extends AbstractIntegrationTest {

    @MockitoBean
    JavaMailSender mailSender;

    @Autowired
    EmailSender sender;

    @Autowired
    NotificationRepository notifications;

    @Autowired
    AccountEmailTemplate template;

    @Test
    void registrationSendsHtmlWithAPlainTextFallbackAndTheVerifyLink() throws Exception {
        String email = "design-" + UUID.randomUUID() + "@meet.local";
        register(email, "Priya Sharma");
        Notification queued = queued(email, "EMAIL_VERIFICATION");

        MimeMessage message = send(queued);

        assertThat(((InternetAddress) message.getFrom()[0]).getPersonal()).isEqualTo("Meetly");
        Map<String, String> parts = parts(message);
        assertThat(parts.get("text/plain")).contains("/verify-email?token=").contains("Priya Sharma");
        assertThat(parts.get("text/html")).contains("Hi Priya Sharma,").contains("Confirm your email address")
                .contains("Verify email address").contains("expires in 48 hours")
                .contains("href=\"" + queued.getLink() + "\"").doesNotContain("{{");
    }

    @Test
    void aPasswordResetRequestSendsTheDesignedEmailWithItsOwnWording() throws Exception {
        String email = "reset-" + UUID.randomUUID() + "@meet.local";
        register(email, "Nishad Vicky");
        mvc.perform(post("/api/v1/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("email", email)))).andExpect(status().is2xxSuccessful());
        Notification queued = queued(email, "PASSWORD_RESET");

        MimeMessage message = send(queued);

        assertThat(message.getSubject()).isEqualTo("Reset your password");
        assertThat(((InternetAddress) message.getFrom()[0]).getPersonal()).isEqualTo("Meetly");
        Map<String, String> parts = parts(message);
        assertThat(parts.get("text/plain")).contains("/reset-password?token=").contains("Nishad Vicky");
        assertThat(parts.get("text/html")).contains("Hi Nishad Vicky,").contains("Reset your password")
                .contains(">Reset password<").contains("expires in 60 minutes").contains("Your password stays the same")
                .contains("href=\"" + queued.getLink() + "\"")
                .doesNotContain("{{").doesNotContain("Verify email address");
    }

    @Test
    void theOneTimeLinkIsWipedFromTheOutboxOnceDelivered() {
        Notification n = emailWithLink("PASSWORD_RESET");

        n.markSent(Instant.now());

        assertThat(n.getLink()).isNull();
        assertThat(n.getBody()).doesNotContain("secret");
    }

    @Test
    void theLinkIsAlsoWipedWhenDeliveryGivesUp() {
        Notification n = emailWithLink("EMAIL_VERIFICATION");

        n.markAttemptFailed("refused", 1, Instant.now());

        assertThat(n.getLink()).isNull();
    }

    @Test
    void aDisplayNameCannotInjectHtmlOrFillAPlaceholderInEitherEmail() {
        String name = "<img src=x onerror=alert(1)> {{link}}";
        for (String html : new String[] {template.verification(name, "https://example.test/v?token=a&b=c", 48),
                template.passwordReset(name, "https://example.test/v?token=a&b=c", 60)}) {
            assertThat(html).doesNotContain("<img").contains("Hi &lt;img src=x onerror=alert(1)&gt; {{link}},");
            assertThat(html).contains("href=\"https://example.test/v?token=a&amp;b=c\"");
        }
    }

    @Test
    void aMissingNameGetsAPlainGreeting() {
        assertThat(template.verification(null, "https://example.test/v", 48)).contains("Hi there,");
        assertThat(template.passwordReset("  ", "https://example.test/v", 60)).contains("Hi there,");
    }

    @Test
    void otherEmailsStayPlainText() throws Exception {
        Notification changed = Notification.email(null, "b@meet.local", "PASSWORD_CHANGED", "Changed", "plain body", null,
                false, null, Instant.now(), Instant.now());

        assertThat(send(changed).getContentType()).startsWith("text/plain");
    }

    @Test
    void aQueuedEmailWithoutALinkStillGoesOutAsPlainText() throws Exception {
        // Queued before this version: nothing to put in the button, so it is sent as it was written.
        Notification old = Notification.email(null, "c@meet.local", "PASSWORD_RESET", "Reset", "plain body", null, true,
                null, Instant.now(), Instant.now());

        assertThat(send(old).getContentType()).startsWith("text/plain");
    }

    private static Notification emailWithLink(String type) {
        Notification n = Notification.email(null, "a@meet.local", type, "s", "secret body", null, true, null,
                Instant.now(), Instant.now());
        n.setLink("https://example.test/link?token=secret");
        return n;
    }

    private void register(String email, String name) throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("email", email, "password", "Design-Test-Pass-123", "displayName", name,
                                "timeZone", "UTC"))))
                .andExpect(status().isCreated());
    }

    private Notification queued(String email, String type) {
        return notifications.findAll().stream()
                .filter(n -> email.equals(n.getRecipientEmail()) && type.equals(n.getType())).findFirst().orElseThrow();
    }

    private MimeMessage send(Notification n) throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        sender.send(n);
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, atLeastOnce()).send(sent.capture());
        MimeMessage message = sent.getValue();
        message.saveChanges();
        return message;
    }

    /** The text parts of a message by type, however they are nested. */
    private static Map<String, String> parts(MimeMessage message) throws Exception {
        Map<String, String> found = new HashMap<>();
        collect((Multipart) message.getContent(), found);
        return found;
    }

    private static void collect(Multipart multipart, Map<String, String> found) throws Exception {
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            if (part.getContent() instanceof Multipart nested) {
                collect(nested, found);
            } else {
                found.put(part.getContentType().split(";")[0].strip(), String.valueOf(part.getContent()));
            }
        }
    }
}
