'use strict';

// Emergency audio: on-demand short clips. A group member requests a clip from a
// consenting target device; the target records it (with a visible/audible
// indicator on the device) and uploads it; the requester downloads and plays it.
// Every request is logged and visible to the group for transparency.

const express = require('express');
const crypto = require('crypto');
const path = require('path');
const fs = require('fs');
const db = require('../db');

const router = express.Router();

const AUDIO_DIR = path.join(
  path.dirname(process.env.DB_PATH || path.join(__dirname, '..', 'data', 'gtracker.db')),
  'audio'
);

function groupIdFromReq(req) {
  const code = (req.get('X-Group-Code') || '').trim();
  if (!code) return 'default';
  return crypto.createHash('sha256').update(code).digest('hex');
}

const deviceStmt = db.prepare('SELECT * FROM devices WHERE device_id = ?');
function getDevice(id) {
  return id ? deviceStmt.get(id) : null;
}
function deviceGroup(dev) {
  return dev && dev.group_id ? dev.group_id : 'default';
}

// Same per-group opt-in used for position visibility: when set, members can
// also request emergency audio from admin devices (not just see their position).
const groupFlatVisibilityStmt = db.prepare(
  'SELECT show_admins_to_members FROM groups WHERE group_id = ?'
);
function groupIsFlat(groupId) {
  const row = groupFlatVisibilityStmt.get(groupId);
  return !!row && row.show_admins_to_members === 1;
}

// --- statements ---
const insertReqStmt = db.prepare(`
  INSERT INTO audio_requests
    (id, group_id, target_device_id, requester_device_id, requester_label, status, created_at, updated_at)
  VALUES (@id, @group_id, @target, @requester, @requester_label, 'pending', @now, @now)
`);
const pendingForTargetStmt = db.prepare(`
  SELECT id, requester_label, created_at FROM audio_requests
  WHERE target_device_id = ? AND status = 'pending'
  ORDER BY created_at ASC
`);
const reqByIdStmt = db.prepare('SELECT * FROM audio_requests WHERE id = ?');
const markDoneStmt = db.prepare(`
  UPDATE audio_requests SET status = 'done', clip_path = ?, duration_ms = ?, updated_at = ? WHERE id = ?
`);
const markStatusStmt = db.prepare('UPDATE audio_requests SET status = ?, updated_at = ? WHERE id = ?');
const logStmt = db.prepare(`
  SELECT r.id, r.status, r.created_at, r.updated_at, r.duration_ms,
    r.target_device_id,
    (SELECT label FROM devices d WHERE d.device_id = r.target_device_id) AS target_label,
    r.requester_device_id, r.requester_label
  FROM audio_requests r
  WHERE r.group_id = ?
  ORDER BY r.created_at DESC
  LIMIT 100
`);

// POST /api/audio/request  { targetDeviceId }
// The requester (X-Device-Id) asks a target device for an emergency clip.
router.post('/request', (req, res) => {
  const g = groupIdFromReq(req);
  const requesterId = (req.get('X-Device-Id') || '').trim();
  const targetId = (req.body && typeof req.body.targetDeviceId === 'string')
    ? req.body.targetDeviceId.trim() : '';

  if (!requesterId) return res.status(400).json({ error: 'X-Device-Id header required' });
  if (!targetId) return res.status(400).json({ error: 'targetDeviceId required' });
  if (targetId === requesterId) return res.status(400).json({ error: 'cannot request your own device' });

  const requester = getDevice(requesterId);
  const target = getDevice(targetId);
  if (!target) return res.status(404).json({ error: 'target device not found' });

  // Must be in the same group as the requester's group code.
  if (deviceGroup(target) !== g) {
    return res.status(403).json({ error: 'target is not in your group' });
  }
  // Members cannot reach admin devices, unless the group has opted in to
  // flat visibility (same rule as positions).
  const requesterIsAdmin = requester && requester.type === 'admin';
  if (!requesterIsAdmin && target.type === 'admin' && !groupIsFlat(g)) {
    return res.status(403).json({ error: 'not allowed' });
  }
  // Consent is mandatory.
  if (!target.allow_audio) {
    return res.status(403).json({ error: 'target device has not consented to emergency audio' });
  }

  const id = crypto.randomUUID();
  const now = Date.now();
  insertReqStmt.run({
    id,
    group_id: g,
    target: targetId,
    requester: requesterId,
    requester_label: requester && requester.label ? requester.label : requesterId,
    now,
  });
  res.status(201).json({ requestId: id });
});

// GET /api/audio/pending  -> pending requests targeting THIS device (X-Device-Id)
router.get('/pending', (req, res) => {
  const me = (req.get('X-Device-Id') || '').trim();
  if (!me) return res.json([]);
  res.json(pendingForTargetStmt.all(me));
});

// POST /api/audio/clip/:id  (raw audio body) -> target uploads the recorded clip
router.post('/clip/:id', express.raw({ type: '*/*', limit: '15mb' }), (req, res) => {
  const me = (req.get('X-Device-Id') || '').trim();
  const reqRow = reqByIdStmt.get(req.params.id);
  if (!reqRow) return res.status(404).json({ error: 'request not found' });
  if (reqRow.target_device_id !== me) return res.status(403).json({ error: 'not your request' });
  if (reqRow.status !== 'pending') return res.status(409).json({ error: 'request already handled' });
  if (!req.body || !req.body.length) return res.status(400).json({ error: 'empty body' });

  const duration = parseInt(req.get('X-Duration-Ms'), 10) || null;
  const filePath = path.join(AUDIO_DIR, `${reqRow.id}.m4a`);
  fs.writeFileSync(filePath, req.body);
  markDoneStmt.run(filePath, duration, Date.now(), reqRow.id);
  res.json({ ok: true });
});

// POST /api/audio/reject/:id -> target reports it can't/won't record (e.g. no consent, no permission)
router.post('/reject/:id', (req, res) => {
  const me = (req.get('X-Device-Id') || '').trim();
  const reqRow = reqByIdStmt.get(req.params.id);
  if (!reqRow) return res.status(404).json({ error: 'request not found' });
  if (reqRow.target_device_id !== me) return res.status(403).json({ error: 'not your request' });
  markStatusStmt.run('rejected', Date.now(), reqRow.id);
  res.json({ ok: true });
});

// GET /api/audio/clip/:id -> requester (or an admin in the group) downloads the clip
router.get('/clip/:id', (req, res) => {
  const g = groupIdFromReq(req);
  const me = (req.get('X-Device-Id') || '').trim();
  const reqRow = reqByIdStmt.get(req.params.id);
  if (!reqRow || reqRow.status !== 'done' || !reqRow.clip_path) {
    return res.status(404).json({ error: 'clip not ready' });
  }
  if (reqRow.group_id !== g) return res.status(403).json({ error: 'not allowed' });
  const requester = getDevice(me);
  const allowed = me === reqRow.requester_device_id || (requester && requester.type === 'admin');
  if (!allowed) return res.status(403).json({ error: 'not allowed' });
  if (!fs.existsSync(reqRow.clip_path)) return res.status(404).json({ error: 'clip missing' });

  res.setHeader('Content-Type', 'audio/mp4');
  fs.createReadStream(reqRow.clip_path).pipe(res);
});

// GET /api/audio/log -> transparency log for the group (who requested from whom)
router.get('/log', (req, res) => {
  res.json(logStmt.all(groupIdFromReq(req)));
});

module.exports = router;
