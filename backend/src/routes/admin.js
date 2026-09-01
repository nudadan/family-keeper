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

// Displayed in the server's local timezone (Asia/Jakarta / WIB), not UTC.
const timeFormatter = new Intl.DateTimeFormat('id-ID', {
  timeZone: 'Asia/Jakarta',
  year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit',
  hour12: false,
});

function fmtTime(ms) {
  if (!ms) return '-';
  const parts = timeFormatter.formatToParts(new Date(ms));
  const get = (t) => parts.find((p) => p.type === t)?.value;
  return `${get('year')}-${get('month')}-${get('day')} ${get('hour')}:${get('minute')}:${get('second')} WIB`;
}

/** "3 menit lalu" style relative time, in Indonesian, for the "Last seen" column. */
function timeAgo(ms) {
  if (!ms) return '';
  const sec = Math.max(0, Math.floor((Date.now() - ms) / 1000));
  if (sec < 60) return 'baru saja';
  if (sec < 3600) return `${Math.floor(sec / 60)} menit lalu`;
  if (sec < 86400) return `${Math.floor(sec / 3600)} jam lalu`;
  return `${Math.floor(sec / 86400)} hari lalu`;
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

// --- Small inline icon set (stroke-based, currentColor) — no external assets. ---
const icon = {
  groups: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>',
  devices: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="5" y="2" width="14" height="20" rx="2"/><line x1="12" y1="18" x2="12.01" y2="18"/></svg>',
  audio: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2"/><line x1="12" y1="19" x2="12" y2="23"/></svg>',
  logout: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg>',
  check: '<svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>',
};

function typeBadge(type) {
  const isAdmin = type === 'admin';
  return `<span class="badge ${isAdmin ? 'badge-admin' : 'badge-member'}">${isAdmin ? 'Admin' : 'Member'}</span>`;
}

function consentBadge(allow) {
  return allow
    ? `<span class="badge badge-yes">${icon.check} Ya</span>`
    : `<span class="badge badge-muted">Tidak</span>`;
}

function statusBadge(status) {
  const map = {
    done: ['badge-yes', 'Selesai'],
    pending: ['badge-pending', 'Menunggu'],
    rejected: ['badge-no', 'Ditolak'],
  };
  const [cls, label] = map[status] || ['badge-muted', status];
  return `<span class="badge ${cls}">${esc(label)}</span>`;
}

function emptyRow(colspan, text) {
  return `<tr><td colspan="${colspan}" class="empty-state">${esc(text)}</td></tr>`;
}

const page = (groups, devices, audio, base) => {
  const adminCount = devices.filter((d) => d.type === 'admin').length;
  const memberCount = devices.length - adminCount;
  const consentCount = devices.filter((d) => d.allow_audio).length;
  const pendingCount = audio.filter((a) => a.status === 'pending').length;

  return `<!doctype html>
<html lang="id"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<base href="${base}/">
<title>Gardenia-1 · Admin</title>
<style>
  :root {
    --primary: #1565c0;
    --primary-dark: #0d47a1;
    --primary-light: #e3f0ff;
    --bg: #f2f4f8;
    --surface: #ffffff;
    --border: #e6e9ef;
    --text: #14171f;
    --text-muted: #6b7280;
    --green-bg: #e3f7e9; --green-fg: #1b7a3d;
    --amber-bg: #fff4dd; --amber-fg: #8a5a00;
    --red-bg: #fde8e8;  --red-fg: #b3231c;
    --blue-bg: #e6efff; --blue-fg: #1e50c8;
    --grey-bg: #eef0f3;  --grey-fg: #5b6270;
    --radius: 14px;
    --shadow: 0 1px 2px rgba(20,23,31,.04), 0 4px 14px rgba(20,23,31,.06);
  }
  * { box-sizing: border-box; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    margin: 0; background: var(--bg); color: var(--text); -webkit-font-smoothing: antialiased;
  }
  a { color: var(--primary); }

  /* --- Top bar --- */
  .topbar {
    position: sticky; top: 0; z-index: 20;
    background: linear-gradient(135deg, var(--primary), var(--primary-dark));
    color: #fff; box-shadow: var(--shadow);
  }
  .topbar-inner {
    max-width: 1100px; margin: 0 auto; padding: 0 20px;
    display: flex; align-items: center; gap: 14px; height: 60px;
  }
  .brand { display: flex; align-items: center; gap: 10px; font-weight: 700; font-size: 16px; white-space: nowrap; }
  .brand-mark {
    width: 32px; height: 32px; border-radius: 9px; background: rgba(255,255,255,.18);
    display: flex; align-items: center; justify-content: center; font-weight: 800; font-size: 15px;
  }
  .brand-sub { font-weight: 400; font-size: 11px; opacity: .8; display: block; margin-top: -2px; }
  nav.topnav { display: flex; gap: 4px; margin-left: auto; overflow-x: auto; }
  nav.topnav a {
    color: rgba(255,255,255,.88); text-decoration: none; font-size: 13.5px; font-weight: 600;
    padding: 8px 12px; border-radius: 8px; white-space: nowrap; display: flex; align-items: center; gap: 6px;
  }
  nav.topnav a:hover { background: rgba(255,255,255,.14); color: #fff; }
  .signed-in { font-size: 12px; opacity: .85; display: flex; align-items: center; gap: 6px; white-space: nowrap; }

  main { max-width: 1100px; margin: 0 auto; padding: 20px; }

  /* --- Stat cards --- */
  .stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 12px; margin-bottom: 28px; }
  .stat-card {
    background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius);
    padding: 14px 16px; box-shadow: var(--shadow);
  }
  .stat-value { font-size: 24px; font-weight: 800; line-height: 1.2; }
  .stat-label { font-size: 12.5px; color: var(--text-muted); margin-top: 2px; font-weight: 500; }

  /* --- Panels --- */
  .panel {
    background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius);
    box-shadow: var(--shadow); margin-bottom: 24px; overflow: hidden; scroll-margin-top: 76px;
  }
  .panel-head {
    display: flex; align-items: center; gap: 10px; padding: 16px 18px; border-bottom: 1px solid var(--border);
  }
  .panel-head .icon-badge {
    width: 30px; height: 30px; border-radius: 8px; background: var(--primary-light); color: var(--primary);
    display: flex; align-items: center; justify-content: center; flex-shrink: 0;
  }
  .panel-head h2 { margin: 0; font-size: 15.5px; font-weight: 700; }
  .panel-head .count { margin-left: auto; font-size: 12.5px; color: var(--text-muted); font-weight: 600; }

  /* --- Tables --- */
  .table-wrap { overflow-x: auto; }
  table { width: 100%; border-collapse: collapse; min-width: 560px; }
  th, td { text-align: left; padding: 11px 14px; font-size: 13.5px; vertical-align: middle; white-space: nowrap; }
  th {
    font-size: 11.5px; text-transform: uppercase; letter-spacing: .04em; color: var(--text-muted);
    background: #fafbfc; font-weight: 700; border-bottom: 1px solid var(--border);
  }
  tbody tr { border-bottom: 1px solid var(--border); }
  tbody tr:last-child { border-bottom: none; }
  tbody tr:hover { background: #fafbfd; }
  td.wrap { white-space: normal; word-break: break-all; }
  .muted { color: var(--text-muted); }
  .mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12px; color: var(--text-muted); }
  .cell-primary { font-weight: 600; }
  .sub { font-size: 11.5px; color: var(--text-muted); margin-top: 1px; }

  .empty-state { text-align: center; color: var(--text-muted); padding: 28px 14px; font-size: 13.5px; white-space: normal; }

  /* --- Badges --- */
  .badge {
    display: inline-flex; align-items: center; gap: 4px; padding: 3px 9px; border-radius: 999px;
    font-size: 11.5px; font-weight: 700; white-space: nowrap;
  }
  .badge-admin { background: var(--red-bg); color: var(--red-fg); }
  .badge-member { background: var(--blue-bg); color: var(--blue-fg); }
  .badge-yes { background: var(--green-bg); color: var(--green-fg); }
  .badge-no { background: var(--red-bg); color: var(--red-fg); }
  .badge-pending { background: var(--amber-bg); color: var(--amber-fg); }
  .badge-muted { background: var(--grey-bg); color: var(--grey-fg); }

  /* --- Buttons & forms --- */
  form.inline { display: inline; margin: 0; }
  .actions { display: flex; gap: 6px; flex-wrap: nowrap; }
  button, .btn {
    cursor: pointer; font-size: 12.5px; font-weight: 600; border-radius: 8px; padding: 7px 11px;
    border: 1px solid var(--border); background: var(--surface); color: var(--text); white-space: nowrap;
    transition: filter .12s ease;
  }
  button:hover, .btn:hover { filter: brightness(0.97); }
  button:active, .btn:active { filter: brightness(0.93); }
  .btn-primary { background: var(--primary); border-color: var(--primary); color: #fff; }
  .btn-danger { background: var(--surface); border-color: var(--red-bg); color: var(--red-fg); }
  .btn-danger:hover { background: var(--red-bg); }
  .rename-form { display: flex; gap: 6px; }
  input[type=text] {
    padding: 7px 10px; border-radius: 8px; border: 1px solid var(--border); font-size: 13px;
    background: #fbfbfc; min-width: 120px;
  }
  input[type=text]:focus { outline: 2px solid var(--primary-light); border-color: var(--primary); }

  /* --- Search toolbar --- */
  .toolbar { padding: 12px 18px; border-bottom: 1px solid var(--border); }
  .search-input {
    width: 100%; padding: 9px 12px; border-radius: 9px; border: 1px solid var(--border);
    font-size: 13.5px; background: #fbfbfc; font-family: inherit;
  }
  .search-input:focus { outline: 2px solid var(--primary-light); border-color: var(--primary); }

  /* --- Toast --- */
  .toast {
    position: fixed; left: 50%; bottom: 22px; transform: translate(-50%, 12px);
    background: var(--text); color: #fff; padding: 10px 18px; border-radius: 10px; font-size: 13.5px;
    font-weight: 600; box-shadow: var(--shadow); opacity: 0; transition: opacity .22s, transform .22s;
    z-index: 300; pointer-events: none; max-width: 90vw; text-align: center;
  }
  .toast.show { opacity: 1; transform: translate(-50%, 0); }
  .toast.success { background: var(--green-fg); }
  .toast.error { background: var(--red-fg); }

  /* --- Confirm modal --- */
  .modal-overlay {
    position: fixed; inset: 0; background: rgba(20,23,31,.45); display: flex;
    align-items: center; justify-content: center; z-index: 250; opacity: 0;
    transition: opacity .16s; padding: 20px;
  }
  .modal-overlay.show { opacity: 1; }
  .modal {
    background: var(--surface); border-radius: var(--radius); padding: 20px; max-width: 340px;
    width: 100%; box-shadow: 0 20px 60px rgba(0,0,0,.25); transform: scale(.96); transition: transform .16s;
  }
  .modal-overlay.show .modal { transform: scale(1); }
  .modal p { margin: 0 0 16px; font-size: 14px; line-height: 1.5; }
  .modal-actions { display: flex; justify-content: flex-end; gap: 8px; }
  tr.row-removing { opacity: 0; transform: translateX(8px); transition: opacity .2s, transform .2s; }

  footer { text-align: center; color: var(--text-muted); font-size: 12px; padding: 24px 20px 40px; }

  @media (max-width: 640px) {
    .topbar-inner { height: 54px; gap: 8px; }
    .brand-sub { display: none; }
    nav.topnav a span.label { display: none; }
    .signed-in span.label { display: none; }
    main { padding: 14px; }
    .panel-head { padding: 13px 14px; }
    th, td { padding: 9px 10px; }
  }
</style></head><body>

<div class="topbar">
  <div class="topbar-inner">
    <div class="brand">
      <div class="brand-mark">G1</div>
      <div>
        Gardenia-1
        <span class="brand-sub">Admin Dashboard</span>
      </div>
    </div>
    <nav class="topnav">
      <a href="#groups">${icon.groups}<span class="label">Grup</span></a>
      <a href="#devices">${icon.devices}<span class="label">Perangkat</span></a>
      <a href="#audio">${icon.audio}<span class="label">Audio Darurat</span></a>
    </nav>
    <div class="signed-in">${icon.logout}<span class="label">${esc(ADMIN_USER)}</span></div>
  </div>
</div>

<main>
  <div class="stats">
    <div class="stat-card"><div class="stat-value" id="stat-groups">${groups.length}</div><div class="stat-label">Grup</div></div>
    <div class="stat-card"><div class="stat-value" id="stat-devices">${devices.length}</div><div class="stat-label">Perangkat</div></div>
    <div class="stat-card"><div class="stat-value" id="stat-admin">${adminCount}</div><div class="stat-label">Admin</div></div>
    <div class="stat-card"><div class="stat-value" id="stat-member">${memberCount}</div><div class="stat-label">Member</div></div>
    <div class="stat-card"><div class="stat-value" id="stat-consent">${consentCount}</div><div class="stat-label">Izin audio aktif</div></div>
    <div class="stat-card"><div class="stat-value" id="stat-pending">${pendingCount}</div><div class="stat-label">Permintaan pending</div></div>
  </div>

  <section id="groups" class="panel">
    <div class="panel-head">
      <span class="icon-badge">${icon.groups}</span>
      <h2>Grup</h2>
      <span class="count" id="groups-count">${groups.length} grup</span>
    </div>
    <div class="table-wrap">
      <table>
        <thead><tr><th>Nama</th><th>Group ID (hash)</th><th>Perangkat</th><th>Ganti nama</th></tr></thead>
        <tbody>
        ${groups.length === 0 ? emptyRow(4, 'Belum ada grup.') : groups.map((g) => `<tr>
          <td class="cell-primary">${g.name ? esc(g.name) : '<span class="muted">(belum diberi nama)</span>'}</td>
          <td class="wrap"><span class="mono">${esc(g.group_id)}</span></td>
          <td>${g.device_count}</td>
          <td>
            <form class="rename-form js-rename" method="post" action="group/${encodeURIComponent(g.group_id)}/name">
              <input type="text" name="name" value="${esc(g.name)}" placeholder="Nama grup">
              <button type="submit" class="btn-primary">Simpan</button>
            </form>
          </td>
        </tr>`).join('')}
        </tbody>
      </table>
    </div>
  </section>

  <section id="devices" class="panel">
    <div class="panel-head">
      <span class="icon-badge">${icon.devices}</span>
      <h2>Perangkat</h2>
      <span class="count" id="devices-count">${devices.length} perangkat</span>
    </div>
    <div class="toolbar">
      <input type="text" class="search-input" id="devices-search"
             placeholder="Cari nama, device ID, atau grup…" autocomplete="off">
    </div>
    <div class="table-wrap">
      <table>
        <thead><tr><th>Nama</th><th>Device ID</th><th>Grup</th><th>Tipe</th><th>Audio</th><th>Terakhir aktif</th><th>Aksi</th></tr></thead>
        <tbody id="devices-tbody">
        ${devices.length === 0 ? emptyRow(7, 'Belum ada perangkat terdaftar.') : devices.map((d) => {
          const name = d.label || d.device_id;
          const searchKey = esc(`${d.label || ''} ${d.device_id} ${d.group_name || ''}`.toLowerCase());
          return `<tr data-device-row data-search="${searchKey}" data-name="${esc(name)}">
          <td class="cell-primary">${d.label ? esc(d.label) : '<span class="muted">(tanpa nama)</span>'}</td>
          <td><span class="mono">${esc(d.device_id)}</span></td>
          <td>${d.group_name ? esc(d.group_name) : `<span class="mono">${esc(d.group_id || 'default')}</span>`}</td>
          <td class="type-cell">${typeBadge(d.type)}</td>
          <td class="consent-cell">${consentBadge(d.allow_audio)}</td>
          <td>${fmtTime(d.last_seen)}<div class="sub">${timeAgo(d.last_seen)}</div></td>
          <td>
            <div class="actions">
              <form class="inline js-type" method="post" action="device/${encodeURIComponent(d.device_id)}/type">
                <input type="hidden" name="type" value="${d.type === 'admin' ? 'member' : 'admin'}">
                <button type="submit">Jadikan ${d.type === 'admin' ? 'Member' : 'Admin'}</button>
              </form>
              <form class="inline js-delete" method="post" action="device/${encodeURIComponent(d.device_id)}/delete">
                <button type="submit" class="btn-danger">Hapus</button>
              </form>
            </div>
          </td>
        </tr>`;
        }).join('')}
        </tbody>
        <tbody id="devices-no-results" style="display:none">
          <tr><td colspan="7" class="empty-state">Tidak ada perangkat yang cocok dengan pencarian.</td></tr>
        </tbody>
      </table>
    </div>
  </section>

  <section id="audio" class="panel">
    <div class="panel-head">
      <span class="icon-badge">${icon.audio}</span>
      <h2>Log Audio Darurat</h2>
      <span class="count" id="audio-count">${audio.length} permintaan</span>
    </div>
    <div class="table-wrap">
      <table>
        <thead><tr><th>Waktu</th><th>Peminta</th><th>Target</th><th>Status</th></tr></thead>
        <tbody>
        ${audio.length === 0 ? emptyRow(4, 'Belum ada permintaan audio darurat.') : audio.map((a) => `<tr>
          <td>${fmtTime(a.created_at)}</td>
          <td class="cell-primary">${esc(a.requester_label)}</td>
          <td>${a.target_label ? esc(a.target_label) : `<span class="mono">${esc(a.target_device_id)}</span>`}</td>
          <td>${statusBadge(a.status)}</td>
        </tr>`).join('')}
        </tbody>
      </table>
    </div>
  </section>

  <footer>Gardenia-1 · GTracker backend admin</footer>
</main>

<script>
(function () {
  'use strict';

  function toast(msg, kind) {
    var t = document.createElement('div');
    t.className = 'toast' + (kind ? ' ' + kind : '');
    t.textContent = msg;
    document.body.appendChild(t);
    requestAnimationFrame(function () { t.classList.add('show'); });
    setTimeout(function () {
      t.classList.remove('show');
      setTimeout(function () { t.remove(); }, 250);
    }, 2600);
  }

  function confirmModal(message, confirmLabel) {
    return new Promise(function (resolve) {
      var overlay = document.createElement('div');
      overlay.className = 'modal-overlay';
      overlay.innerHTML =
        '<div class="modal">' +
          '<p></p>' +
          '<div class="modal-actions">' +
            '<button type="button" class="btn-cancel">Batal</button>' +
            '<button type="button" class="btn-danger btn-confirm"></button>' +
          '</div>' +
        '</div>';
      overlay.querySelector('.modal p').textContent = message;
      overlay.querySelector('.btn-confirm').textContent = confirmLabel || 'Hapus';
      document.body.appendChild(overlay);
      requestAnimationFrame(function () { overlay.classList.add('show'); });
      function close(result) {
        overlay.classList.remove('show');
        setTimeout(function () { overlay.remove(); }, 180);
        resolve(result);
      }
      overlay.querySelector('.btn-cancel').addEventListener('click', function () { close(false); });
      overlay.querySelector('.btn-confirm').addEventListener('click', function () { close(true); });
      overlay.addEventListener('click', function (e) { if (e.target === overlay) close(false); });
    });
  }

  function submitForm(form) {
    return fetch(form.action, {
      method: 'POST',
      body: new URLSearchParams(new FormData(form)),
    });
  }

  function deviceRows() {
    return Array.prototype.slice.call(
      document.querySelectorAll('#devices-tbody tr[data-device-row]')
    );
  }

  function ensureDevicesEmptyState() {
    var tbody = document.getElementById('devices-tbody');
    if (deviceRows().length === 0 && !tbody.querySelector('.empty-state')) {
      var tr = document.createElement('tr');
      tr.innerHTML = '<td colspan="7" class="empty-state">Belum ada perangkat terdaftar.</td>';
      tbody.appendChild(tr);
      document.getElementById('devices-no-results').style.display = 'none';
    }
  }

  function updateDeviceCounts() {
    var rows = deviceRows();
    var admin = rows.filter(function (r) { return r.querySelector('.badge-admin'); }).length;
    var consent = rows.filter(function (r) { return r.querySelector('.consent-cell .badge-yes'); }).length;
    document.getElementById('stat-devices').textContent = rows.length;
    document.getElementById('stat-admin').textContent = admin;
    document.getElementById('stat-member').textContent = rows.length - admin;
    document.getElementById('stat-consent').textContent = consent;
    document.getElementById('devices-count').textContent = rows.length + ' perangkat';
  }

  // --- Delete (with custom confirm modal) ---
  document.querySelectorAll('form.js-delete').forEach(function (form) {
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      var row = form.closest('tr');
      var name = row.getAttribute('data-name') || 'perangkat ini';
      confirmModal(
        'Hapus "' + name + '" beserta seluruh riwayat posisinya? Tindakan ini tidak bisa dibatalkan.',
        'Hapus'
      ).then(function (ok) {
        if (!ok) return;
        submitForm(form).then(function (res) {
          if (!res.ok) throw new Error('request failed');
          row.classList.add('row-removing');
          setTimeout(function () {
            row.remove();
            ensureDevicesEmptyState();
            updateDeviceCounts();
          }, 200);
          toast('"' + name + '" berhasil dihapus.', 'success');
        }).catch(function () {
          toast('Gagal menghapus perangkat. Coba lagi.', 'error');
        });
      });
    });
  });

  // --- Toggle admin/member type ---
  document.querySelectorAll('form.js-type').forEach(function (form) {
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      var input = form.querySelector('input[name=type]');
      var newType = input.value;
      submitForm(form).then(function (res) {
        if (!res.ok) throw new Error('request failed');
        var row = form.closest('tr');
        var cell = row.querySelector('.type-cell');
        cell.innerHTML = newType === 'admin'
          ? '<span class="badge badge-admin">Admin</span>'
          : '<span class="badge badge-member">Member</span>';
        input.value = newType === 'admin' ? 'member' : 'admin';
        form.querySelector('button').textContent = 'Jadikan ' + (newType === 'admin' ? 'Member' : 'Admin');
        updateDeviceCounts();
        toast('Tipe perangkat diperbarui.', 'success');
      }).catch(function () {
        toast('Gagal memperbarui tipe perangkat. Coba lagi.', 'error');
      });
    });
  });

  // --- Rename group ---
  document.querySelectorAll('form.js-rename').forEach(function (form) {
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      var input = form.querySelector('input[name=name]');
      var name = input.value.trim();
      submitForm(form).then(function (res) {
        if (!res.ok) throw new Error('request failed');
        var cell = form.closest('tr').querySelector('.cell-primary');
        if (name) {
          cell.textContent = name;
        } else {
          cell.innerHTML = '<span class="muted">(belum diberi nama)</span>';
        }
        toast('Nama grup disimpan.', 'success');
      }).catch(function () {
        toast('Gagal menyimpan nama grup. Coba lagi.', 'error');
      });
    });
  });

  // --- Live search/filter for the devices table (client-side, no reload) ---
  var searchInput = document.getElementById('devices-search');
  if (searchInput) {
    searchInput.addEventListener('input', function () {
      var q = searchInput.value.trim().toLowerCase();
      var rows = deviceRows();
      if (rows.length === 0) return; // nothing to filter (empty state already shown)
      var visible = 0;
      rows.forEach(function (row) {
        var match = !q || (row.getAttribute('data-search') || '').indexOf(q) !== -1;
        row.style.display = match ? '' : 'none';
        if (match) visible++;
      });
      document.getElementById('devices-no-results').style.display = visible === 0 ? '' : 'none';
    });
  }
})();
</script>

</body></html>`;
};

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
