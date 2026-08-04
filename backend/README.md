# GTracker Backend

Node.js + Express + SQLite backend for the GTracker app. It receives GPS
positions from the **Tracker** mode and serves them to the **Viewer** mode.

## Requirements

- Node.js 18 or newer
- Build tools for `better-sqlite3` (a native module):
  - **Debian/Ubuntu:** `sudo apt-get install -y build-essential python3`
  - These are usually already present on most Linux servers.

## Setup

```bash
cd backend
cp .env.example .env
# edit .env and set a strong API_KEY
npm install
npm start
```

The server prints `GTracker backend listening on port 3000`.

Quick check in a browser: `http://<server>:3000/health` → `{"ok":true,...}`

## Authentication

Every `/api/*` request must include the shared secret as a header:

```
X-API-Key: <the value of API_KEY in your .env>
```

The Android app stores this key and sends it automatically.

## Groups (multi-tenant isolation)

Devices are isolated into groups via an optional header:

```
X-Group-Code: <a shared secret per group>
```

- The server stores/compares only the **SHA-256 hash** of the code (raw code never hits the DB).
- A Tracker and a Viewer that send the **same** code see each other; different codes are fully isolated.
- **No code** → the shared `default` group (also covers older data stored before this feature).

This is how the family / team use case works: each family picks its own group code,
and only members with that code can see one another. The `X-API-Key` still gates the
API as a whole; `X-Group-Code` partitions what you can see within it.

## API

Base path: `/api/positions`

### `POST /api/positions`  — store a GPS fix (Tracker app)

Request body (JSON):

```json
{
  "deviceId": "phone-andi",
  "lat": -6.200000,
  "lng": 106.816666,
  "accuracy": 12.5,        // optional, meters
  "speed": 1.4,            // optional, m/s
  "timestamp": 1719140400000  // optional, epoch millis; server time used if omitted
}
```

Response: `201 { "id": 123, "ok": true }`

### `GET /api/positions/latest` — latest fix for every device (Viewer app)

Response:

```json
[
  { "id": 123, "device_id": "phone-andi", "lat": -6.2, "lng": 106.8,
    "accuracy": 12.5, "speed": 1.4, "recorded_at": 1719140400000,
    "received_at": 1719140401000 }
]
```

### `GET /api/positions/latest?deviceId=phone-andi` — latest fix for one device

Returns a single object (or `null`).

### `GET /api/positions?deviceId=phone-andi&limit=100` — history, newest first

`limit` defaults to 100, max 1000.

### `GET /api/positions/tracks?hours=12` — tracks for the trail/polyline (Viewer app)

Returns each device's points within the time window, **oldest first** (ready to
draw as a line). Accepts `?hours=<n>` (default 12, max 168) or `?since=<epochMillis>`.
Add `?deviceId=x` to limit to one device.

```json
{
  "since": 1719097200000,
  "tracks": [
    {
      "deviceId": "phone-andi",
      "points": [
        { "lat": -6.2, "lng": 106.81, "accuracy": 12.5, "speed": 1.4, "recordedAt": 1719097205000 },
        { "lat": -6.21, "lng": 106.82, "accuracy": 9.0, "speed": 2.1, "recordedAt": 1719097505000 }
      ]
    }
  ]
}
```

### `GET /api/positions/devices` — known devices

```json
[ { "device_id": "phone-andi", "count": 42, "last_seen": 1719140400000 } ]
```

## Run as a service (systemd, on your remote server)

Create `/etc/systemd/system/gtracker.service`:

```ini
[Unit]
Description=GTracker backend
After=network.target

[Service]
WorkingDirectory=/opt/gtracker/backend
ExecStart=/usr/bin/node src/server.js
Restart=always
EnvironmentFile=/opt/gtracker/backend/.env
User=www-data

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now gtracker
```

Put Nginx (with HTTPS) in front of it for production. The Android app should
talk to the backend over **https://** whenever possible.
