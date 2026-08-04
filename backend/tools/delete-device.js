'use strict';

// Delete a device entirely: its positions, its registry row, and prune any
// group that becomes empty as a result (the 'default' group is kept).
//   node tools/delete-device.js <deviceId>

const path = require('path');
const Database = require('better-sqlite3');

const dbPath = process.env.DB_PATH || path.join(__dirname, '..', 'data', 'gtracker.db');
const device = process.argv[2];

if (!device) {
  console.error('usage: node tools/delete-device.js <deviceId>');
  process.exit(1);
}

const db = new Database(dbPath);

const fs = require('fs');

const tx = db.transaction((id) => {
  // Remove audio clip files for this device's requests first.
  const clips = db.prepare(
    'SELECT clip_path FROM audio_requests WHERE (target_device_id = ? OR requester_device_id = ?) AND clip_path IS NOT NULL'
  ).all(id, id);
  for (const c of clips) {
    try { if (c.clip_path && fs.existsSync(c.clip_path)) fs.unlinkSync(c.clip_path); } catch (_) {}
  }
  const aud = db.prepare(
    'DELETE FROM audio_requests WHERE target_device_id = ? OR requester_device_id = ?'
  ).run(id, id);
  const pos = db.prepare('DELETE FROM positions WHERE device_id = ?').run(id);
  const dev = db.prepare('DELETE FROM devices WHERE device_id = ?').run(id);
  const grp = db.prepare(`
    DELETE FROM groups
    WHERE group_id <> 'default'
      AND group_id NOT IN (SELECT DISTINCT COALESCE(group_id, 'default') FROM devices)
  `).run();
  return {
    positions: pos.changes, devices: dev.changes,
    audio: aud.changes, groupsPruned: grp.changes,
  };
});

const r = tx(device);
console.log(
  `Deleted device '${device}': ${r.positions} position(s), ${r.devices} registry row(s), ` +
  `${r.audio} audio request(s), ${r.groupsPruned} empty group(s) pruned`
);
