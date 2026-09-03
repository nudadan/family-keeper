'use strict';

// "Belum lapor" watchdog: periodically scans for devices that have gone
// quiet for too long (tracker killed, phone dead, no signal, ...) and
// relays a WhatsApp alert to their group — a dead-man's-switch, since by
// definition nothing triggers this from an incoming request. Runs on a
// plain setInterval inside the single long-lived backend process; not tied
// to any HTTP request.

const db = require('./db');
const { whatshubConfigured, sendWhatsHubMessage, wibTime } = require('./whatshub');

const STALE_THRESHOLD_MS = (Number(process.env.STALE_THRESHOLD_HOURS) || 6) * 3600_000;
const CHECK_INTERVAL_MS = 15 * 60_000;

const findStaleDevicesStmt = db.prepare(`
  SELECT d.device_id, d.label, d.group_id, d.last_seen, g.whatsapp_group_id
  FROM devices d
  JOIN groups g ON g.group_id = COALESCE(d.group_id, 'default')
  WHERE d.blocked_at IS NULL
    AND d.last_seen IS NOT NULL
    AND d.stale_alert_sent_at IS NULL
    AND g.whatsapp_group_id IS NOT NULL
`);
const markAlertedStmt = db.prepare('UPDATE devices SET stale_alert_sent_at = ? WHERE device_id = ?');

function hoursAgo(ms) {
  return Math.floor(ms / 3600_000);
}

async function checkStaleDevices() {
  if (!whatshubConfigured()) return;

  const now = Date.now();
  const candidates = findStaleDevicesStmt.all()
    .filter((d) => now - d.last_seen > STALE_THRESHOLD_MS);

  for (const d of candidates) {
    const label = d.label || d.device_id;
    const text = `⏰ *Belum Lapor*\n\n*${label}* belum mengirim posisi selama ${hoursAgo(now - d.last_seen)} jam.\n` +
      `Terakhir terlihat: ${wibTime(d.last_seen)} WIB.\n\nCek apakah HP mati, tidak ada sinyal, atau aplikasi tertutup.`;
    try {
      await sendWhatsHubMessage({ to: d.whatsapp_group_id, content: { text } });
    } catch (_err) {
      // Best-effort; we still mark it below so a flaky WhatsApp send doesn't
      // retry-storm every check cycle — it'll re-arm once the device reports.
    }
    markAlertedStmt.run(now, d.device_id);
  }
}

let started = false;
function startStaleWatchdog() {
  if (started) return;
  started = true;
  checkStaleDevices().catch(() => {});
  setInterval(() => checkStaleDevices().catch(() => {}), CHECK_INTERVAL_MS);
}

module.exports = { startStaleWatchdog, checkStaleDevices, STALE_THRESHOLD_MS };
