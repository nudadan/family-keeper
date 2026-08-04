'use strict';

const express = require('express');
const crypto = require('crypto');
const db = require('../db');

const router = express.Router();

// --- Group isolation ---
// A client identifies its group via the "X-Group-Code" header. We store/compare
// the SHA-256 hash so the raw code never lands in the database. No code => the
// shared "default" group (keeps older single-group data working).
function groupIdFromReq(req) {
  const code = (req.get('X-Group-Code') || '').trim();
  if (!code) return 'default';
  return crypto.createHash('sha256').update(code).digest('hex');
}

// --- Device type / visibility ---
// The requesting device identifies itself via "X-Device-Id". Members cannot see
// devices of type 'admin'; admins see everyone in their group. Unknown/missing
// requester is treated as a member (safer default).
const deviceTypeStmt = db.prepare('SELECT type FROM devices WHERE device_id = ?');
function requesterIsAdmin(req) {
  const id = (req.get('X-Device-Id') || '').trim();
  if (!id) return false;
  const row = deviceTypeStmt.get(id);
  return !!row && row.type === 'admin';
}

// --- Prepared statements. @g = group id, @admins = 1 if requester is admin. ---
const insertStmt = db.prepare(`
  INSERT INTO positions (device_id, group_id, label, lat, lng, accuracy, speed, recorded_at, received_at)
  VALUES (@device_id, @group_id, @label, @lat, @lng, @accuracy, @speed, @recorded_at, @received_at)
`);

const ensureGroupStmt = db.prepare(
  'INSERT OR IGNORE INTO groups (group_id, name, created_at) VALUES (?, NULL, ?)'
);

// Upsert the device registry; never overwrite the admin-managed "type".
// allow_audio (emergency-audio consent) is owner-controlled from the app.
const upsertDeviceStmt = db.prepare(`
  INSERT INTO devices (device_id, group_id, label, type, allow_audio, first_seen, last_seen)
  VALUES (@device_id, @group_id, @label, 'member', @allow_audio, @now, @now)
  ON CONFLICT(device_id) DO UPDATE SET
    group_id    = excluded.group_id,
    label       = COALESCE(excluded.label, devices.label),
    allow_audio = excluded.allow_audio,
    last_seen   = excluded.last_seen
`);

// "AND visible" predicate used by all read queries.
const VISIBLE = "(@admins = 1 OR COALESCE(dev.type, 'member') <> 'admin')";

const latestPerDeviceStmt = db.prepare(`
  SELECT p.*
  FROM positions p
  JOIN (
    SELECT device_id, MAX(recorded_at) AS max_time
    FROM positions
    WHERE COALESCE(group_id, 'default') = @g
    GROUP BY device_id
  ) m ON p.device_id = m.device_id AND p.recorded_at = m.max_time
  LEFT JOIN devices dev ON dev.device_id = p.device_id
  WHERE COALESCE(p.group_id, 'default') = @g AND ${VISIBLE}
  GROUP BY p.device_id
  ORDER BY p.device_id
`);

const latestForDeviceStmt = db.prepare(`
  SELECT p.*
  FROM positions p
  LEFT JOIN devices dev ON dev.device_id = p.device_id
  WHERE p.device_id = @d AND COALESCE(p.group_id, 'default') = @g AND ${VISIBLE}
  ORDER BY p.recorded_at DESC
  LIMIT 1
`);

const historyStmt = db.prepare(`
  SELECT p.*
  FROM positions p
  LEFT JOIN devices dev ON dev.device_id = p.device_id
  WHERE p.device_id = @d AND COALESCE(p.group_id, 'default') = @g AND ${VISIBLE}
  ORDER BY p.recorded_at DESC
  LIMIT @lim
`);

const devicesStmt = db.prepare(`
  SELECT
    p.device_id,
    COUNT(*) AS count,
    MAX(p.recorded_at) AS last_seen,
    (SELECT label FROM positions x
       WHERE x.device_id = p.device_id
         AND COALESCE(x.group_id, 'default') = @g
         AND x.label IS NOT NULL
       ORDER BY x.recorded_at DESC LIMIT 1) AS label
  FROM positions p
  LEFT JOIN devices dev ON dev.device_id = p.device_id
  WHERE COALESCE(p.group_id, 'default') = @g AND ${VISIBLE}
  GROUP BY p.device_id
  ORDER BY last_seen DESC
`);

const tracksStmt = db.prepare(`
  SELECT p.device_id, p.label, p.lat, p.lng, p.accuracy, p.speed, p.recorded_at
  FROM positions p
  LEFT JOIN devices dev ON dev.device_id = p.device_id
  WHERE p.recorded_at >= @since AND COALESCE(p.group_id, 'default') = @g AND ${VISIBLE}
  ORDER BY p.device_id, p.recorded_at ASC
`);

const tracksForDeviceStmt = db.prepare(`
  SELECT p.device_id, p.label, p.lat, p.lng, p.accuracy, p.speed, p.recorded_at
  FROM positions p
  LEFT JOIN devices dev ON dev.device_id = p.device_id
  WHERE p.device_id = @d AND p.recorded_at >= @since
    AND COALESCE(p.group_id, 'default') = @g AND ${VISIBLE}
  ORDER BY p.recorded_at ASC
`);

function toNumber(value) {
  if (value === undefined || value === null || value === '') return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : NaN;
}

// POST /api/positions  -> store a new GPS fix (used by the Tracker app)
router.post('/', (req, res) => {
  const body = req.body || {};

  const deviceId = typeof body.deviceId === 'string' ? body.deviceId.trim() : '';
  const label = typeof body.label === 'string' && body.label.trim() !== ''
    ? body.label.trim()
    : null;
  const lat = toNumber(body.lat);
  const lng = toNumber(body.lng);
  const accuracy = toNumber(body.accuracy);
  const speed = toNumber(body.speed);
  const recordedAt = body.timestamp !== undefined ? toNumber(body.timestamp) : Date.now();

  if (!deviceId) {
    return res.status(400).json({ error: 'deviceId is required' });
  }
  if (lat === null || Number.isNaN(lat) || lat < -90 || lat > 90) {
    return res.status(400).json({ error: 'lat must be a number between -90 and 90' });
  }
  if (lng === null || Number.isNaN(lng) || lng < -180 || lng > 180) {
    return res.status(400).json({ error: 'lng must be a number between -180 and 180' });
  }
  if (Number.isNaN(accuracy) || Number.isNaN(speed) || Number.isNaN(recordedAt)) {
    return res.status(400).json({ error: 'accuracy, speed and timestamp must be numbers when provided' });
  }

  const groupId = groupIdFromReq(req);
  const now = Date.now();
  const allowAudio = (body.allowAudio === true || body.allowAudio === 1) ? 1 : 0;

  ensureGroupStmt.run(groupId, now);
  upsertDeviceStmt.run({ device_id: deviceId, group_id: groupId, label, allow_audio: allowAudio, now });

  const info = insertStmt.run({
    device_id: deviceId,
    group_id: groupId,
    label: label,
    lat,
    lng,
    accuracy: accuracy,
    speed: speed,
    recorded_at: recordedAt || now,
    received_at: now,
  });

  res.status(201).json({ id: info.lastInsertRowid, ok: true });
});

// GET /api/positions/latest -> latest fix for every visible device in the group
router.get('/latest', (req, res) => {
  const g = groupIdFromReq(req);
  const admins = requesterIsAdmin(req) ? 1 : 0;
  const deviceId = req.query.deviceId;
  if (deviceId) {
    const row = latestForDeviceStmt.get({ d: String(deviceId), g, admins });
    return res.json(row || null);
  }
  res.json(latestPerDeviceStmt.all({ g, admins }));
});

// GET /api/positions?deviceId=x&limit=100 -> history (newest first), scoped
router.get('/', (req, res) => {
  const deviceId = req.query.deviceId;
  if (!deviceId) {
    return res.status(400).json({ error: 'deviceId query parameter is required' });
  }
  let limit = parseInt(req.query.limit, 10);
  if (!Number.isFinite(limit) || limit <= 0) limit = 100;
  if (limit > 1000) limit = 1000;

  res.json(historyStmt.all({
    d: String(deviceId),
    g: groupIdFromReq(req),
    admins: requesterIsAdmin(req) ? 1 : 0,
    lim: limit,
  }));
});

// GET /api/positions/devices -> known visible devices in the group
router.get('/devices', (req, res) => {
  res.json(devicesStmt.all({
    g: groupIdFromReq(req),
    admins: requesterIsAdmin(req) ? 1 : 0,
  }));
});

// GET /api/positions/tracks?hours=12 -> tracks (polyline) for the group
router.get('/tracks', (req, res) => {
  let since = parseInt(req.query.since, 10);
  if (!Number.isFinite(since)) {
    let hours = parseFloat(req.query.hours);
    if (!Number.isFinite(hours) || hours <= 0) hours = 12;
    if (hours > 168) hours = 168; // cap at 7 days
    since = Date.now() - Math.round(hours * 3600 * 1000);
  }

  const g = groupIdFromReq(req);
  const admins = requesterIsAdmin(req) ? 1 : 0;
  const deviceId = req.query.deviceId;
  const rows = deviceId
    ? tracksForDeviceStmt.all({ d: String(deviceId), since, g, admins })
    : tracksStmt.all({ since, g, admins });

  const byDevice = new Map();
  for (const r of rows) {
    if (!byDevice.has(r.device_id)) {
      byDevice.set(r.device_id, { label: null, points: [] });
    }
    const entry = byDevice.get(r.device_id);
    if (r.label) entry.label = r.label;
    entry.points.push({
      lat: r.lat,
      lng: r.lng,
      accuracy: r.accuracy,
      speed: r.speed,
      recordedAt: r.recorded_at,
    });
  }

  const tracks = Array.from(byDevice.entries()).map(([id, entry]) => ({
    deviceId: id,
    label: entry.label,
    points: entry.points,
  }));

  res.json({ since, tracks });
});

module.exports = router;
