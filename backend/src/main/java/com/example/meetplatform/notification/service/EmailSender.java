package com.example.meetplatform.notification.service;

import com.example.meetplatform.common.config.AppProperties;
import com.example.meetplatform.notification.entity.Notification;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** Plain-text e-mail over SMTP, with an optional calendar invitation (text/calendar) attachment. */
@Component
public class EmailSender {

    private final JavaMailSender mailSender;
    private final String from;

    public EmailSender(JavaMailSender mailSender, AppProperties properties) {
        this.mailSender = mailSender;
        this.from = properties.notifications().fromAddress();
    }

    public void send(Notification n) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        boolean multipart = n.getIcsAttachment() != null;
        MimeMessageHelper helper = new MimeMessageHelper(message, multipart, StandardCharsets.UTF_8.name());
        helper.setFrom(from);
        helper.setTo(n.getRecipientEmail());
        helper.setSubject(n.getTitle());
        helper.setText(n.getBody() == null ? "" : n.getBody(), false);
        if (multipart) {
            String method = n.getIcsAttachment().contains("METHOD:CANCEL") ? "CANCEL" : "REQUEST";
            helper.addAttachment("invite.ics", new ByteArrayResource(n.getIcsAttachment().getBytes(StandardCharsets.UTF_8)),
                    "text/calendar; charset=UTF-8; method=" + method);
        }
        mailSender.send(message);
    }
}
