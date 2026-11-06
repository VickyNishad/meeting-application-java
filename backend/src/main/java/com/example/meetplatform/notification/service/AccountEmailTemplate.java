package com.example.meetplatform.notification.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The HTML version of account e-mails that ask the reader to follow a one-time link: one layout, so they always match.
 * The name and link are escaped because a display name is user input.
 */
@Component
public class AccountEmailTemplate {

    private static final String PATH = "templates/email/account-email.html";

    private final String template;

    public AccountEmailTemplate() {
        try {
            this.template = new ClassPathResource(PATH).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing e-mail template " + PATH, e);
        }
    }

    public String verification(String name, String link, long hours) {
        return render(name, link, "Confirm your email address",
                "Welcome to Meetly. Confirm your email address to finish setting up your account.",
                "Verify email address", hours + " hours",
                "Didn't create a Meetly account? You can safely ignore this email.");
    }

    public String passwordReset(String name, String link, long minutes) {
        return render(name, link, "Reset your password",
                "We received a request to reset your Meetly password. Choose a new one using the button below.",
                "Reset password", minutes + " minutes",
                "Didn't ask for this? You can safely ignore this email. Your password stays the same.");
    }

    private String render(String name, String link, String title, String intro, String button, String expiry,
                          String ignore) {
        String greeting = name == null || name.isBlank() ? "there" : name.strip();
        // The name goes last, so text in it can never be taken for a placeholder.
        return template.replace("{{title}}", title)
                .replace("{{intro}}", intro)
                .replace("{{button}}", button)
                .replace("{{expiry}}", expiry)
                .replace("{{ignore}}", ignore)
                .replace("{{link}}", HtmlUtils.htmlEscape(link, StandardCharsets.UTF_8.name()))
                .replace("{{name}}", HtmlUtils.htmlEscape(greeting, StandardCharsets.UTF_8.name()));
    }
}
