'use strict';

// Shared WhatsHub relay helper — the app_id/secret_key never reach any
// client, only server-side callers (pickup requests, geofence alerts, ...).

const WHATSHUB_BASE_URL = process.env.WHATSHUB_BASE_URL || 'https://whatshub.noesantara.online';
const WHATSHUB_APP_ID = process.env.WHATSHUB_APP_ID || '';
const WHATSHUB_SECRET_KEY = process.env.WHATSHUB_SECRET_KEY || '';

function whatshubConfigured() {
  return !!(WHATSHUB_APP_ID && WHATSHUB_SECRET_KEY);
}

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

module.exports = { whatshubConfigured, sendWhatsHubMessage, wibTime };
