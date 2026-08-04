'use strict';

// Simple server-rendered admin site to manage devices and groups.
// Protected by HTTP Basic auth (ADMIN_USER / ADMIN_PASSWORD).

const express = require('express');
const crypto = require('crypto');
const db = require('../db');

const router = express.Router();

const ADMIN_USER = process.env.ADMIN_USER || 'admin';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || '';

function timingSafeEqual(a, b) {
  const ab = Buffer.from(a);
  const bb = Buffer.from(b);
  if (ab.length !== bb.length) return false;
  return crypto.timingSafeEqual(ab, bb);
}

function basicAuth(req, res, next) {
  if (!ADMIN_PASSWORD) {
    return res.status(500).send('ADMIN_PASSWORD is not set on the server.');
  }
  const header = req.get('Authorization') || '';
  const [scheme, encoded] = header.split(' ');
  if (scheme === 'Basic' && encoded) {
    const decoded = Buffer.from(encoded, 'base64').toString();
    const idx = decoded.indexOf(':');
    const user = decoded.slice(0, idx);
    const pass = decoded.slice(idx + 1);
    if (timingSafeEqual(user, ADMIN_USER) && timingSafeEqual(pass, ADMIN_PASSWORD)) {
      return next();
    }
  }
  res.set('WWW-Authenticate', 'Basic realm="GTracker Admin"');
  return res.status(401).send('Authentication required.');
}

router.use(basicAuth);
router.use(express.urlencoded({ extended: false }));

function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

function fmtTime(ms) {
  if (!ms) return '-';
  return new Date(ms).toISOString().replace('T', ' ').slice(0, 19);
}

const listGroupsStmt = db.prepare(`
  SELECT
    g.group_id,
    g.name,
    (SELECT COUNT(*) FROM devices d WHERE COALESCE(d.group_id, 'default') = g.group_id) AS device_count
  FROM groups g
  ORDER BY (g.name IS NULL), g.name, g.group_id
`);

const listDevicesStmt = db.prepare(`
  SELECT
    d.device_id, d.label, d.group_id, d.type, d.last_seen, d.allow_audio,
    (SELECT name FROM groups g WHERE g.group_id = COALESCE(d.group_id, 'default')) AS group_name
  FROM devices d
  ORDER BY d.last_seen DESC
`);

const listAudioStmt = db.prepare(`
  SELECT r.created_at, r.status, r.requester_label,
    (SELECT label FROM devices d WHERE d.device_id = r.target_device_id) AS target_label,
    r.target_device_id
  FROM audio_requests r
  ORDER BY r.created_at DESC
  LIMIT 100
`);

const page = (groups, devices, audio, base) => `<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<base href="${base}/">
<title>GTracker Admin</title>
<style>
  body { font-family: system-ui, sans-serif; margin: 0; background: #f4f5f7; color: #1a1a1a; }
  header { background: #1565C0; color: #fff; padding: 14px 20px; font-size: 18px; font-weight: 600; }
  main { padding: 20px; max-width: 1100px; margin: 0 auto; }
  h2 { margin-top: 28px; }
  table { width: 100%; border-collapse: collapse; background: #fff; box-shadow: 0 1px 3px rgba(0,0,0,.1); }
  th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #eee; font-size: 14px; vertical-align: middle; }
  th { background: #fafafa; }
  code { font-size: 12px; color: #555; }
  .pill { display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 12px; }
  .admin { background: #fde7e7; color: #b00020; }
  .member { background: #e7f0fd; color: #1565C0; }
  button { cursor: pointer; }
  .danger { color: #b00020; }
  form { display: inline; margin: 0; }
  input[type=text] { padding: 4px 6px; }
</style></head><body>
<header>GTracker Admin</header>
<main>
  <h2>Groups (${groups.length})</h2>
  <table><tr><th>Name</th><th>Group ID (hash)</th><th>Devices</th><th>Rename</th></tr>
  ${groups.map((g) => `<tr>
    <td>${g.name ? esc(g.name) : '<em>(unnamed)</em>'}</td>
    <td><code>${esc(g.group_id)}</code></td>
    <td>${g.device_count}</td>
    <td><form method="post" action="group/${encodeURIComponent(g.group_id)}/name">
      <input type="text" name="name" value="${esc(g.name)}" placeholder="friendly name">
      <button type="submit">Save</button>
    </form></td>
  </tr>`).join('')}
  </table>

  <h2>Devices (${devices.length})</h2>
  <table><tr><th>Name</th><th>Device ID</th><th>Group</th><th>Type</th><th>Audio consent</th><th>Last seen</th><th>Actions</th></tr>
  ${devices.map((d) => `<tr>
    <td>${d.label ? esc(d.label) : '<em>(no name)</em>'}</td>
    <td><code>${esc(d.device_id)}</code></td>
    <td>${d.group_name ? esc(d.group_name) : `<code>${esc(d.group_id || 'default')}</code>`}</td>
    <td><span class="pill ${d.type === 'admin' ? 'admin' : 'member'}">${esc(d.type)}</span></td>
    <td>${d.allow_audio ? '✅ yes' : '—'}</td>
    <td>${fmtTime(d.last_seen)}</td>
    <td>
      <form method="post" action="device/${encodeURIComponent(d.device_id)}/type">
        <input type="hidden" name="type" value="${d.type === 'admin' ? 'member' : 'admin'}">
        <button type="submit">Make ${d.type === 'admin' ? 'member' : 'admin'}</button>
      </form>
      <form method="post" action="device/${encodeURIComponent(d.device_id)}/delete"
            onsubmit="return confirm('Delete this device and all its positions?');">
        <button type="submit" class="danger">Delete</button>
      </form>
    </td>
  </tr>`).join('')}
  </table>

  <h2>Emergency audio log (${audio.length})</h2>
  <table><tr><th>When</th><th>Requester</th><th>Target</th><th>Status</th></tr>
  ${audio.map((a) => `<tr>
    <td>${fmtTime(a.created_at)}</td>
    <td>${esc(a.requester_label)}</td>
    <td>${a.target_label ? esc(a.target_label) : `<code>${esc(a.target_device_id)}</code>`}</td>
    <td>${esc(a.status)}</td>
  </tr>`).join('')}
  </table>
</main></body></html>`;

router.get('/', (req, res) => {
  res.send(page(
    listGroupsStmt.all(),
    listDevicesStmt.all(),
    listAudioStmt.all(),
    req.baseUrl || '/admin',
  ));
});

function home(req) {
  return req.baseUrl || '/admin';
}

const setTypeStmt = db.prepare('UPDATE devices SET type = ? WHERE device_id = ?');
router.post('/device/:id/type', (req, res) => {
  const type = req.body.type === 'admin' ? 'admin' : 'member';
  setTypeStmt.run(type, req.params.id);
  res.redirect(home(req));
});

const delPositionsStmt = db.prepare('DELETE FROM positions WHERE device_id = ?');
const delDeviceStmt = db.prepare('DELETE FROM devices WHERE device_id = ?');
router.post('/device/:id/delete', (req, res) => {
  const tx = db.transaction((id) => {
    delPositionsStmt.run(id);
    delDeviceStmt.run(id);
  });
  tx(req.params.id);
  res.redirect(home(req));
});

const setGroupNameStmt = db.prepare('UPDATE groups SET name = ? WHERE group_id = ?');
router.post('/group/:gid/name', (req, res) => {
  const name = (req.body.name || '').trim() || null;
  setGroupNameStmt.run(name, req.params.gid);
  res.redirect(home(req));
});

module.exports = router;
