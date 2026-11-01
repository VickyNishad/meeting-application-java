-- Pending calendar OAuth authorizations. The OAuth state carries the SHA-256 of a random nonce that lives only
-- in an HttpOnly cookie of the browser that started the flow; the row makes each state usable exactly once.
CREATE TABLE calendar_oauth_states (
    nonce_hash  varchar(64)  PRIMARY KEY,
    user_id     uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider    varchar(32)  NOT NULL,
    expires_at  timestamptz  NOT NULL
);
CREATE INDEX ix_calendar_oauth_states_expiry ON calendar_oauth_states (expires_at);
