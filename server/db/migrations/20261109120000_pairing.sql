-- migrate:up
-- Devices from config are kept in step with it on every start. Paired ones stay until removed.
ALTER TABLE devices ADD COLUMN configured boolean NOT NULL DEFAULT false;
UPDATE devices SET configured = true;

-- A device asking to join an account (ADR 0004). It shows the code, a signed-in device
-- approves it, and it trades its secret for a token of its own. Only the secret's hash is
-- kept. account_id is set once approved.
CREATE TABLE pairings (
    id bigserial PRIMARY KEY,
    code text NOT NULL UNIQUE,
    secret_hash bytea NOT NULL UNIQUE,
    name text NOT NULL,
    account_id bigint REFERENCES accounts ON DELETE CASCADE,
    expires_at timestamptz NOT NULL
);

-- migrate:down
DROP TABLE pairings;
ALTER TABLE devices DROP COLUMN configured;
