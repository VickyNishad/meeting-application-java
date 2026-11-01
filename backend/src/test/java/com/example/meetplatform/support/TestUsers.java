package com.example.meetplatform.support;

import com.example.meetplatform.identity.entity.SystemRole;
import com.example.meetplatform.identity.entity.User;
import com.example.meetplatform.identity.entity.UserCredential;
import com.example.meetplatform.identity.repository.UserCredentialRepository;
import com.example.meetplatform.identity.repository.UserRepository;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Creates verified accounts directly and signs them in through the real login endpoint. */
@Component
public class TestUsers {

    public static final String PASSWORD = "correct horse battery staple";

    private final UserRepository users;
    private final UserCredentialRepository credentials;
    private final PasswordEncoder encoder;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public TestUsers(UserRepository users, UserCredentialRepository credentials, PasswordEncoder encoder,
                     TransactionTemplate tx, JsonMapper json) {
        this.users = users;
        this.credentials = credentials;
        this.encoder = encoder;
        this.tx = tx;
        this.json = json;
    }

    public SignedIn create(MockMvc mvc, String name) throws Exception {
        return create(mvc, name, SystemRole.USER);
    }

    public SignedIn create(MockMvc mvc, String name, SystemRole role) throws Exception {
        String email = name.toLowerCase().replace(' ', '.') + "+" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        UUID id = tx.execute(s -> {
            User user = new User(email, name, "Europe/London");
            user.setEmailVerified(true);
            user.setSystemRole(role);
            users.save(user);
            credentials.save(new UserCredential(user.getId(), encoder.encode(PASSWORD), Instant.now()));
            return user.getId();
        });
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(body);
        return new SignedIn(id, email, name, node.get("accessToken").asString());
    }

    public record SignedIn(UUID id, String email, String name, String accessToken) {

        public String bearer() {
            return "Bearer " + accessToken;
        }
    }
}
