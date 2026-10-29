# Alerts — tracking

Context: the menu drawer (`ui/dashboard/AppDrawer.kt`) has an "Alerts" entry that opens
`AlertsScreen`/`AlertsRoute` (`ui/dashboard/AlertsScreen.kt`), listing active system alerts via
`DashboardRepository.getAlerts()`. Loaded when the screen opens (not polled like the dashboard) and
on manual refresh.

## API format verified live (2026-08-29)

The user provided a "throwaway" API key for their real TrueNAS (LAN, reachable from this session
for the duration of the test) to verify the format before implementation, as with the rest of the
dashboard (see `CLAUDE.md`). Call made (read-only): `GET /api/v2.0/alert/list` with
`Authorization: Bearer <key>`. The key was revoked on the TrueNAS side after use; the scratch file
containing the response is not in the repo.

Real response (5 alerts, matching exactly what the user was seeing on the web dashboard at the
same moment):

- Each entry has `id` (= `uuid`), `level` (`"WARNING"`, `"NOTICE"`, `"INFO"` observed — the other
  values of `AlertLevel` in `DashboardModels.kt`, e.g. `CRITICAL`/`ERROR`, come from the JSON-RPC
  docs (`api.truenas.com`) but haven't been seen live), `text` (raw template with unsubstituted
  `%s`/`%(name)s`), `formatted` (already-substituted text, with a bit of HTML — `<br>` and
  `<a href="...">…</a>` observed), `dismissed` (bool), `datetime`
  (`{"$date": epochMillis}`, same shape as `pool.query`'s `scan.start_time`/`end_time` — already
  handled by `epochSecondsFromTrueNasDate`).
- `args` varies wildly in shape depending on `klass` (object, string, or `null`) — not parsed,
  `formatted` is enough since it has already substituted everything.
- The endpoint is indeed the deprecated REST gateway (`/api/v2.0/...`), not the JSON-RPC/WebSocket
  documented by `api.truenas.com` — consistent with the rest of the app (see
  `CONNECTIVITY_TODO.md`), and API-key auth works as `Authorization: Bearer <key>` on this gateway.
- The 5 returned alerts all had `dismissed: false` on the first call — **confirmed while testing
  the Dismiss button (below)** that `alert/list` does include already-dismissed alerts
  (`dismissed: true` in the response), so client-side filtering (`extractAlerts`) is necessary, not
  just a precaution.

Full payload (for reference, with the real uuids/dates/text) archived in the unit tests
(`DashboardRepositoryTest.kt`, test `parses and maps a real GET api v2_0 alert list response...`),
not only described here.

## Dismiss — verified live (2026-08-29, same session)

Each alert's "Dismiss" button (`AlertsScreen.kt`) calls
`DashboardRepository.dismissAlert(id)`, actually tested against the real server before
implementation (same server and same throwaway key as above, revoked since):

- `POST /api/v2.0/alert/dismiss` — the initially assumed convention (body `["<uuid>"]`, an
  RPC-style positional array) is **wrong**: 422 `{"uuid": [{"message": "Input should be a
  valid string", ...}]}`. An object `{"uuid": "<uuid>"}` fails the same way. The right format is
  **the raw JSON string**: `-d '"<uuid>"'` → `200`, body `null`. Meaning `gson.toJson(id)`
  (a plain Kotlin `String`) already produces exactly the right body — see `postRaw` in
  `DashboardRepository.kt`.
- Tested end-to-end on a real, low-criticality alert (a "PoolUpgraded" notice for Miyota):
  dismiss → confirmed `dismissed: true` via a fresh `GET /alert/list` → then restored with
  `POST /api/v2.0/alert/restore` (same body format, also verified) to put the system back in the
  state the user had left it in.
- `alert.restore` isn't wired into the app (not requested), but its body format is therefore
  already known if it's ever needed: same convention as dismiss (raw JSON string).

## Decisions made

- `formatted` is used for display rather than `text` (already substituted), with its minimal HTML
  transformed client-side (`stripAlertHtml`: `<br>` → line break, any other tag stripped while
  keeping its text) — no HTML rendering in the current Compose UI.
- `level` is parsed into `AlertLevel` with an `UNKNOWN` fallback (`parseAlertLevel`) rather than
  crashing on a value not yet seen live.
- Already-"dismissed" alerts are filtered client-side (`extractAlerts`), necessary (see above).
- Dismiss is a real server call (mirroring the web dashboard's button), not a plain local "seen"
  flag — an explicit user choice. Optimistic on the UI side: the alert is removed from
  `DashboardUiState.alerts` as soon as the call succeeds, without re-fetching the whole list
  (`DashboardViewModel.dismissAlert`).
- Red badge on the "Alerts" drawer entry (`AppDrawer.kt`, Material3 `Badge`/`BadgedBox`, default
  theme red) = `uiState.alerts.size`, i.e. the number of active alerts currently known on the app
  side. Loaded once when the dashboard opens (`DashboardViewModel.init`, like `system.info`) rather
  than polled — no need for second-by-second freshness for alerts.

## Tests to do

- [ ] Open the Alerts screen on device/emulator with real alerts: check readability, colors per
  level (WARNING orange, NOTICE indigo, INFO gray, CRITICAL/ERROR red — only WARNING/NOTICE/INFO
  verified live), line wrapping of the formatted text.
- [ ] Check the "no alerts" case (`alert/list` returns `[]`) and the server-error case.
- [ ] Check the Dismiss button on device/emulator: the alert disappears from the list, the drawer
  badge decrements without having to reopen the app (API format already confirmed live above,
  end-to-end UI test still pending).
- [ ] Check the dismiss-failure case (e.g. server unreachable during the call): the error banner
  above the list shows up correctly without making the other alerts disappear.
