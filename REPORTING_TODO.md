# Reporting graphs — tracking

Context: the CPU, Memory and Pools & Storage cards of the dashboard have a "view graph" button
(`ShowChart` icon in `DashboardCard`, `ui/dashboard/DashboardScreen.kt`) that opens a dedicated
screen (`ui/dashboard/GraphScreen.kt`, `GraphRoute`/`GraphScreen`) with a back arrow at the top
left. The System card no longer has one (removed on 2026-08-29, for lack of real use — the screen
only showed a load-average histogram, already visible as raw values on the card itself on the main
page): `GraphTarget` therefore only has `CPU`/`MEMORY`/`POOLS` left.

"Page by page" improvement in progress: CPU, Memory and the per-disk detail screen (see below)
have framed sections, a time-range selector and an auto-refresh button; the Pools graph screen
(per-pool overview) still keeps its version with no selector or auto-refresh.

## Data sources per card

- **CPU** (redone, see dedicated section below): `DashboardRepository.getCpuPageHistory(windowSeconds)`,
  graphs `cpu` + `cputemp` + `load` in a single `reporting/netdata_get_data` call.
- **Memory** (redone, see dedicated section below): `DashboardRepository.getMetricsHistory(windowSeconds)`,
  graphs `cpu` + `memory` + `arcsize` in a single `reporting/netdata_get_data` call (the `cpu`
  graph is requested there but not shown on this page — only `memory`/`arcsize` are used here).
- **Pools** (initial donut + new navigation to a per-pool detail, see below): no history over
  time (no known endpoint for that) — the graph shows an allocated/free donut per pool from data
  already fetched by `pool.query`, so no new API call.

## Features shared by CPU, Memory and the per-disk detail

These three screens share the same mechanics:

- Each section/card sits in its own `Card` (same framed style as the main page's cards).
- Auto-refresh button (`Switch` in the `TopAppBar`): reloads the history every 5s while active,
  via a `LaunchedEffect` scoped to the screen (stops automatically on leaving the screen, no
  background job in the ViewModel). Shared interval
  (`AUTO_REFRESH_INTERVAL_MILLIS`, `GraphScreen.kt`, `internal` so it can be reused by
  `PoolDetailScreen.kt`).
- Time-range selector (`TimeRange` + `TimeRangeSelector`, `GraphScreen.kt`, `internal` for the
  same reason — on a 2nd row under the title/Auto, framed): 13 values from 5 min to 1 month, on a
  single row `[-] Period shown: XXX [+]` (the center button directly shows the full text, no
  separate abbreviated label). "-"/"+" step one notch at a time through the list (e.g. 1 day →
  "+" → 3 days, 1 day → "-" → 12 h), disabled at the ends (5 min / 1 month). Tapping the center
  text opens a menu to jump straight to any value. Every change (step-by-step or direct)
  re-triggers a load with the new window; auto-refresh, if on, keeps reloading with the current
  window on every tick. The Pools graph screen (per-pool overview) doesn't have this selector yet
  — `GraphTarget.supportsTimeRange()` lists the cards concerned on the `GraphScreen` side, to
  extend the day it gets one too.
- Section titles suffixed with the period shown (e.g. "Disk I/O sdc | ... — 15 minutes").
- A single range/auto-refresh state per screen (no per-card/per-disk prefix): since only one
  screen is shown at a time (`GraphRoute` for CPU/Memory, `PoolDetailRoute` for the per-disk
  detail, each keyed on `target`/`poolId` via `remember`), no need for separate state per
  sub-element.

## CPU card

Three sections, all sourced from `getCpuPageHistory(windowSeconds)`. **Curl-verified against a
real server** (see "Verified live" at the very bottom):

- **CPU usage**: an "Average" line (dimension `cpu` of the `cpu` graph) + one line per core
  (`cpu0`, `cpu1`, ...) — same logic as `extractLiveMetrics`, already verified live.
- **CPU temperature**: `cputemp` graph. Real legend confirmed:
  `["time", "cpu0", "cpu2", "cpu1", "cpu3", "cpu", ...]` — cores aren't necessarily in numeric
  order in the legend, and there is indeed a `"cpu"` dimension (not `"cputemp"`) which is the
  server-precomputed average. `extractCpuTemperatureSeries` uses this dimension directly as
  "Average" when present (found via an `indexOf("cpu")`, as for the usage `cpu` graph), and only
  falls back to a client-computed average when it's absent (different hardware/sensor config).
  Core labels recognize the `cpuN`/`cputempN` pattern via regex (`Core N`), otherwise use the raw
  legend name as-is.
- **Load average**: `load` graph. Real legend confirmed:
  `["time", "shortterm", "midterm", "longterm"]` — exactly the assumption already coded (matched
  by case-insensitive name, with a positional fallback in case another server ever gave different
  names).

Each section uses a different color per line (cyclic `ChartPalette` palette, 10 colors).

## Memory card

Three sections, all sourced from `getMetricsHistory(windowSeconds)`, each a single line
(no multi-line here) but with a fixed color reused from the color coding already used on the main
page's Memory card (instead of the cyclic palette):

- **Available memory** (new, listed first): `available` dimension of the `memory` graph, raw
  value — dedicated color `AvailableColor` (a marked pink-gray, `#9C7480`). Deliberately not
  `FreeColor`: the latter is also used on the main page and pools, changing it would have had an
  impact elsewhere.
- **Used memory**: computed client-side as `total - available` (the total comes from
  `SystemInfo.totalMemoryBytes`, loaded once when the dashboard opens) — color `ServicesColor`
  (indigo, "in use" on the main page).
- **ZFS cache (ARC)**: `size` dimension of the `arcsize` graph — color `ZfsCacheColor` (orange,
  same color as on the main page).

## Pools card — two levels: overview then per-disk

The Pools card now has two screens:

1. **Pools graph screen** (`PoolsGraphContent`, `GraphScreen.kt`): a single `Card` per pool,
   grouping everything about the pool as a whole — Space breakdown (allocated/free donut,
   already present before), Information (data topology, usable capacity, status, disks with ZFS
   errors, fragmentation) and Last scrub (date, duration, errors) — followed by an "Open this
   pool" button.
2. **Pool detail screen** (`ui/dashboard/PoolDetailScreen.kt`, `PoolDetailRoute`/
   `PoolDetailScreen`, reached via "Open this pool"): no more overview at all (moved to point 1
   above) — only the per-disk detail: for each disk in the pool, a "Disk I/O {name}" card then a
   "Disk Temperature {name}" card, each with the disk's identity in the title (Type/Model/Serial),
   the graph, and a Max/Mean/Min row per line below. Its back arrow returns to the Pools graph
   screen (not straight to the dashboard). Also has the auto-refresh button and time-range
   selector, same mechanics as CPU/Memory (see "Shared features" above).

Navigation: always handled in `DashboardRoute` (`DashboardScreen.kt`) with the
`selectedPoolId: Int?` state alongside `selectedGraph` — no navigation library. The pool itself
(status, allocated space) is still looked up by id in `uiState.pools` (polled every 2s); if the
pool disappears along the way, the detail screen shows "This pool is no longer available." rather
than crashing.

### API call for the overview: `getPoolDetail(poolId)`

One per pool shown, triggered as soon as a new pool id appears in `uiState.pools` (via a
`LaunchedEffect` in `GraphRoute` keyed on the list of ids, not the whole pool list — otherwise it
would re-trigger on every 2s poll). Loads the `topology` and `scan` fields of
`pool.query` (`GET /api/v2.0/pool/id/{id}`), absent from `PoolDto`/`PoolSummary`. **Curl-verified
against a real server** (see "Verified live" at the very bottom) — `topology`, `scan` and
`GET /pool/id/{id}` are exactly as coded, no adjustment needed:

- **`topology.data`** (data vdevs): grouped by shape (type/width/size) for "Data
  Topology" (e.g. "1 x RAIDZ1 | 4 wide | 1.82 TiB") — `extractPoolDetail` in
  `DashboardRepository.kt`. A vdev with no redundancy (a bare disk at the head of `data`) is
  shown as "STRIPE", width 1. Each leaf does carry a `disk` field with the device's raw name
  (e.g. `"sdc"`), confirmed live.
  - **"No redundancy" indicator tried then dropped**: a warning message based on the presence of
    a "STRIPE" group had been added under "Data Topology", but it was app-side text, not an API
    field — verified live that no field, `status_code`
    (`warning`/`status_code` on `pool.query` reflect other middleware warnings, e.g.
    `FEAT_DISABLED` for ZFS feature flags, identical on a pool with and without redundancy) or
    TrueNAS alert class exposes this notion. Removed at the user's request once this limit was
    understood — if the need comes back, app-side detection (via topology) will be needed again,
    the API having nothing to offer here.
- **All `topology` categories** (`data`, `log`, `cache`, `spare`, `special`, `dedup`): walked
  recursively to count "Disks with ZFS errors" (leaves with
  `read_errors`/`write_errors`/`checksum_errors` > 0 — field names confirmed).
- **`scan`**: `function`, `state`, `errors`, and `start_time`/`end_time` for "Last scrub" (end
  date, duration = end − start). `start_time`/`end_time` are indeed encoded as
  `{"$date": epochMillis}`, confirmed live (e.g. `{"$date": 1786831202000}`) —
  `epochSecondsFromTrueNasDate` handles this format first.

### API call for the per-disk detail: `getPoolDiskDetails(poolId, windowSeconds)`

A single call, triggered when the pool detail screen opens, on every change of the time-range
selector, and on every auto-refresh tick (same mechanisms as CPU/Memory, see the dedicated
section above). **Curl-verified and fixed following a bug reported by the user**
("disks show up but no graph") — see "Verified live" at the very bottom for investigation
details:

- **Disk names**: reuses the `topology` from the same `pool.query` (`GET /pool/id/{id}`, a
  second call, no cache between the two screens for now) — `extractDiskNames` walks every
  vdev category and reads the `disk` field of each leaf. Confirmed correct.
- **Identity (Type/Model/Serial)**: `GET /api/v2.0/disk` (the whole collection, unfiltered),
  filtered client-side by name. Fields confirmed exact: `name`/`type`/`model`/`serial`.
- **I/O and temperature history**: `disk` and `disktemp` graphs, one per disk via the
  `identifier` parameter of each `graphs` entry. **Two bugs found and fixed in two passes:**
  1. The identifier expected by the server is *not* the disk's raw name (e.g. `"sdc"`) but a
     composite string `"{name} | Type: {type} | Model: {model} | Serial: {serial}"`. With just
     the raw name, the server silently returned `[]` (200 OK, empty array, no error) — hence the
     total lack of a graph despite data being well present on the server side (confirmed visible
     on the TrueNAS web UI).
  2. Rebuilding this string client-side from `disk.query` (`GET /disk`) isn't enough: its `model`
     field doesn't always match exactly the one used in the real identifier
     (observed on a real disk: `disk.query` returned a `model` with an underscore and a longer
     suffix, while the graph's real identifier used a space and a truncated version). A first fix
     based on reconstruction (`diskGraphIdentifier`) therefore stayed broken for that specific
     disk despite an overall-correct format.
  3. **Solution adopted**: read the list of valid identifiers directly from
     `GET /api/v2.0/reporting/graphs` (the `"disk"` entry's `identifiers`, shared by `disk` and
     `disktemp`) and match by the `"{name} | "` prefix (`findDiskGraphIdentifier`) rather
     than reconstructing the string — avoids any formatting mismatch with `disk.query`.
     `diskGraphIdentifier` (reconstruction) is now only a fallback if a disk is missing from that
     list. Verified end-to-end via `curl` (resolution + `netdata_get_data` call with the resolved
     identifier → data received correctly).
  - `disk`: legend confirmed `["time", "reads", "writes"]`. Unit confirmed **Kibibytes/s** (not
    bytes/s as initially assumed) via the `vertical_label` from `GET /reporting/graphs` —
    `formatMebibytesPerSecond` fixed accordingly (divides by 1024, not 1024²).
  - `disktemp`: legend confirmed `["time", "temperature_value"]`, unit Celsius (as already coded).
- **Max/Mean/Min**: computed client-side from the raw points rather than relying on the
  `aggregations` field of the API (confirmed present, but deliberately set aside to avoid one
  more assumption about its exact per-dimension shape).

## Verified live (2026-08-29)

The user provided a "throwaway" API key for their real TrueNAS (LAN, reachable from this session)
for the time it took to diagnose the "no graph on the disk page" bug. Calls made
(all read-only): `GET /system/info`, `GET /disk`, `GET /pool`, `GET /pool/id/{id}` (both
pools, "Miyota" and "Omega"), `GET /reporting/graphs`, and several `POST
/reporting/netdata_get_data` (checks on `cpu`/`arcsize`/`interface`, then `disk`/`disktemp`/
`cputemp`/`load` with and without an identifier). Result: almost every assumption in this
document since the start of the reporting feature was right — only the `disk`/`disktemp`
identifier format and `disk`'s unit were wrong, fixed in a first pass.

**Second pass** (same day, key still valid): after a first fix based on client-side reconstruction
of the identifier (`diskGraphIdentifier`), the user reported the graph stayed empty even after a
rebuild/reinstall of the app. Live investigation showed that `disk.query`'s `model` field doesn't
match verbatim the one used by the reporting module in the real identifier (see detail in the
Pools section above) — hence a second fix based on `GET /reporting/graphs` as the source of truth
rather than reconstruction, re-verified end-to-end via `curl` (resolving the real identifier +
calling `netdata_get_data` with that identifier → 900 points received for `disk`, 2 for
`disktemp`, consistent with the first pass).

The key was only used for read calls and wasn't kept (scratch files containing the responses
deleted after use); the user noted it expires on its own after ~20h, dedicated to that day's
tests.

## Not yet verified live

- **`aggregate = true` over a very long window (1 month)**: tested up to 15 min (900s) live
  during the verification above, with a consistent point count (900 for `disk`
  aggregate=true over 900s, so ~1 point/s over that short window) — behavior over 1 week
  or 1 month (real downsampling expected) remains to be observed.
- Graph rendering (multi-line CPU, Memory lines, Pools donut + per-pool detail + per-disk
  detail) only tested via the Gradle build (compilation + JVM unit tests) and via the raw API
  calls above — real visual rendering not yet observed on device/emulator.

## Tests to do

Every API shape (topology/scan, disk.query, cputemp/load/disk/disktemp legends,
composite identifier) is now curl-verified — see "Verified live" above. What remains is UI
testing (real rendering in the app) rather than format verification:

- [ ] Open the CPU and Memory cards on device/emulator: check that every section shows
  consistent, readable data (CPU: core order for temperature isn't necessarily numeric in the UI,
  check it stays readable; Memory: Available listed first, then
  Used, then ZFS Cache).
- [ ] Check the auto-refresh button (CPU, Memory, per-disk detail): turn it on, let it run,
  turn it off, leave the screen while it's running — confirm no call keeps firing after
  leaving the screen.
- [ ] Check the time-range selector (CPU, Memory, per-disk detail) on the large
  windows (1 week, 1 month): response time, real downsampling of `aggregate: true` over a
  long window (only tested up to 15 min so far), and that the graph stays readable
  — especially for the per-disk detail, where `disktemp` has very few points even over 15 min
  (2 points observed live, SMART temperature history is infrequent): check that it
  stays correct over a longer window.
- [ ] Open the Pools screen on device/emulator: check that each pool's merged card
  shows consistent data (Breakdown, Information, Last scrub), then the "Open this
  pool" button for each pool, and that the per-disk detail shown matches the
  right pool (especially with several pools).
- [ ] Check on a pool with a heterogeneous topology (several data-vdev shapes) that
  "Data Topology" correctly shows one group per shape, comma-separated (not encountered on
  the pools tested live, which each have only a single vdev shape).
- [ ] Open the pool detail screen on device/emulator: check that each disk has both
  cards (I/O then Temperature) with data shown (identifier bug fixed), with
  the correct identity (Type/Model/Serial) and Max/Mean/Min values consistent with the
  graph shown.
- [ ] Check the error case (server unreachable while on the graph screen).
- [ ] Check back navigation (top-left arrow) from every graph screen, including
  the two-level Pools → pool detail → Pools → dashboard path.
