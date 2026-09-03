'use strict';

// "Jemput" (pickup request) + emergency "no audio response" SOS alert. Both
// are relayed as WhatsApp messages (via the WhatsHub API) to the group's
// configured WhatsApp group — the WhatsHub app_id/secret_key never reach the
// client. Every message is logged for transparency (visible in the admin site).

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

const getGroupStmt = db.prepare(
  'SELECT group_id, name, whatsapp_group_id, show_admins_to_members FROM groups WHERE group_id = ?'
);
const getDeviceStmt = db.prepare('SELECT label, type, group_id FROM devices WHERE device_id = ?');
const getLatestPositionStmt = db.prepare(
  'SELECT lat, lng, recorded_at FROM positions WHERE device_id = ? ORDER BY recorded_at DESC LIMIT 1'
);
const lastRequestStmt = db.prepare(
  'SELECT created_at FROM pickup_requests WHERE requester_device_id = ? ORDER BY created_at DESC LIMIT 1'
);
const insertRequestStmt = db.prepare(`
  INSERT INTO pickup_requests
    (group_id, requester_device_id, requester_label, target_device_id, target_label,
     note, lat, lng, status, error, created_at, kind)
  VALUES (@group_id, @requester_device_id, @requester_label, @target_device_id, @target_label,
     @note, @lat, @lng, @status, @error, @created_at, @kind)
`);
const logStmt = db.prepare(`
  SELECT id, requester_label, target_label, note, status, error, created_at, kind
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

function wibTime(ms) {
  return new Intl.DateTimeFormat('id-ID', {
    timeZone: 'Asia/Jakarta', hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(new Date(ms));
}

// POST /api/pickup/request  { note?: string, targetDeviceId?: string, kind?: string }
//
// Without targetDeviceId: a self pickup request ("I need a ride"), or — when
// kind === 'sos' — a self-initiated emergency alert ("I need help").
// With targetDeviceId: an SOS-style alert about ANOTHER device — used when an
// emergency-audio request to that device times out, so the family still gets
// an actionable notification + last-known location even if audio fails.
router.post('/request', async (req, res) => {
  if (!WHATSHUB_APP_ID || !WHATSHUB_SECRET_KEY) {
    return res.status(500).json({ error: 'WhatsHub belum dikonfigurasi di server (.env)' });
  }

  const requesterId = (req.get('X-Device-Id') || '').trim();
  if (!requesterId) {
    return res.status(400).json({ error: 'X-Device-Id header required' });
  }

  const note = typeof req.body?.note === 'string' ? req.body.note.trim().slice(0, 300) : '';
  const targetId = typeof req.body?.targetDeviceId === 'string' ? req.body.targetDeviceId.trim() : '';
  const isSelfSos = !targetId && req.body?.kind === 'sos';

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

  const requester = getDeviceStmt.get(requesterId);
  const requesterLabel = (requester && requester.label) || requesterId;

  let targetDevice = null;
  if (targetId) {
    targetDevice = getDeviceStmt.get(targetId);
    if (!targetDevice) return res.status(404).json({ error: 'Target device not found' });
    if ((targetDevice.group_id || 'default') !== g) {
      return res.status(403).json({ error: 'Target is not in your group' });
    }
    const requesterIsAdmin = requester && requester.type === 'admin';
    if (!requesterIsAdmin && targetDevice.type === 'admin' && !group.show_admins_to_members) {
      return res.status(403).json({ error: 'Not allowed' });
    }
  }

  const subjectId = targetId || requesterId;
  const subjectLabel = targetId ? ((targetDevice && targetDevice.label) || targetId) : requesterLabel;
  const position = getLatestPositionStmt.get(subjectId);

  const now = Date.now();
  const timeStr = wibTime(now);

  let text;
  if (targetId) {
    text = `⚠️ *Peringatan Darurat*\n\n*${requesterLabel}* tidak mendapat respons audio darurat dari ` +
      `*${subjectLabel}*.\n🕒 ${timeStr} WIB`;
    if (note) text += `\n📝 ${note}`;
    text += position
      ? '\n\nLokasi terakhir di bawah ini 👇'
      : '\n\n(Lokasi terakhir belum tersedia)';
  } else if (isSelfSos) {
    text = `🆘 *DARURAT — Butuh Bantuan*\n\n*${requesterLabel}* menekan tombol SOS dan butuh bantuan segera!` +
      `\n🕒 ${timeStr} WIB`;
    if (note) text += `\n📝 ${note}`;
    text += position
      ? '\n\nLokasi terkini di bawah ini 👇'
      : '\n\n(Lokasi belum tersedia)';
  } else {
    text = `🚗 *Permintaan Jemput*\n\n*${requesterLabel}* minta dijemput.\n🕒 ${timeStr} WIB`;
    if (note) text += `\n📝 ${note}`;
    if (!position) text += '\n\n(Lokasi belum tersedia)';
  }

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
          title: `Lokasi ${subjectLabel}`,
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
    requester_label: requesterLabel,
    target_device_id: targetId || null,
    target_label: targetId ? subjectLabel : null,
    note: note || null,
    lat: position ? position.lat : null,
    lng: position ? position.lng : null,
    status,
    error,
    created_at: now,
    kind: targetId ? 'sos_target' : isSelfSos ? 'sos_self' : 'pickup',
  });

  if (status === 'failed') {
    return res.status(502).json({ error: `Gagal mengirim ke WhatsApp: ${error}` });
  }
  res.status(201).json({ ok: true });
});

// GET /api/pickup/log -> recent pickup/alert requests for the group (used by admin.js too)
router.get('/log', (req, res) => {
  res.json(logStmt.all(groupIdFromReq(req)));
});

module.exports = router;
