'use strict';

// Low-battery alert: relays a WhatsApp warning when a device's battery drops
// to/under LOW_THRESHOLD, once per "episode" (won't re-notify on every fix
// while it stays low). The episode resets once the battery recovers past
// RESET_THRESHOLD, so a full recharge re-arms the alert for next time.

const db = require('./db');
const { whatshubConfigured, sendWhatsHubMessage, wibTime } = require('./whatshub');

const LOW_THRESHOLD = 15;
const RESET_THRESHOLD = 30;

const getDeviceBatteryStmt = db.prepare(
  'SELECT battery_percent, battery_alert_sent_at FROM devices WHERE device_id = ?'
);
const setBatteryStmt = db.prepare(
  'UPDATE devices SET battery_percent = ? WHERE device_id = ?'
);
const setAlertSentStmt = db.prepare(
  'UPDATE devices SET battery_alert_sent_at = ? WHERE device_id = ?'
);
const getGroupWhatsappStmt = db.prepare(
  'SELECT whatsapp_group_id FROM groups WHERE group_id = ?'
);

/**
 * Records [batteryPercent] for [deviceId] and, if it just dropped to/under
 * the low threshold (and no alert is outstanding for this episode), relays a
 * WhatsApp warning. Safe to call on every position fix.
 */
async function checkBattery({ deviceId, groupId, batteryPercent, label, now }) {
  if (batteryPercent == null) return;

  const row = getDeviceBatteryStmt.get(deviceId);
  setBatteryStmt.run(batteryPercent, deviceId);

  if (batteryPercent >= RESET_THRESHOLD) {
    if (row && row.battery_alert_sent_at != null) {
      setAlertSentStmt.run(null, deviceId);
    }
    return;
  }

  if (batteryPercent > LOW_THRESHOLD) return;
  if (row && row.battery_alert_sent_at != null) return; // already warned this episode

  setAlertSentStmt.run(now, deviceId);

  const whatsappGroupId = getGroupWhatsappStmt.get(groupId)?.whatsapp_group_id;
  if (!whatsappGroupId || !whatshubConfigured()) return;

  const deviceLabel = label || deviceId;
  const text = `🔋 *Baterai Lemah*\n\n*${deviceLabel}* tersisa ${batteryPercent}%.` +
    ` Lokasi mungkin berhenti terkirim jika HP mati.\n🕒 ${wibTime(now)} WIB`;
  try {
    await sendWhatsHubMessage({ to: whatsappGroupId, content: { text } });
  } catch (_err) {
    // Best-effort; state above is already saved so we won't spam retries.
  }
}

module.exports = { checkBattery, LOW_THRESHOLD, RESET_THRESHOLD };
