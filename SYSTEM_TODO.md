# System screen — tracking

Context: the menu drawer (`AppDrawer.kt`) has a "System" entry (`DrawerDestination.SYSTEM`)
opening `ui/dashboard/SystemScreen.kt` (`SystemRoute`/`SystemScreen`) — four sections, each in its
own `Card`: General settings, Network, Boot (display only) and Services (editable: on/off toggles
applied by a "Save modifications" button). Loaded together when the screen opens and
on manual refresh (button in the `TopAppBar`), no polling (same mechanics as Alerts/Apps) — see
`DashboardViewModel.loadSystemSettings`.

## Verified live (2026-08-29)

Same conditions as `REPORTING_TODO.md`: throwaway API key provided by the user for their real
TrueNAS (LAN), read-only calls, key and raw responses not kept after use (scratch files deleted).

- **`GET /api/v2.0/system/general`** (`getSystemGeneral`): confirmed live. The real response is
  much bigger than what's actually read — it embeds the web GUI's TLS certificate **and private
  key** in the clear (`ui_certificate.privatekey`). `SystemGeneralDto` only declares the fields
  actually shown (`timezone`, `kbdmap`, `ui_port`, `ui_httpsport`, `ui_httpsredirect`,
  `ui_httpsprotocols`) — Gson silently ignores the rest, so nothing sensitive is ever deserialized
  in the app. **Careful if this file is ever extended**: never add a generic field like
  `Map<String, Any>` or declare `ui_certificate` on this DTO.
  - Values seen live: `kbdmap: "fr"`, `timezone: "Europe/Paris"`, `ui_port: 80`,
    `ui_httpsport: 443`, `ui_httpsredirect: false`, `ui_httpsprotocols: ["TLSv1.2", "TLSv1.3"]`.
- **`GET /api/v2.0/network/configuration`** (`getNetworkInfo`, 1st call): confirmed live.
  `hostname`, `domain`, `ipv4gateway`/`ipv6gateway`, `nameserver1`/`2`/`3` exactly as coded.
  `ipv6gateway` was an empty string (not `null`) on the tested server — `?.ifBlank { null }` handles
  that case on the app side.
- **`GET /api/v2.0/interface`** (`getNetworkInfo`, 2nd call): confirmed live on 2 physical
  interfaces. Important point: configured IP addresses live under **`state.aliases`** (the live
  kernel view: `[{"type": "INET", "address": "<server's local ip>", "netmask": 24, ...}]`),
  **not** the top-level `aliases` field (empty on both tested interfaces, since the IP was
  obtained via DHCP — the top-level `aliases` would only have entries for a static config).
  `state.link_state` seen as `"LINK_STATE_UP"`/`"LINK_STATE_DOWN"` — `linkUp` checks equality with
  `"LINK_STATE_UP"`.
- **`GET /api/v2.0/boot/get_state`** (`getBootInfo`, 1st call): confirmed live — same shape as
  a `pool.query` entry (the boot pool isn't a regular pool, but the middleware serializes it the
  same way): `name` ("boot-pool"), `status`, `healthy`, `size`, `allocated`, `scan` (same fields
  as `ScanDto`, including `start_time`/`end_time` as `{"$date": epochMillis}`). `warning`/
  `status_code`/`status_detail` are also present but not read (not shown on this screen).
- **`GET /api/v2.0/boot/environment/query`** (`getBootInfo`, 2nd call): **pitfall found and
  fixed** — the middleware method `boot.environment` has no `/boot/environment` REST route
  (404 confirmed); it's a CRUD-style method whose "list" variant is exposed under
  `/boot/environment/query` (confirmed via `GET /api/v2.0/openapi.json`, which also lists
  `/boot/environment/activate`, `/clone`, `/destroy`, `/keep` for the other actions, not
  implemented here). Fields confirmed: `id` (version, e.g. `"24.10.2.2"`), `active`, `activated`,
  `created` (`{"$date": ...}`), `used_bytes`. Field `used` (human string, e.g. `"2.66 GiB"`) seen
  but not read — `formatBytes(usedBytes)` is already used everywhere else in the app.

## Verified live (2026-10-11) — Services

Same kind of conditions (throwaway API key, user's LAN server, read-only calls only, raw responses
deleted after use), server on **TrueNAS 25.10.5**.

- **`GET /api/v2.0/service`** (`getServices`): confirmed live, one call for every service. Each
  entry: `id`, `service` (internal middleware name), `enable` (start automatically on boot),
  `state` (`"RUNNING"`/`"STOPPED"`), `pids`. The 8 entries seen live match the web UI's
  System > Services list: `cifs` (SMB, id 4), `ftp` (6), `iscsitarget` (iSCSI, 7), `nfs` (9),
  `snmp` (10), `ssh` (11), `ups` (14), `nvmet` (NVMe-oF, 27 — only on recent versions, absent
  before 25.10). `serviceLabel` maps the three names that don't match their UI label and
  uppercases the rest, so an unknown future service still shows up. Sorted by label like the web
  UI.
- **`POST /api/v2.0/service/start`** / **`/service/stop`** (`setServiceRunning`): confirmed live
  with the user's go-ahead on FTP (started then stopped again, left as found). Body
  `{"service": "ftp"}` (internal name, not the id). **Synchronous**: the response is a plain
  `true` once the service is actually running/stopped, not a job id — no `core.get_jobs`
  polling. With the default `options.silent: true`, a failure answers `false` instead of an HTTP
  error, hence the explicit check on the body. TrueNAS waits up to `options.timeout` (120s by
  default) — the app uses a dedicated client with a 130s read timeout for these two calls only
  (the shared client's is 15s). Stopping an already-stopped service answers `true`.
- **`PUT /api/v2.0/service/id/{id}`** (`setServiceStartOnBoot`): confirmed live on FTP (id 6,
  turned on then off again). Body `{"enable": bool}` (the only accepted field), response is the
  service id (`6`). Only changes the boot flag, doesn't start/stop the service.
- Not used: `/service/restart`, `/service/reload` and the generic `POST /service/control`
  (`{"verb": "START|STOP|RESTART|RELOAD", ...}`), all OpenAPI-only.

## Deliberate choices / known limits

- **Services edits are only applied on "Save modifications"**: compact table layout, one line per
  service (status dot + name + two `Switch`es aligned under a single "Running" / "Start auto"
  header line); toggling only records a pending edit, local to the screen
  (`remember`, not `rememberSaveable` nor the ViewModel), so leaving the screen or the app (or
  tapping refresh) drops it without touching the NAS — requested by the user. The button is
  pinned to the bottom of the screen (`Scaffold.bottomBar`), not at the end of the scrolling
  content, and only shows while a toggle differs from the server's value. On save
  (`DashboardViewModel.saveServiceChanges`): services in parallel, per service "start
  automatically" first then start/stop; the list is then reloaded and the pending edits cleared,
  so the toggles always end up on the server's real state; failures are listed per service at the
  top of the section. The status dot next to each name keeps showing the server's state (green
  running, grey stopped), not the pending one.
- **No confirmation dialog** before stopping a service: "Save modifications" is the explicit
  confirmation. Careful, stopping SSH/SMB/NFS from here cuts those accesses (not the web
  UI/API, which isn't a listed service).

- **Read-only except Services**: nothing else on this screen changes the config (no editing
  timezone, network, no activating a boot environment) — not requested, not done. If that changes, plan for a real
  confirmation flow (especially for activating a boot environment, which requires a reboot on the
  TrueNAS side).
- No polling (unlike CPU/Memory/Pools): this information rarely changes, like Alerts/Apps.
- The four sections are loaded in parallel (`async`) but each keeps its own loading/error state,
  so one section failing (e.g. the server responding badly to one of the calls) doesn't
  prevent the others from showing.
- A stopped service gets a grey dot, not the red `StatusDot` used for unhealthy pools/apps: most
  services are stopped on purpose, red would read as an error.

## Tests to do

- [ ] Open the System screen on device/emulator: verify the four sections show consistent,
  readable data.
- [ ] Verify the manual refresh button.
- [ ] Verify the partial-failure case (e.g. cutting the network while loading): the sections that
  succeeded should stay displayed, only the failed one should show the error.
- [ ] Verify on a server with more than 2 network interfaces and/or a static IP (to confirm that
  the top-level `aliases` is indeed empty by default and that `state.aliases` remains the right
  source even outside DHCP).
- [ ] Services: start a service from the TrueNAS web UI (e.g. SSH), refresh the System screen,
  verify it shows "Running" with a green dot.
- [ ] Services: toggle a switch → "Save modifications" appears pinned at the bottom (visible
  without scrolling); toggle it back → the button disappears.
- [ ] Services: toggle, then leave the screen (back) and come back → nothing applied, toggles back
  to the server's state. Same after closing the app.
- [ ] Services: start FTP + turn on its "Start automatically", save → both applied (check in the
  web UI), button gone. Then revert.
- [ ] Services: a failing start (e.g. a service with an invalid config) → error shown under
  "Services", toggles back to the real state.
- [ ] Verify back navigation (top-left arrow) from the System screen to the dashboard.
