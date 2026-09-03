'use strict';

// Geofence ("Zona Aman") transition detection: on every position fix, check
// whether the device just crossed into or out of any zone defined for its
// group, and relay an enter/exit alert to the group's WhatsApp group.
// Fire-and-forget from the caller's point of view — never blocks or fails
// the position upload itself.

const db = require('./db');
const { whatshubConfigured, sendWhatsHubMessage, wibTime } = require('./whatshub');

const listGeofencesForGroupStmt = db.prepare(
  'SELECT id, name, lat, lng, radius_m FROM geofences WHERE group_id = ?'
);
const getStateStmt = db.prepare(
  'SELECT inside FROM geofence_state WHERE device_id = ? AND geofence_id = ?'
);
const upsertStateStmt = db.prepare(`
  INSERT INTO geofence_state (device_id, geofence_id, inside, updated_at)
  VALUES (@device_id, @geofence_id, @inside, @updated_at)
  ON CONFLICT(device_id, geofence_id) DO UPDATE SET
    inside = excluded.inside,
    updated_at = excluded.updated_at
`);
const getGroupWhatsappStmt = db.prepare(
  'SELECT whatsapp_group_id FROM groups WHERE group_id = ?'
);

const EARTH_RADIUS_M = 6371000;

/** Great-circle distance between two lat/lng points, in meters. */
function distanceMeters(lat1, lng1, lat2, lng2) {
  const toRad = (d) => (d * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLng = toRad(lng2 - lng1);
  const a = Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
  return EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

/**
 * Checks [lat,lng] for [deviceId] against every geofence in [groupId],
 * relaying an enter/exit WhatsApp alert for any zone it just crossed.
 * Safe to call on every position fix — a no-op unless a boundary was
 * actually crossed since the last fix.
 */
async function checkGeofences({ deviceId, groupId, lat, lng, label, now }) {
  const zones = listGeofencesForGroupStmt.all(groupId);
  if (zones.length === 0) return;

  const whatsappGroupId = getGroupWhatsappStmt.get(groupId)?.whatsapp_group_id;
  const deviceLabel = label || deviceId;

  for (const zone of zones) {
    const isInside = distanceMeters(lat, lng, zone.lat, zone.lng) <= zone.radius_m;
    const prev = getStateStmt.get(deviceId, zone.id);
    const wasInside = !!prev && prev.inside === 1;

    if (isInside === wasInside) continue; // no transition

    upsertStateStmt.run({
      device_id: deviceId,
      geofence_id: zone.id,
      inside: isInside ? 1 : 0,
      updated_at: now,
    });

    // First-ever fix for this device/zone pair just seeds the baseline
    // state — don't announce a "transition" for a state that never existed.
    if (!prev) continue;

    if (!whatsappGroupId || !whatshubConfigured()) continue;

    const text = isInside
      ? `🟢 *${deviceLabel}* memasuki zona *${zone.name}*\n🕒 ${wibTime(now)} WIB`
      : `🔴 *${deviceLabel}* meninggalkan zona *${zone.name}*\n🕒 ${wibTime(now)} WIB`;

    try {
      await sendWhatsHubMessage({ to: whatsappGroupId, content: { text } });
    } catch (_err) {
      // Best-effort notification; a failed WhatsApp send shouldn't affect
      // tracking. The state above is already saved so we won't re-announce
      // the same crossing on the next fix.
    }
  }
}

module.exports = { checkGeofences, distanceMeters };
