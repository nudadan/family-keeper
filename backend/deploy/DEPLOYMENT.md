# GTracker backend — deployment record

Deployed: 2026-06-23

## Server

- Host: `194.233.92.67` (ns1-app-svr, Ubuntu 24.04)
- SSH user: `dimas`
- Public domain: **https://g-tracker.d42n.online** (DNS A record already points to the server)

## What was installed / created

| Thing | Location |
|-------|----------|
| App code | `/opt/gtracker/backend` (owned by `dimas`) |
| Environment file | `/opt/gtracker/backend/.env` (chmod 600) — contains `API_KEY` |
| SQLite database | `/opt/gtracker/backend/data/gtracker.db` |
| systemd service | `/etc/systemd/system/gtracker.service` (enabled, auto-restart, starts on boot) |
| nginx vhost | `/etc/nginx/sites-available/g-tracker.d42n.online.conf` (symlinked in sites-enabled) |
| TLS cert | Let's Encrypt via certbot, auto-renews. Expires 2026-09-21. |

Runtime: Node v24, listens on `127.0.0.1:3000`; nginx reverse-proxies 443 → 3000
and redirects 80 → 443.

## Common operations (run on the server)

```bash
sudo systemctl status gtracker      # check service
sudo systemctl restart gtracker     # restart after a code change
sudo journalctl -u gtracker -f      # live logs
```

## Updating the code

```bash
# from your local machine
scp -r backend/src dimas@194.233.92.67:/opt/gtracker/backend/
ssh dimas@194.233.92.67 'sudo systemctl restart gtracker'
```

## Admin site

- URL: **https://g-tracker.d42n.online/admin** (HTTP Basic auth)
- Credentials live in the server `.env` as `ADMIN_USER` / `ADMIN_PASSWORD`.
- Manage device **type** (admin/member), rename **groups**, and delete devices.
- Visibility rule: a **member** cannot see devices of type **admin**; an **admin**
  sees everyone in its group. The app sends `X-Device-Id` so the server knows the
  requester's type. Device type is changed **only** here, never in the app.

## Smoke test

```bash
curl https://g-tracker.d42n.online/health
# {"ok":true,...}
```

The `API_KEY` value lives in the server's `.env` and in
`android/gradle.properties` (`BACKEND_API_KEY`). Keep both in sync. Rotate it by
editing both and restarting the service.
