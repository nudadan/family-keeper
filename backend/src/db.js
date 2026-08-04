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
