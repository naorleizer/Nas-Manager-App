# Apps — tracking

Context: the menu drawer (`ui/dashboard/AppDrawer.kt`) has an "Apps" entry that opens
`AppsScreen`/`AppsRoute` (`ui/dashboard/AppsScreen.kt`): "Update All (X)"/"Discover Apps" buttons
at the top, a table of installed apps (logo, name+version, status, update) below. Loaded when the
screen opens (not polled) and on manual refresh.

## API format verified live (2026-08-29)

Same throwaway key, same LAN server as for alerts (see `ALERTS_TODO.md`) — revoked on the TrueNAS
side once this work was done.

### App list — `GET /api/v2.0/app`

Real response: 8 installed apps (`planka`, `onlyoffice-document-server`, `nextcloud3`,
`immich`, `home-assistant`, `firefly-iii`, `nginx-proxy-manager`, `jellyfin`) — the first 6
with `upgrade_available: true`, consistent with the "Updates are available for 6 applications"
alert already seen in `ALERTS_TODO.md`.

- Each entry has `id`/`name` (identical in every case observed), `state` (enum confirmed by the
  `api.truenas.com` docs: `RUNNING`, `STOPPED`, `DEPLOYING`, `CRASHED`, `STOPPING` — only `RUNNING`
  seen live, all 8 test apps were running), `version`/`latest_version`/`human_version`,
  `upgrade_available`/`image_updates_available` (bool), `metadata.title` (display name, e.g.
  `"Planka"`) and `metadata.icon` (direct URL, e.g.
  `https://media.sys.truenas.net/apps/planka/icons/icon.png` — a public CDN, **not** the user's
  TrueNAS server, hence `TrueNasApplication.imageOkHttpClient` kept separate from the authenticated
  client).
- `active_workloads`/`container_details` exist (per-container detail of the compose stack) but
  contain **no metrics at all** (CPU/memory/network/IO) — just ports, volumes, images, per-container
  state. Not used for now.
- Full payload (trimmed to the fields read by `AppDto`) archived in
  `DashboardRepositoryTest.kt` (test `parses a real GET api v2_0 app response...`).

### CPU/Memory/Network/Block I/O per app — **no REST**

Verified live by elimination before starting to code (user decision: ship without these columns,
see conversation):

- `GET /api/v2.0/app/stats` → 404. `POST /api/v2.0/app/stats` (body `[]`) → 404.
  `GET /api/v2.0/container/metrics` → 404.
- The `api.truenas.com` docs do list `app.stats` and `container.metrics`, but under their
  "API Events" section — these are WebSocket subscription events (JSON-RPC 2.0), not regular RPC
  methods exposed on the deprecated REST gateway. This app is built on pure REST/OkHttp (see
  `CLAUDE.md`), with no WebSocket client.
- `reporting.graphs` (already used for CPU/Memory/Disk, see `REPORTING_TODO.md`) has **no** graph
  tied to apps/containers (40 graphs listed, all system/pool/UPS) — no workaround possible through
  that route.
- Conclusion: these 3 columns would require a real WebSocket JSON-RPC 2.0 client (a new network
  stack, different auth, subscription management) — a project of its own, not done here.

### Catalog ("Discover Apps") — `POST /api/v2.0/catalog/apps`

- `GET /api/v2.0/catalog/apps` → 405 (wrong method); `POST` with body `{}` → 200, **~800 KB** of
  JSON. Structure: a top-level object per train (`stable`, `test`, `community`, `enterprise`,
  `dev` observed), each train a dict `{app_name: {title, description, icon, categories,
  latest_version, healthy, ...}}` (`stable` had 17 apps at test time).
- Not used for now: "Discover Apps" only opens a placeholder screen
  (`AppsDiscoverScreen` in `AppsScreen.kt`) — browsing 5 trains plus an install screen (per-app
  config form) is a much bigger feature than what was asked here.

### Update — `POST /api/v2.0/app/upgrade` (job) + `GET /api/v2.0/core/get_jobs`

`app.upgrade` mutates a real system — it was **not** tested with a real app name to avoid
triggering a real update during verification. The format was confirmed without that:

- `POST /api/v2.0/app/upgrade` with body `{}` → `400 {"message": "app_name attribute
  expected."}`, confirming the body is an **object** `{"app_name": "<id>"}` (kwargs-style),
  unlike `alert/dismiss` (raw string) — the docs also mention an optional `options`
  (`app_version`, `values`, `snapshot_hostpaths`), not sent by the app for now (upgrades default to
  `latest`).
- With a **nonexistent** app name (`"this-app-does-not-exist-zzz"`, guaranteed to have no effect on
  a real component) → `200`, body = **a raw integer** (`105554`), not an object. It's indeed an
  async middleware job (confirmed by the docs: `app.upgrade` is a Job) — the app's existence is
  validated **inside** the job, not upfront on receiving the REST request.
- `GET /api/v2.0/core/get_jobs?id=105554` (right after) confirmed the job was `FAILED` with an
  explicit `error` (`App this-app-does-not-exist-zzz does not exist`) — full payload (trimmed)
  archived in `DashboardRepositoryTest.kt`. Fields read: `state` (classic middlewared enum:
  `WAITING`, `RUNNING`, `SUCCESS`, `FAILED`, `ABORTED` — only `FAILED` seen live, the others come
  from the general middlewared convention, not curl-verified individually), `progress.percent`,
  `error`.
- `GET /core/get_jobs?id=<id>` returns an **array** (not a single object) even when filtered to a
  single id — `DashboardRepository.getJobStatus` takes `firstOrNull()`.

### Start/Stop — `POST /api/v2.0/app/start` / `app/stop` (jobs) — confirmed working on device 2026-10-25

From the `api.truenas.com` docs (v25.10, checked 2026-10-25): `app.start` and `app.stop` are both
**jobs**, a single positional `app_name` (string), return `null`, role `APPS_WRITE`.

- Body sent as a **bare JSON string** (`"<id>"`), by analogy with `alert/dismiss` (the other
  single-parameter method, where an object 422s) — unlike `app/upgrade` (two parameters → kwargs
  object). Confirmed by the user starting/stopping real apps from the app against their server
  (2026-10-25) — not separately captured with `curl`, so no archived raw payload.
- Response assumed to be the raw job id (like `app/upgrade`), polled with the same
  `awaitJobCompletion`, then the list is re-fetched.

## Decisions made

- No CPU/Block I/O/Network in the table (see above) — an explicit user choice made after seeing the
  technical constraint, not a silent simplification.
- "Discover Apps" is a placeholder — scope deliberately limited to the buttons + table requested in
  this message, not the full catalog browsing experience.
- "Update All" calls `upgradeApp` for every app with `upgrade_available`, in parallel (not
  sequential) — each upgrade is an independent job, no reason to serialize them.
- After a successful upgrade, the whole `DashboardUiState.apps` is re-fetched (no local patch of
  the entry) — unlike dismissing an alert, an upgrade changes several fields at once
  (version, `upgrade_available`, possibly `state` while redeploying).
- Icons are loaded via a **separate**, unauthenticated `OkHttpClient`
  (`TrueNasApplication.imageOkHttpClient`) — icon URLs point to a public CDN
  (`media.sys.truenas.net`), not the user's server; sending the NAS's Basic Auth to a third party
  would be a needless credential leak.
- No image-loading library (Coil considered then dropped — see below): manual loading in
  `fetchIconBitmap`, consistent with the project's philosophy ("OkHttp + Gson... no Retrofit") —
  `BitmapFactory` for PNG/JPEG, AndroidSVG (a renderer, not a loader) for SVG, see below.
- Icon formats vary per app on the CDN (checked via `curl` 2026-10-08): `plex`, `planka`,
  `pihole`... → `icon.png` (`image/png`); `jellyfin`, `nextcloud`, `immich`, `syncthing`... →
  `icon.svg` (`image/svg+xml`). `BitmapFactory` can't decode SVG (returned `null` → fallback icon,
  hence "some icons show, others don't"), so SVGs are rasterized with AndroidSVG
  (`com.caverock:androidsvg-aar`, Apache 2.0, pure Java, no transitive deps — unlike Coil, no
  Kotlin stdlib clash). Picked by `Content-Type`, falling back to the URL extension
  (`isSvgIcon`, unit-tested). `renderSvg` forces the document's width/height to `100%`: without
  that, a root `width="590"` (Immich's icon, which also has no `viewBox`) was drawn at 590 px
  into the 40dp bitmap, leaving only an empty corner visible — checked on an emulator against the
  5 real SVGs of a test server (Immich 0% → 63% of pixels drawn, OnlyOffice no longer cropped,
  Jellyfin/Nextcloud/Nginx Proxy Manager still fine).
- Icon fetches are bounded (the URL comes from the NAS, so it isn't trusted): body capped at 1 MiB
  (`MAX_ICON_BYTES`, real icons ≤ ~80 KB), PNG/JPEG decoded subsampled to about the displayed
  40dp (`iconSampleSize` — e.g. qBittorrent's 1024 px icon → 128 px, ~64 KB instead of ~4 MB),
  SVG internal XML entities disabled app-wide (`TrueNasApplication.onCreate`). No
  `SVGExternalFileResolver` is registered, so an SVG can't make AndroidSVG fetch anything else.
  Security detail and what's still open: `SECURITY_TODO.md`, "App icons: SVG support + bounded
  download/decode".

## Snag hit: Coil 3.6.0 incompatible with the project's Kotlin version

First attempt with `io.coil-kt.coil3:coil-compose:3.6.0` (+ `coil-network-okhttp`) to load icons —
`./gradlew compileDebugKotlin` failed: `coil-compose` pulls in
`org.jetbrains.compose.foundation:foundation:1.12.0` (JetBrains' **Multiplatform** Compose libs,
not just androidx.compose), which bundles a much newer `kotlin-stdlib` (2.4.x metadata) than the
project's Kotlin compiler (2.2.10) can read. Rather than forcing a stdlib version resolution
(fragile, could break on the next update of any dependency), the Coil dependency was removed
entirely — manual icon loading (`AppIcon`/`fetchIconBitmap` in `AppsScreen.kt`) is more than enough
for a handful of small icons per screen.

## Tests to do

- [ ] Open the Apps screen on device/emulator: check the table is readable (logos, names,
  statuses, "Update" button per row) and that the "Update All (X)" count is correct.
- [ ] Test a real "Update" on an app with an update available (never done live, to avoid mutating a
  real system during verification): check the button turns into a spinner, the job polling
  completes (`SUCCESS`), the row updates (new version, button disappears) without leaving the
  screen.
- [ ] Test "Update All" with several outdated apps at once: check the jobs really run in parallel
  (not one after another) and each row reflects its own state.
- [ ] Test an upgrade-failure case (e.g. a buggy app, or server unreachable during polling): check
  the error shows under the right row without blocking the other apps.
- [x] Check on device that SVG icons (e.g. Jellyfin, Nextcloud, Immich) now render, sharp and
  not cropped, alongside PNG ones — confirmed by the user on the emulator, 2026-10-08.
- [x] Start/Stop works against a real server — confirmed by the user on device, 2026-10-25.
- [x] Start/Stop vs "Update" mis-taps (user feedback 2026-10-25): status text label dropped, the
  Status column is now a green/red dot + a tinted square "■"/"▶" icon button (no text label),
  a different shape from the outlined-pill "Update". Stop asks for confirmation first
  ("Stop <app>?" dialog); Start doesn't. The full state
  (Deploying/Stopping/Crashed...) is only exposed to screen readers now. Check on device that it
  fits on a narrow phone and that mis-taps are gone.
- [ ] Test Start/Stop edge cases on device: Stop on a running app → spinner, then "Stopped" + Start icon;
  Start → back to "Running" (possibly via "Deploying"); Update disabled meanwhile; a failure shows
  under the row. Check the Status column (chip + icon) still fits on a narrow phone.
- [ ] Check the rendering when an app has no icon (`metadata.icon` null): the fallback icon
  (`Icons.Filled.Apps`) should show up with no error.
- [ ] If "Discover Apps" is ever built out: the `catalog/apps` payload (~800 KB, 5 trains) is
  big — plan for client-side pagination/filtering before considering loading it all at once.
