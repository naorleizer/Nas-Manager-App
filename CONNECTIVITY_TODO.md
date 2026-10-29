# Server connectivity — tracking

Context: the login form accepts a TrueNAS address over HTTP or HTTPS, and authenticates against
the TrueNAS Scale REST API. This file tracks what's covered, tested, and what not to forget before
production.

## Authentication

The app was first written assuming a `POST /api/v2.0/auth/login` login (username/password →
session cookie). That doesn't exist on the tested TrueNAS install: the OpenAPI spec
(`/api/v2.0/openapi.json`) lists no `/auth/login` or `/auth/logout` route, and
`components.securitySchemes` declares `basic`.

A first Basic Auth + password test had returned 401 — the (wrong) conclusion was that Basic Auth
didn't work, and it was swapped for an API key via `Authorization: Bearer <key>` instead. The 401
actually came from a wrong test account: a second test (`curl -u user:pwd .../system/info`) with
the right account returned 200. **Basic Auth (user/password) works**, and the app was switched
back to it.

Pitfall to remember: TrueNAS's human-facing docs page (`/api/docs/current`) lists **every**
middleware method, including ones only reachable over the WebSocket JSON-RPC (like `auth.login`,
with its famous `otp_token` and "Returns true/false"). Don't confuse this doc with the real REST
contract (`openapi.json`) — that's what wasted time here. Another pitfall, this one our own fault:
always double-check the account used before concluding an auth mechanism doesn't work.

Current architecture: `CredentialsStore` (mutable, in memory, username + password) +
an OkHttp `AuthInterceptor` that adds `Authorization: Basic base64(user:pass)`
(`okhttp3.Credentials.basic`) to every request. `TrueNasAuthRepository.login()` validates the
credentials with a GET `/api/v2.0/system/info` before storing them in the store. No session cookie
(`PersistentCookieJar` removed): auth is stateless, every request carries its own credentials.

## "Stay logged in" is unavailable over HTTP

The password persisted by "Stay logged in" (`SessionPreferences`, `EncryptedSharedPreferences`) is
already encrypted at rest, so storage itself was never the risk here. The risk is what happens
*after* storage: if the saved server address is `http://`, every app start restores the password
into `CredentialsStore` and resumes the dashboard's 2s poll, which sends `Authorization: Basic
base64(user:pass)` in the clear on whatever network the phone happens to be on at that moment —
home Wi-Fi, a hotel/airport AP, anything — with no further action or warning from the user. For
HTTPS this isn't a concern (the channel is encrypted), so the restriction only applies to `http://`.

Implementation (`TrueNasUrl.isHttp`, the single `internal` helper now used everywhere this
decision is made, so the three sites below can't drift from each other or from the checkbox):
- `LoginScreen`: the "Stay logged in" checkbox is disabled and forced to unchecked while the
  entered address normalizes to `http://`, with a short explanation shown underneath. It becomes
  usable again as soon as the address is `https://` (or scheme-less, which normalizes to `https`).
- `LoginViewModel.connect()`: re-checks `isHttp` before ever writing the password to
  `SessionPreferences`, independently of the checkbox/UI state — defense in depth in case a future
  caller reaches this path with `rememberMe = true` some other way. Server address and username
  are still always persisted (no secret in either), so an HTTP user who can't save the password
  still only has to retype that one field next time.
- `TrueNasApplication.onCreate`: if a previously saved session's `serverUrl` is `http://` (older
  install, or the server was switched from https to http since), the saved password is wiped and
  `isLoggedIn` is cleared instead of restoring — the user lands back on the login screen. This is
  the backstop for the case the other two guards can't prevent: a "Stay logged in" session saved
  while the address was `https://`, where the user then only changes the address to `http://` on a
  later successful login (rememberMe forced false by the UI at that point, so no *new* password
  gets written, but the *old* stored one would otherwise still be sitting there tied to the new
  http address).

Out of scope / unchanged by this: `acceptHttpRisks` consent flow, no IP-range filtering, no Wi-Fi
SSID detection (would need the location permission — too heavy for this).

## Connection scenarios considered

End users connect either via an IP or a custom URL/hostname, over HTTP or HTTPS — all 4
combinations must work. History: an earlier version restricted cleartext HTTP to literal private
IPs (`PrivateNetworkPolicy.kt`, with dedicated tests) and blocked any hostname over HTTP. Replaced
by **explicit user consent**: `PrivateNetworkPolicy.kt` and its test are removed,
`network_security_config.xml` allows cleartext with no address restriction, and
`TrueNasAuthRepository.login()` refuses any `http://` address unless the caller passes
`acceptHttpRisks = true` — which `LoginScreen` only sends once the "I accept the risks of using
HTTP" checkbox is checked (shown only when the entered address resolves to HTTP). This guard is
re-checked on the repository side, not just in the UI, in case a future call bypasses the login
screen.

| Scenario | Status | Detail |
|---|---|---|
| HTTPS + valid public certificate (reverse proxy, Let's Encrypt, Cloudflare Tunnel, DDNS...) | ✅ Already works | Goes through system trust-anchors, no change needed. |
| HTTPS + self-signed certificate (TrueNAS default) | ✅ Works | Requires importing the certificate into Android (Settings → Security → Install a CA certificate). Explicit error message already in place. |
| Cleartext HTTP to an IP (LAN, VPN like WireGuard/Tailscale, or public) | ✅ Implemented | Allowed after explicit consent (checkbox) — no more address restriction. |
| Cleartext HTTP to a hostname (`.local`, DDNS, custom URL...) | ✅ Implemented | Same guard as above: allowed after explicit consent, whatever the resolved address. |
| IPv6 (ULA `fc00::/7`, link-local `fe80::/10`, loopback, or public) | ✅ Implemented | No more special handling needed, address-range filtering was removed. |
| Authentication with TrueNAS credentials (user/password, Basic Auth) | ✅ Implemented | See section above. Same type of account as used for the TrueNAS web UI. |

## Tests done

- `./gradlew compileDebugKotlin testDebugUnitTest` → green build after switching to Basic auth
  (user/password) and after removing `PrivateNetworkPolicy` in favor of HTTP consent.
- Manual live check against the user's real TrueNAS (curl): presence/absence of REST routes, Basic
  Auth with the right account → 200 on `/api/v2.0/system/info`.
- Dashboard (system.info, pool.query, reporting.netdata_get_data for CPU/memory/ARC) validated
  live via curl before implementation — see conversation history, not re-detailed here.

## Tests to do (no manual test inside the app itself yet)

- [ ] Real connection from the app with real credentials (user/password), over HTTPS.
- [ ] Real connection to `http://192.168.x.x` on the LAN TrueNAS, with these credentials — verify
  that the HTTP checkbox appears and blocks the connection until it's checked.
- [ ] Real connection to `http://<ip>` via a WireGuard tunnel (the original use case for this
  request).
- [ ] Connection via a Tailscale client (IP 100.64.0.0/10) if used by the team/testers.
- [ ] Real connection to `http://my-custom-url.example.com` (hostname, not just an IP) — new use
  case to validate now that address filtering has been removed.
- [x] "Stay logged in": kill the app, reopen it, verify the credentials are properly restored
  (`TrueNasApplication.onCreate`) and that subsequent calls are authenticated — confirmed on
  2026-08-29 on emulator (`adb shell am force-stop` then relaunch: dashboard reloaded directly
  with real server data, no re-prompt for credentials). See `SECURITY_TODO.md`.
- [ ] Log out then log back in — verify no residual credential lingers in memory (the logout→login
  flow itself was exercised on 2026-08-29 for another test, but not this specific memory check).
- [x] Password changed on the TrueNAS side while the app is "logged in": verify the behavior (the
  app only detects it on the next request, no periodic re-validation) — done by the user, see
  `SECURITY_TODO.md`.
- [x] Re-test the HTTPS + **untrusted** self-signed certificate flow: confirmed on 2026-08-29 via
  mitmproxy interception (see `SECURITY_TODO.md`) — connection refused with a clear message. Still
  need to test the positive path (actually importing a self-signed certificate into Android, then
  a successful connection) — not done, only the rejection was verified dynamically.
- [ ] Real device/emulator check of the "Stay logged in unavailable over HTTP" flow: checkbox
  disabled + explanation under an `http://` address, re-enabled on switching to `https://`, and —
  for an old session saved while on `https://` — confirm that logging in again against `http://`
  doesn't leave a stale password+`isLoggedIn=true` restorable on next launch (covered at the unit
  level by `TrueNasUrlTest`/`TrueNasApplicationTest`, not yet exercised in the running app).
- [ ] Test on a real Android device (not just an emulator), particularly for
  EncryptedSharedPreferences (depends on the hardware Keystore, may behave differently on an
  emulator).
- [ ] Dashboard: real-conditions test on device/emulator (2s polling for CPU/Memory/Pools,
  behavior in the background / with the screen locked, CPU core grid rendering).

## Not to forget to remove/revisit before production

- `cleartextTrafficPermitted="true"` in `network_security_config.xml` now allows HTTP with no
  address restriction at all — **the only barrier is user consent** (`acceptHttpRisks` in
  `TrueNasAuthRepository.login()`, checked via the checkbox in `LoginScreen`). Any new network
  entry point (a new screen, a direct OkHttp call bypassing `TrueNasAuthRepository`) must go
  through this same consent, otherwise cleartext becomes open again with no guard at all, not even
  a warning.
- **Basic Auth sends `base64(user:pass)` on every request** — this isn't encryption, just
  trivially reversible encoding; over HTTP, all the protection therefore comes down to the user
  having explicitly accepted this risk. This password is the same one used to log in (often the
  TrueNAS web UI's admin account), unlike an old-style API key, a dedicated secret revocable
  independently — so the exposure hurts more. **Recommendation to document for the end user**
  (README + possibly a help text in `LoginScreen`): create a TrueNAS account dedicated to the app
  with reduced permissions (read-only on the endpoints used —
  `system.info`/`pool.query`/`reporting.*`/`alert.*` — plus `app.upgrade` only if "Update" is
  used) rather than using the main admin account there, precisely because this password travels in
  the clear if the user picks the HTTP option.
- Credentials are stored via `EncryptedSharedPreferences` (androidx.security-crypto 1.1.0) — the
  API is deprecated on Google's side (compile-time warnings) in favor of Jetpack DataStore + manual
  encryption (Tink), but remains functional and supported. Worth watching in case Google drops
  support later; not urgent today.
- `truenas_session.xml` is excluded from cloud backup and device-to-device transfer
  (`data_extraction_rules.xml`, `backup_rules.xml`) because the Keystore master key doesn't migrate
  between devices — a restore would have made the file unreadable. If a future SharedPreferences
  file is added to store another secret, remember to exclude it too.
- "Logout" in the app does **not** invalidate the password on the server side (TrueNAS exposes no
  REST route for that, and changing the password would also break access to the web UI) — this is
  a plain local sign-out. Unlike an API key, there's not even a "revocation" possible without
  changing the password everywhere it's used. This needs to be documented clearly in the app's
  UI/help if it's meant to be robust against a stolen/lost device.
- Check that no debug log prints the full URL or the password in the clear (useful during dev, to
  remove before release).
- Confirm that no test value (IP, credentials) is hardcoded anywhere.
