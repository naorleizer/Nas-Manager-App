# NasManager App

Android mobile app for connecting to a **TrueNAS Scale** server via its WebSocket JSON-RPC 2.0 API (`/api/current`), and viewing a near-real-time system dashboard straight from your phone.

Fork of Juju-archy/Nas-Manager-App (v1.2.0 baseline, GPL-3.0) with the network layer migrated from REST /api/v2.0 to the WebSocket JSON-RPC 2.0 API.

## Tech stack

- **Kotlin** + **Jetpack Compose** (Material 3) — Android native only for now, no iOS version yet.
- **OkHttp** WebSocket for the TrueNAS Scale JSON-RPC 2.0 API (hand-written client, no Retrofit).
- **Gson** for JSON serialization.
- **AndroidSVG** to render app icons served as SVG (PNG/JPEG go through Android's `BitmapFactory`).
- **Coroutines / StateFlow** for async state management (MVVM architecture).

## Architecture

Single Activity, state-driven navigation (no Navigation-Compose), MVVM with manual DI (no framework — `TrueNasApplication` holds the shared singletons as `by lazy` properties). See `CLAUDE.md` for the full breakdown per feature.

```mermaid
graph TB
    MainActivity["MainActivity<br/>(single Activity)"] --> IsLoggedIn{"SessionPreferences<br/>.isLoggedIn ?"}
    IsLoggedIn -->|no| LoginScreen["Login screen<br/>LoginRoute / LoginViewModel"]
    IsLoggedIn -->|yes| Drawer["Navigation drawer<br/>AppDrawer"]
    Drawer --> FeatureScreens["Feature screens<br/>Dashboard · Reporting graphs<br/>Pools detail · Alerts · Apps · System<br/>(all driven by DashboardViewModel)"]

    LoginScreen --> AuthRepo["TrueNasAuthRepository"]
    FeatureScreens --> DashRepo["DashboardRepository"]

    AuthRepo --> CredStore["CredentialsStore<br/>(in-memory)"]
    AuthRepo -->|"if 'stay logged in'"| SessionPrefs["SessionPreferences<br/>(EncryptedSharedPreferences)"]
    AuthRepo --> Rpc["TrueNasRpcClient<br/>(one WebSocket per session)"]
    DashRepo --> Rpc
    Rpc -->|"re-auth on reconnect"| CredStore

    Rpc --> TrueNAS[("TrueNAS Scale server<br/>wss://host:port/api/current")]

    FeatureScreens -. app icons only .-> ImageClient["imageOkHttpClient<br/>(unauthenticated)"]
    ImageClient --> CDN[("Public CDN<br/>media.sys.truenas.net")]

    SessionPrefs -. encrypted via .-> Keystore[("Android Keystore")]
```

- **One authenticated WebSocket per session**: `TrueNasRpcClient` opens `/api/current`, authenticates it once with `auth.login_ex`, and multiplexes every call over it as JSON-RPC 2.0 (`params` always an array). If the socket drops it reconnects lazily (exponential backoff) and re-authenticates with the credentials held by `CredentialsStore`.
- **One `DashboardViewModel`/`DashboardUiState` feeds every post-login screen** (Dashboard, reporting graphs, pool detail, Alerts, Apps, System) — navigation between them is local Compose state (`var ... by remember` in `DashboardScreen.kt`), not a router.
- **`imageOkHttpClient` is deliberately separate and unauthenticated**: app icons are served from a public CDN, not the user's TrueNAS, so the NAS credentials must never be sent there.

## Authentication

The app authenticates the WebSocket with **`auth.login_ex`** (`PASSWORD_PLAIN`: TrueNAS username + password), once per connection. If that fails and the password field holds something shaped like an API key (`<number>-<16+ chars>`), it retries once with `API_KEY_PLAIN` — so you can paste an API key in the password field. Accounts that require two-factor authentication, lack API access, have expired credentials, or must authenticate on the other controller get a specific message.

The WebSocket URL is derived from the typed address: `https://` → `wss://`, `http://` → `ws://`, the port is the one you typed (443/80 by default), path `/api/current`.

Unencrypted HTTP (`ws://`, no certificate) is allowed to any address (IP or hostname), but only after the user has explicitly checked a box accepting the associated risks. HTTPS (`wss://`) remains recommended — see `CONNECTIVITY_TODO.md` for the connection scenarios covered.

> ⚠️ **Recommendation**: use a TrueNAS account **dedicated to the app**, with reduced permissions (read-only on the screens used, plus app-update permission if you plan to use it), rather than the main admin account — or an API key. The password is sent in the clear if you use HTTP mode; a dedicated account limits the damage in case of interception.

## Features

- [x] Login screen (server address, credentials, "stay logged in" option)
- [x] Authentication via `auth.login_ex` (password or API key) on the `/api/current` WebSocket
- [x] Session persistence (encrypted credentials + local preferences)
- [x] Post-login dashboard: system info, CPU (total + per core), memory (Services / ZFS Cache / Free breakdown), pools & storage — CPU/memory/pools refreshed every 2s
- [x] Detailed reporting graphs for CPU, Memory and Pools & Storage (CPU/Memory history, Pools detail per pool then per disk)
- [x] Navigation menu (side drawer): Dashboard, Storage, Reporting, Apps, System, Alerts, Logout
- [x] System back button/gesture steps back through the drawer navigation instead of closing the app
- [x] System alerts (badge, list, "Dismiss" per alert — real server call)
- [x] Installed apps: status, individual or bulk update ("Update All"); catalog ("Discover Apps") not browsable yet
- [x] System screen: General settings, Network, Boot (read-only)
- [ ] Other NAS features (shares, snapshots, VMs...)
- [ ] iOS version

## Requirements

- Android Studio (latest stable version)
- JDK 17+ (required by the Android Gradle Plugin 9)
- A TrueNAS Scale server reachable from the phone/emulator (TrueNAS 25.10 `/api/current` JSON-RPC API)

## Getting started

```bash
./gradlew assembleDebug
```

Or simply open the project in Android Studio and run the app on an emulator/device (`minSdk 35`).

On the login screen, provide:
- the server address (e.g. `https://192.168.1.10`, or `https://nas.local:8443` — the port comes from the typed address)
- your TrueNAS username and password (or an API key in the password field)

> Note: if your TrueNAS uses a self-signed certificate, it must be imported into Android's security settings for the HTTPS (`wss://`) connection to succeed.

## Building a signed release

```bash
./gradlew assembleRelease
```

Without any further setup this produces an **unsigned** APK (`app-release-unsigned.apk`) — fine
for local testing, not installable as an update or uploadable to a store listing. To get a signed
`app-release.apk` instead, set these four properties in your own `~/.gradle/gradle.properties`
(never in this repo):

```properties
RELEASE_STORE_FILE=/absolute/path/to/your.keystore
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

`app/build.gradle.kts` reads them via `providers.gradleProperty(...)` and only creates the
`release` signing config when `RELEASE_STORE_FILE` is present, so the build never fails for a
contributor who doesn't have the signing key. The keystore itself and its passwords are kept and
backed up outside this repository — see `SECURITY_TODO.md`.

## Project structure

```
app/src/main/java/com/nasmanagerapp/
├── data/
│   ├── auth/       # Auth repository (auth.login_ex) + session persistence
│   ├── network/    # JSON-RPC WebSocket client, credentials, HTTP/HTTPS policy
│   └── dashboard/  # Dashboard repository + models (system.info, pool.query, reporting)
├── ui/
│   ├── login/      # Login screen and ViewModel
│   ├── dashboard/  # Dashboard, reporting graphs, navigation drawer, Alerts, Apps
│   └── theme/      # Material 3 theme
├── MainActivity.kt
└── TrueNasApplication.kt
```

## Tests

```bash
./gradlew testDebugUnitTest
```

## Further documentation

- `CLAUDE.md` — architecture overview, for AI tools (and humans).
- `CONNECTIVITY_TODO.md` — detailed connectivity/auth tracking: network scenarios covered, tests done and pending, security points not to forget before production.
- `REPORTING_TODO.md` — reporting graphs tracking (API formats verified live, UI tests pending).
- `ALERTS_TODO.md` — alerts tracking (`alert.list`/`alert.dismiss`, formats verified live, tests pending).
- `APPS_TODO.md` — apps tracking (`app.query`/`app.upgrade`, what only exists over WebSocket on the TrueNAS side, tests pending).
- `SYSTEM_TODO.md` — System screen tracking (`system.general`/`network.configuration`/`interface`/`boot.get_state`/`boot.environment`, formats verified live, tests pending).
- `SECURITY_TODO.md` — security testing tracking (static and dynamic).

## License

Copyright (C) 2026 Julie POUNY

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. See [LICENSE](LICENSE) for the full text.

The app icon (launcher icons under `app/src/main/res/mipmap-*` and `fastlane/metadata/android/*/images/icon.png`)
was generated by the author with Google Gemini and is dedicated to the public domain under
[CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/).
