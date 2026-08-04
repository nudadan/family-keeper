# GTracker

A small GPS tracking system in two parts:

```
Android app (Tracker mode) --POST--> Backend (Node + SQLite) <--GET-- Android app (Viewer mode)
```

- **Tracker mode** sends this phone's GPS position to the backend every ~5 min.
- **Viewer mode** shows every device's latest position on a Google Map.
- A single Android APK contains both modes (chosen on the home screen).

## Layout

| Folder | What |
|--------|------|
| [`backend/`](backend/) | Node.js + Express + SQLite API. See [backend/README.md](backend/README.md). |
| [`android/`](android/) | Kotlin + Jetpack Compose app. See [android/README.md](android/README.md). |

## Getting started (order)

1. **Backend first** — deploy it and note its URL + `API_KEY`
   (see [backend/README.md](backend/README.md)).
2. **Android app** — put the backend URL, API key, and your Google Maps key into
   `android/gradle.properties`, then build in Android Studio
   (see [android/README.md](android/README.md)).

## Security notes

- All `/api/*` calls require the `X-API-Key` shared secret.
- Serve the backend over **HTTPS** in production (put Nginx in front).
- Don't commit real secrets (`.env`, API keys) to version control.
