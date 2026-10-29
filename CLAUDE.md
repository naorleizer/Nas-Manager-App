# NasManager App

Android app (Kotlin, Jetpack Compose, Material3) acting as a companion client for a TrueNAS Scale
server: login + near-real-time system dashboard + detailed reporting graphs.

## Architecture

- **Network**: OkHttp + Gson, manual REST calls to `/api/v2.0/...` (no Retrofit). The authenticated
  `OkHttpClient` (`TrueNasApplication.okHttpClient`) carries an `AuthInterceptor` that adds auth to
  every request; a second, separate `imageOkHttpClient` (no interceptor) is used only for app icons
  served by a public CDN, so the NAS credentials are never sent there — see `APPS_TODO.md`. Icons
  are PNG/JPEG (`BitmapFactory`) or SVG (AndroidSVG, internal XML entities disabled app-wide in
  `TrueNasApplication.onCreate`), with a bounded download and decode size (`AppsScreen.kt`).
- **Auth**: Basic Auth (`Authorization: Basic base64(user:pass)`), no API key or session cookie —
  TrueNAS Scale has no `/auth/login` REST route. `CredentialsStore` keeps the credentials in
  memory; `TrueNasAuthRepository.login()` validates them via a GET `/api/v2.0/system/info` before
  storing them there. `SessionPreferences` (EncryptedSharedPreferences) persists them if "Stay
  logged in" is checked. "Stay logged in" is unavailable for an `http://` address (checkbox
  disabled in `LoginScreen`, guarded again in `LoginViewModel` and on restore in
  `TrueNasApplication.onCreate`, via the shared `TrueNasUrl.isHttp` helper) — see
  `CONNECTIVITY_TODO.md`.
- **Unencrypted HTTP**: allowed to any address (IP or hostname), but only after the user's explicit
  consent (checkbox in `LoginScreen`, `acceptHttpRisks` parameter re-checked in
  `TrueNasAuthRepository.login()`). No filtering by IP range — see `CONNECTIVITY_TODO.md` for the
  history of this decision.
- **Dashboard** (`ui/dashboard`, `data/dashboard`): `system.info` is loaded once on open; CPU,
  Memory and Pools are re-polled every 2s (`DashboardViewModel`), via `pool.query` and
  `reporting.netdata_get_data` (a sliding window of a few seconds, not the default ~3600 points).
  The Memory breakdown (Services / ZFS Cache / Free) and bar colors reproduce the TrueNAS web
  dashboard.
- **Reporting graphs** (`GraphScreen.kt`, `PoolDetailScreen.kt`): the CPU, Memory and Pools &
  Storage cards of the dashboard (not System, which doesn't have one) have a "view graph" button
  opening a dedicated screen (CPU/Memory history via aggregated `reporting.netdata_get_data`, with
  a 5 min→1 month time-range selector and auto-refresh). Pools has two levels: per-pool overview
  (topology, last scrub) then
  per-disk detail (I/O + temperature, one graph per disk via the `identifier` of
  `reporting.netdata_get_data` — **careful**, this identifier is not the disk's raw name but a
  composite string to fetch via `reporting.graphs`, see `REPORTING_TODO.md`). Full detail of the
  API formats verified live is in `REPORTING_TODO.md`.
- **Navigation** (`AppDrawer.kt`, `DashboardScreen.kt`): a menu drawer (`ModalNavigationDrawer`,
  hamburger button in the dashboard's `TopAppBar`) rather than Navigation-Compose — same
  state-based navigation pattern (`when` over `var ... by remember`) as the rest of the dashboard
  screen. Entries: current user + Alerts + Logout at the top,
  Dashboard/Storage/Reporting/Apps/System at the bottom (Storage and Reporting reuse the existing
  `GraphTarget.POOLS`/`GraphTarget.CPU` screens, no new dedicated screen — Apps and System have
  their own, `AppsScreen.kt`/`SystemScreen.kt`). A single `BackHandler` in `DashboardRoute` mirrors
  the current screen's `onBack` (drawer open → Alerts/Apps/System → pool detail → graph → root),
  enabled everywhere except the true root so the system back gesture/button steps back through this
  state instead of closing the app.
- **Alerts** (`AlertsScreen.kt`): screen reachable from the drawer, lists `alert.list` (loaded on
  open + manual refresh, not polled), red badge on the drawer entry, "Dismiss" button per alert
  (`alert.dismiss`, real server call). Full detail of the API formats verified live is in
  `ALERTS_TODO.md`.
- **Apps** (`AppsScreen.kt`): screen reachable from the drawer, lists `app.query` (loaded on open,
  not polled) with an "Update" button per app (`app.upgrade`, a middleware job polled via
  `core.get_jobs` to completion) and "Update All" (same mechanism run in parallel across every app
  with an update available), plus a per-app status dot + Start/Stop button (`app.start`/`app.stop`, same
  job mechanism; tinted square icon so it isn't mistaken for "Update", Stop confirmed by a dialog). No CPU/Memory/Network/Block I/O per app — these metrics only exist as
  WebSocket events on the TrueNAS side (`app.stats`/`container.metrics`), not over REST, and this
  app is REST-only. "Discover Apps" is only a placeholder for now (the `catalog.apps` catalog is
  ~800 KB). Full detail in `APPS_TODO.md`.
- **System** (`SystemScreen.kt`): screen reachable from the drawer, four sections loaded
  together on open + manual refresh (not polled) — General settings (`system.general`), Network
  (`network.configuration` + `interface`), Boot (`boot.get_state` + `boot.environment`, exposed
  over REST under `/boot/environment/query`) and Services (`service.query`). The first three are
  display only (no editing timezone, network, activating a boot environment). Services has
  "Running"/"Start automatically" switches per service, applied only by a "Save modifications"
  button pinned to the bottom of the screen (`service.start`/`stop`, synchronous, and
  `PUT /service/id/{id}`); unsaved edits are dropped when leaving the screen. Full detail in
  `SYSTEM_TODO.md`.

## Tracking files

- `CONNECTIVITY_TODO.md`: dedicated tracking for connectivity/auth (network scenarios covered,
  tests done and pending, security points not to forget before production). Check before touching
  auth or the HTTP/HTTPS policy.
- `REPORTING_TODO.md`: dedicated tracking for reporting graphs (API formats verified live via
  `curl` against a real server, remaining assumptions, UI tests pending). Check before touching
  `GraphScreen.kt`, `PoolDetailScreen.kt` or the `reporting.*`/`disk.*` calls in
  `DashboardRepository.kt`.
- `SECURITY_TODO.md`: security testing tracking (static and dynamic) beyond what's already covered
  by `CONNECTIVITY_TODO.md`. Started as a personal, undistributed project but now intended for
  actual publishing (R8/obfuscation enabled on the release build since 2026-10-04; APK signing
  and a dynamic smoke test of the signed release build both done 2026-10-06) — check this file
  for what's still open before shipping.
- `ALERTS_TODO.md`: dedicated tracking for alerts (`alert.list`, formats verified live via `curl`
  against a real server, parsing decisions, tests pending). Check before touching
  `AlertsScreen.kt` or the `alert.*` calls in `DashboardRepository.kt`.
- `APPS_TODO.md`: dedicated tracking for apps (`app.query`/`app.upgrade`/`core.get_jobs`, formats
  verified live via `curl`, what only exists over WebSocket on the TrueNAS side — CPU/memory/
  network/block-IO per app —, tests pending). Check before touching `AppsScreen.kt` or the
  `app.*`/`core.get_jobs` calls in `DashboardRepository.kt`.
- `SYSTEM_TODO.md`: dedicated tracking for the System screen (`system.general`/
  `network.configuration`/`interface`/`boot.get_state`/`boot.environment`/`service.query`, formats verified live via
  `curl`, notably the `boot.environment` REST route pitfall and the fact that `system.general`
  embeds the GUI's TLS private key — never deserialized in the app). Check before touching
  `SystemScreen.kt` or those calls in `DashboardRepository.kt`.
- Convention going forward: if a new TrueNAS feature (shares, snapshots, VMs...) needs similar
  tracking, create a dedicated file of the same kind (`XXX_TODO.md`) rather than growing an
  existing file scoped to something else.

## Tests

- `./gradlew testDebugUnitTest` — JVM unit tests (`app/src/test`). Internal functions (parsing,
  formatting) are tested directly from `app/src/test` thanks to `internal` visibility shared
  between `main` and `test` in the same module.
- No integration tests against a real server: the TrueNAS API's response formats (`system.info`,
  `pool.query`, `reporting.netdata_get_data`...) were verified live via `curl` before
  implementation rather than assumed from the docs.
