-- migrate:up
-- Everything belongs to an account, so going multi-user only changes how tokens are found.
CREATE TABLE accounts (
    id bigserial PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Only a hash of each token is kept, so a leaked database holds no usable tokens.
CREATE TABLE devices (
    id bigserial PRIMARY KEY,
    account_id bigint NOT NULL REFERENCES accounts ON DELETE CASCADE,
    token_hash bytea NOT NULL UNIQUE,
    name text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE settings (
    account_id bigint NOT NULL REFERENCES accounts ON DELETE CASCADE,
    key text NOT NULL,
    value integer NOT NULL,
    PRIMARY KEY (account_id, key)
);

CREATE TABLE pending_changes (
    id bigserial PRIMARY KEY,
    account_id bigint NOT NULL REFERENCES accounts ON DELETE CASCADE,
    key text NOT NULL,
    value integer NOT NULL,
    requested_at timestamptz NOT NULL,
    apply_at timestamptz NOT NULL
);

-- One row per video per budget day. intervals is [[start, end], ...] in seconds of video,
-- merged, so each second is charged once per day.
CREATE TABLE watched (
    account_id bigint NOT NULL REFERENCES accounts ON DELETE CASCADE,
    day date NOT NULL,
    video_id text NOT NULL,
    title text NOT NULL,
    channel text NOT NULL,
    intervals jsonb NOT NULL,
    charged_seconds double precision NOT NULL DEFAULT 0,
    last_at timestamptz NOT NULL,
    PRIMARY KEY (account_id, day, video_id)
);

CREATE TABLE sessions (
    id bigserial PRIMARY KEY,
    account_id bigint NOT NULL REFERENCES accounts ON DELETE CASCADE,
    day date NOT NULL,
    videos integer NOT NULL,
    started_at timestamptz NOT NULL
);
CREATE INDEX sessions_account_day ON sessions (account_id, day);

-- first_at and last_at are the first and latest check-ins for the video in that session.
CREATE TABLE session_videos (
    session_id bigint NOT NULL REFERENCES sessions ON DELETE CASCADE,
    video_id text NOT NULL,
    first_at timestamptz NOT NULL,
    last_at timestamptz NOT NULL,
    PRIMARY KEY (session_id, video_id)
);

CREATE TABLE subscriptions (
    account_id bigint NOT NULL REFERENCES accounts ON DELETE CASCADE,
    channel_id text NOT NULL,
    name text NOT NULL,
    added_at timestamptz NOT NULL,
    PRIMARY KEY (account_id, channel_id)
);

CREATE TABLE playlists (
    id bigserial PRIMARY KEY,
    account_id bigint NOT NULL REFERENCES accounts ON DELETE CASCADE,
    name text NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE TABLE playlist_items (
    playlist_id bigint NOT NULL REFERENCES playlists ON DELETE CASCADE,
    video_id text NOT NULL,
    title text NOT NULL,
    channel text NOT NULL,
    duration integer,
    position integer NOT NULL,
    PRIMARY KEY (playlist_id, video_id)
);

-- migrate:down
DROP TABLE playlist_items, playlists, subscriptions, session_videos, sessions, watched, pending_changes, settings, devices, accounts;
