'use strict';

// "Jemput" (pickup request): a family member asks to be picked up. The server
// relays this as a WhatsApp message (via the WhatsHub API) to the group's
// configured WhatsApp group — the WhatsHub app_id/secret_key never reach the
// client. Every request is logged for transparency (visible in the admin site).

const express = require('express');
const crypto = require('crypto');
const db = require('../db');

const router = express.Router();

const WHATSHUB_BASE_URL = process.env.WHATSHUB_BASE_URL || 'https://whatshub.noesantara.online';
const WHATSHUB_APP_ID = process.env.WHATSHUB_APP_ID || '';
const WHATSHUB_SECRET_KEY = process.env.WHATSHUB_SECRET_KEY || '';

// Requests from the same device more often than this are rejected, to avoid
// accidental double-taps and WhatsApp message-quota / anti-ban issues.
const COOLDOWN_MS = 60_000;

function groupIdFromReq(req) {
  const code = (req.get('X-Group-Code') || '').trim();
  if (!code) return 'default';
  return crypto.createHash('sha256').update(code).digest('hex');
}

const getGroupStmt = db.prepare('SELECT group_id, name, whatsapp_group_id FROM groups WHERE group_id = ?');
const getDeviceStmt = db.prepare('SELECT label FROM devices WHERE device_id = ?');
const getLatestPositionStmt = db.prepare(
  'SELECT lat, lng, recorded_at FROM positions WHERE device_id = ? ORDER BY recorded_at DESC LIMIT 1'
);
const lastRequestStmt = db.prepare(
  'SELECT created_at FROM pickup_requests WHERE requester_device_id = ? ORDER BY created_at DESC LIMIT 1'
);
const insertRequestStmt = db.prepare(`
  INSERT INTO pickup_requests
    (group_id, requester_device_id, requester_label, note, lat, lng, status, error, created_at)
  VALUES (@group_id, @requester_device_id, @requester_label, @note, @lat, @lng, @status, @error, @created_at)
`);
const logStmt = db.prepare(`
  SELECT id, requester_label, note, status, error, created_at
  FROM pickup_requests
  WHERE group_id = ?
  ORDER BY created_at DESC
  LIMIT 100
`);

async function sendWhatsHubMessage(payload) {
  const res = await fetch(`${WHATSHUB_BASE_URL}/api/v1/messages/send`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      app_id: WHATSHUB_APP_ID,
      secret_key: WHATSHUB_SECRET_KEY,
      ...payload,
    }),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok || data.success === false) {
    const msg = data.error || `WhatsHub HTTP ${res.status}`;
    throw new Error(msg);
  }
  return data;
}

// POST /api/pickup/request  { note?: string }
router.post('/request', async (req, res) => {
  if (!WHATSHUB_APP_ID || !WHATSHUB_SECRET_KEY) {
    return res.status(500).json({ error: 'WhatsHub belum dikonfigurasi di server (.env)' });
  }

  const requesterId = (req.get('X-Device-Id') || '').trim();
  if (!requesterId) {
    return res.status(400).json({ error: 'X-Device-Id header required' });
  }

  const note = typeof req.body?.note === 'string' ? req.body.note.trim().slice(0, 300) : '';

  const g = groupIdFromReq(req);
  const group = getGroupStmt.get(g);
  if (!group || !group.whatsapp_group_id) {
    return res.status(400).json({ error: 'Fitur Jemput belum diatur untuk grup ini. Hubungi admin.' });
  }

  const last = lastRequestStmt.get(requesterId);
  if (last && Date.now() - last.created_at < COOLDOWN_MS) {
    const waitSec = Math.ceil((COOLDOWN_MS - (Date.now() - last.created_at)) / 1000);
    return res.status(429).json({ error: `Tunggu ${waitSec} detik sebelum mengirim permintaan lagi.` });
  }

  const device = getDeviceStmt.get(requesterId);
  const label = (device && device.label) || requesterId;
  const position = getLatestPositionStmt.get(requesterId);

  const now = Date.now();
  const timeStr = new Intl.DateTimeFormat('id-ID', {
    timeZone: 'Asia/Jakarta', hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(new Date(now));

  let text = `🚗 *Permintaan Jemput*\n\n*${label}* minta dijemput.\n🕒 ${timeStr} WIB`;
  if (note) text += `\n📝 ${note}`;
  if (!position) text += '\n\n(Lokasi belum tersedia)';

  let status = 'sent';
  let error = null;
  try {
    await sendWhatsHubMessage({
      to: group.whatsapp_group_id,
      content: { text },
    });
    if (position) {
      await sendWhatsHubMessage({
        to: group.whatsapp_group_id,
        type: 'location',
        location: {
          latitude: position.lat,
          longitude: position.lng,
          title: `Lokasi ${label}`,
        },
      });
    }
  } catch (err) {
    status = 'failed';
    error = String(err.message || err).slice(0, 300);
  }

  insertRequestStmt.run({
    group_id: g,
    requester_device_id: requesterId,
    requester_label: label,
    note: note || null,
    lat: position ? position.lat : null,
    lng: position ? position.lng : null,
    status,
    error,
    created_at: now,
  });

  if (status === 'failed') {
    return res.status(502).json({ error: `Gagal mengirim ke WhatsApp: ${error}` });
  }
  res.status(201).json({ ok: true });
});

// GET /api/pickup/log -> recent pickup requests for the group (used by admin.js too)
router.get('/log', (req, res) => {
  res.json(logStmt.all(groupIdFromReq(req)));
});

module.exports = router;
