# GTracker Android app

One app, two modes:

- **Tracker** — runs a foreground service that sends this device's GPS position
  to the backend every N minutes (default 5).
- **Viewer** — shows the latest position of every device on a Google Map.

Stack: Kotlin, Jetpack Compose, Google Maps Compose, FusedLocationProvider,
Retrofit/Moshi, DataStore.

## Prerequisites

- **Android Studio** (latest stable). It bundles the Android SDK and Gradle, so
  you do not need Gradle installed globally.
- A **Google Maps API key** (Maps SDK for Android enabled) from
  [Google Cloud Console](https://console.cloud.google.com/).

## Configure

Edit `gradle.properties` and set:

```properties
MAPS_API_KEY=AIza...your-key...
BACKEND_BASE_URL=https://your-server.example.com/   # keep the trailing slash
BACKEND_API_KEY=must-match-the-backend-.env-API_KEY
```

These are baked in as defaults. You can also change the backend URL / key / interval
at runtime in the app's **Settings** screen.

> Tip: keep `gradle.properties` secrets out of git. For a real project move them
> to `~/.gradle/gradle.properties` or use the in-app Settings screen instead.

## Build & run

1. Open the `android/` folder in Android Studio. Let it sync Gradle (this also
   generates the Gradle wrapper if missing).
2. Plug in a phone (or use an emulator **with Google Play** so Maps + location work).
3. Press **Run**.

From the command line (after Android Studio has created the wrapper):

```bash
cd android
./gradlew assembleDebug        # build APK
./gradlew installDebug         # install on a connected device
```

## Permissions flow

The Tracker requests, in order:

1. `ACCESS_FINE_LOCATION` + `POST_NOTIFICATIONS` (Android 13+)
2. `ACCESS_BACKGROUND_LOCATION` (Android 10+, requested separately — the system
   sends the user to a settings page to choose "Allow all the time")

Tracking only continues in the background reliably when background location is
granted **and** battery optimization is relaxed for the app on some OEM phones
(Xiaomi, Oppo, Samsung, etc.).

## Auto-restart after reboot

`BootReceiver` listens for `BOOT_COMPLETED` and restarts the tracker service
automatically **if tracking was on** when the phone was powered off (the state
is persisted in DataStore). Notes:

- It fires after the user unlocks the phone the first time post-boot (that's when
  the encrypted settings become readable).
- Background + (ideally) "Allow all the time" location must already be granted.
- On aggressive OEM ROMs, also enable **Autostart** for the app and disable
  battery optimization, otherwise the system may block the boot broadcast.
