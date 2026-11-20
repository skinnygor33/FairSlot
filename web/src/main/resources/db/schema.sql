-- FairSlot web-tier database: accounts, resource catalog, booking history.
-- Runs on MySQL 8 (cloud VM) and on H2 in MySQL mode (local development and tests).
-- Idempotent: safe to run at every start-up.
-- The Erlang back-end is the source of truth for bookings; booking_history is a
-- copy written asynchronously after confirmation (proposal §3).

CREATE TABLE IF NOT EXISTS users (
    username      VARCHAR(32)  NOT NULL PRIMARY KEY,
    display_name  VARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(16)  NOT NULL DEFAULT 'student',
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS resources (
    id        VARCHAR(64)  NOT NULL PRIMARY KEY,
    name      VARCHAR(100) NOT NULL,
    kind      VARCHAR(16)  NOT NULL,
    building  VARCHAR(32)  NOT NULL,
    capacity  INT          NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS booking_history (
    booking_id   VARCHAR(64) NOT NULL PRIMARY KEY,
    username     VARCHAR(32) NOT NULL,
    resource_id  VARCHAR(64) NOT NULL,
    slot_start   BIGINT      NOT NULL,
    status       VARCHAR(16) NOT NULL,
    updated_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_history_user (username, slot_start),
    CONSTRAINT fk_history_user FOREIGN KEY (username) REFERENCES users (username),
    CONSTRAINT fk_history_resource FOREIGN KEY (resource_id) REFERENCES resources (id)
);

-- Demo catalog: two buildings of rooms and a shared equipment pool.
INSERT IGNORE INTO resources (id, name, kind, building, capacity) VALUES
    ('room:B1-101',   'Study room B1-101',  'room',      'B1',   6),
    ('room:B1-102',   'Study room B1-102',  'room',      'B1',   4),
    ('room:B2-201',   'Group room B2-201',  'room',      'B2',   10),
    ('room:B2-202',   'Group room B2-202',  'room',      'B2',   8),
    ('equip:proj-01', 'Projector 01',       'equipment', 'pool', 1),
    ('equip:proj-02', 'Projector 02',       'equipment', 'pool', 1),
    ('equip:ws-01',   'Lab workstation 01', 'equipment', 'pool', 1);
