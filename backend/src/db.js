'use strict';

const path = require('path');
const fs = require('fs');
const Database = require('better-sqlite3');

const dbPath = process.env.DB_PATH || path.join(__dirname, '..', 'data', 'gtracker.db');

// Make sure the folder for the database file exists.
fs.mkdirSync(path.dirname(dbPath), { recursive: true });

const db = new Database(dbPath);
db.pragma('journal_mode = WAL');

db.exec(`
  CREATE TABLE IF NOT EXISTS positions (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id  TEXT    NOT NULL,
    lat        REAL    NOT NULL,
    lng        REAL    NOT NULL,
    accuracy   REAL,
    speed      REAL,
    -- when the fix was taken on the device (epoch millis)
    recorded_at INTEGER NOT NULL,
    -- when the server stored it (epoch millis)
    received_at INTEGER NOT NULL
  );

  CREATE INDEX IF NOT EXISTS idx_positions_device_time
    ON positions (device_id, recorded_at DESC);
`);

// --- Migrations ---
const columns = db.prepare('PRAGMA table_info(positions)').all().map((c) => c.name);
// Friendly display name.
if (!columns.includes('label')) {
  db.exec('ALTER TABLE positions ADD COLUMN label TEXT');
}
// Group isolation: hashed group code (NULL legacy rows are treated as 'default').
if (!columns.includes('group_id')) {
  db.exec('ALTER TABLE positions ADD COLUMN group_id TEXT');
}

db.exec(`
  CREATE INDEX IF NOT EXISTS idx_positions_group_time
    ON positions (group_id, recorded_at DESC);

  -- Device registry: type (admin/member) is managed only from the admin site.
  CREATE TABLE IF NOT EXISTS devices (
    device_id  TEXT PRIMARY KEY,
    group_id   TEXT,
    label      TEXT,
    type       TEXT NOT NULL DEFAULT 'member',
    first_seen INTEGER,
    last_seen  INTEGER
  );

  -- Groups (group_id is the SHA-256 of the code). Admin can give a friendly name.
  CREATE TABLE IF NOT EXISTS groups (
    group_id   TEXT PRIMARY KEY,
    name       TEXT,
    created_at INTEGER
  );
`);

// Consent flag: has this device's owner opted in to emergency audio?
const deviceCols = db.prepare('PRAGMA table_info(devices)').all().map((c) => c.name);
if (!deviceCols.includes('allow_audio')) {
  db.exec('ALTER TABLE devices ADD COLUMN allow_audio INTEGER NOT NULL DEFAULT 0');
}
// Set (to the block time) when an admin removes a device: future position
// uploads from it are rejected and it stays hidden everywhere until an admin
// explicitly unblocks it — a plain DELETE isn't enough since the device's own
// tracker app would just re-register it on its next GPS fix.
if (!deviceCols.includes('blocked_at')) {
  db.exec('ALTER TABLE devices ADD COLUMN blocked_at INTEGER');
}

// Per-group opt-in: when set, members can see admin devices' positions too
// (normally hidden from members). Managed only from the admin site.
const groupCols = db.prepare('PRAGMA table_info(groups)').all().map((c) => c.name);
if (!groupCols.includes('show_admins_to_members')) {
  db.exec('ALTER TABLE groups ADD COLUMN show_admins_to_members INTEGER NOT NULL DEFAULT 0');
}
// Per-group WhatsApp group ID ("628xxx-xxx@g.us") pickup requests are sent
// to. NULL = pickup feature disabled for that group until an admin sets it.
if (!groupCols.includes('whatsapp_group_id')) {
  db.exec('ALTER TABLE groups ADD COLUMN whatsapp_group_id TEXT');
}

// "Jemput" (pickup request) log — one row per request sent to WhatsApp.
db.exec(`
  CREATE TABLE IF NOT EXISTS pickup_requests (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    group_id            TEXT,
    requester_device_id TEXT,
    requester_label     TEXT,
    -- When set, this is an SOS-style alert ABOUT another device (e.g. "no
    -- response to an emergency-audio request"), not a self pickup request.
    target_device_id    TEXT,
    target_label        TEXT,
    note                TEXT,
    lat                 REAL,
    lng                 REAL,
    status              TEXT NOT NULL DEFAULT 'sent',
    error               TEXT,
    created_at          INTEGER
  );
  CREATE INDEX IF NOT EXISTS idx_pickup_group_time
    ON pickup_requests (group_id, created_at DESC);
`);

const pickupCols = db.prepare('PRAGMA table_info(pickup_requests)').all().map((c) => c.name);
if (!pickupCols.includes('target_device_id')) {
  db.exec('ALTER TABLE pickup_requests ADD COLUMN target_device_id TEXT');
}
if (!pickupCols.includes('target_label')) {
  db.exec('ALTER TABLE pickup_requests ADD COLUMN target_label TEXT');
}
if (!pickupCols.includes('kind')) {
  // 'pickup' (self, "jemput saya"), 'sos_target' (alert about another device
  // that didn't respond to an audio request), or 'sos_self' (self-initiated
  // emergency button).
  db.exec("ALTER TABLE pickup_requests ADD COLUMN kind TEXT NOT NULL DEFAULT 'pickup'");
  db.exec("UPDATE pickup_requests SET kind = 'sos_target' WHERE target_device_id IS NOT NULL");
}

// Emergency audio requests (on-demand clips).
db.exec(`
  CREATE TABLE IF NOT EXISTS audio_requests (
    id                  TEXT PRIMARY KEY,
    group_id            TEXT,
    target_device_id    TEXT,
    requester_device_id TEXT,
    requester_label     TEXT,
    status              TEXT NOT NULL DEFAULT 'pending',
    clip_path           TEXT,
    duration_ms         INTEGER,
    created_at          INTEGER,
    updated_at          INTEGER
  );
  CREATE INDEX IF NOT EXISTS idx_audio_target_status
    ON audio_requests (target_device_id, status);
`);

// Folder for stored audio clips.
fs.mkdirSync(path.join(path.dirname(dbPath), 'audio'), { recursive: true });

// Backfill registry tables from any pre-existing positions (idempotent).
db.exec(`
  INSERT OR IGNORE INTO devices (device_id, group_id, label, type, first_seen, last_seen)
  SELECT
    p.device_id,
    COALESCE(p.group_id, 'default'),
    (SELECT label FROM positions x
       WHERE x.device_id = p.device_id AND x.label IS NOT NULL
       ORDER BY x.recorded_at DESC LIMIT 1),
    'member',
    MIN(p.recorded_at),
    MAX(p.recorded_at)
  FROM positions p
  GROUP BY p.device_id;

  INSERT OR IGNORE INTO groups (group_id, name, created_at)
  SELECT DISTINCT COALESCE(group_id, 'default'), NULL, CAST(strftime('%s','now') AS INTEGER) * 1000
  FROM positions;
`);

module.exports = db;
