package com.example.meetplatform.notification.service;

import com.example.meetplatform.common.config.AppProperties;
import com.example.meetplatform.identity.entity.User;
import com.example.meetplatform.identity.repository.UserRepository;
import com.example.meetplatform.notification.entity.Notification;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

/**
 * E-mail over SMTP: plain text, with an optional calendar invitation (text/calendar) attachment. Verification and
 * password reset e-mails also have an HTML version, with the plain text kept for mail clients that do not show HTML.
 */
@Component
public class EmailSender {

    private static final String SENDER_NAME = "Meetly";

    private final JavaMailSender mailSender;
    private final String from;
    private final AccountEmailTemplate accountTemplate;
    private final UserRepository users;
    private final long verificationHours;
    private final long resetMinutes;

    public EmailSender(JavaMailSender mailSender, AppProperties properties, AccountEmailTemplate accountTemplate,
                       UserRepository users) {
        this.mailSender = mailSender;
        this.from = properties.notifications().fromAddress();
        this.accountTemplate = accountTemplate;
        this.users = users;
        this.verificationHours = properties.auth().emailVerificationTtl().toHours();
        this.resetMinutes = properties.auth().passwordResetTtl().toMinutes();
    }

    public void send(Notification n) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        String html = html(n);
        boolean multipart = n.getIcsAttachment() != null || html != null;
        MimeMessageHelper helper = new MimeMessageHelper(message, multipart, StandardCharsets.UTF_8.name());
        helper.setTo(n.getRecipientEmail());
        helper.setSubject(n.getTitle());
        String text = n.getBody() == null ? "" : n.getBody();
        if (html != null) {
            setNamedFrom(helper);
            helper.setText(text, html);
        } else {
            helper.setFrom(from);
            helper.setText(text, false);
        }
        if (n.getIcsAttachment() != null) {
            String method = n.getIcsAttachment().contains("METHOD:CANCEL") ? "CANCEL" : "REQUEST";
            helper.addAttachment("invite.ics", new ByteArrayResource(n.getIcsAttachment().getBytes(StandardCharsets.UTF_8)),
                    "text/calendar; charset=UTF-8; method=" + method);
        }
        mailSender.send(message);
    }

    /** @return the HTML version, or null for an e-mail that is plain text only */
    private String html(Notification n) {
        if (n.getLink() == null) {
            return null;
        }
        return switch (n.getType()) {
            case NotificationListeners.TYPE_VERIFY -> accountTemplate.verification(displayName(n), n.getLink(), verificationHours);
            case NotificationListeners.TYPE_RESET -> accountTemplate.passwordReset(displayName(n), n.getLink(), resetMinutes);
            default -> null;
        };
    }

    private void setNamedFrom(MimeMessageHelper helper) throws MessagingException {
        try {
            helper.setFrom(from, SENDER_NAME);
        } catch (UnsupportedEncodingException e) {
            helper.setFrom(from);
        }
    }

    private String displayName(Notification n) {
        return n.getUserId() == null ? null : users.findById(n.getUserId()).map(User::getDisplayName).orElse(null);
    }
}
