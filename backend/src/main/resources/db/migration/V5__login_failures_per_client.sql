-- Failed-login lockout is kept per account *and* client (SHA-256 of the IP address) instead of per account, so
-- anyone who knows an e-mail address cannot lock its owner out; brute force from one client is still throttled.
-- (user_credentials.failed_attempts / locked_until are no longer used for lockout.)
CREATE TABLE login_failures (
    user_id          uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    client_key       varchar(64)  NOT NULL,
    failed_attempts  integer      NOT NULL,
    locked_until     timestamptz,
    updated_at       timestamptz  NOT NULL,
    PRIMARY KEY (user_id, client_key)
);
CREATE INDEX ix_login_failures_updated ON login_failures (updated_at);
