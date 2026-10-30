# REST → WebSocket JSON-RPC migration

The app speaks JSON-RPC 2.0 over the TrueNAS `/api/current` WebSocket (the table below maps the
former REST `/api/v2.0` routes). Wire contract probed live against **TrueNAS 25.10.x on 2026-10-30**.

- Request: `{"jsonrpc":"2.0","id":"<uuid>","method":"<m>","params":[...]}` — `params` must be an array.
- Auth: `auth.login_ex` on the same socket (`API_KEY_PLAIN` first if the secret looks like an
  API key — never over `ws://` — with a `PASSWORD_PLAIN` fallback; otherwise `PASSWORD_PLAIN`), then `core.set_options [{"legacy_jobs":false}]` (ignored on -32601).
- Query filters are positional arg 0 and three levels deep: `[[["id","=",3]]]`.
- Job-yielding calls (`app.upgrade`/`start`/`stop`) resolve from the direct numeric result or from a
  `collection_update` on `core.get_jobs` whose `fields.message_ids` contains the call id; 15 s cap.
- Notifications (no `id`) are otherwise ignored; an `error` frame with `"id": null` fails every
  pending call. Keepalive: OkHttp `pingInterval(20s)`.

| Old REST | New call (params on the wire) |
|---|---|
| GET /api/v2.0/system/info | `system.info` `[]` |
| GET /api/v2.0/pool | `pool.query` `[]` |
| GET /api/v2.0/pool/id/{id} | `pool.query` `[[["id","=",<id>]]]` |
| GET /api/v2.0/disk | `disk.query` `[]` |
| GET /api/v2.0/reporting/graphs | `reporting.netdata_graphs` `[]` |
| POST /api/v2.0/reporting/netdata_get_data | `reporting.netdata_get_data` `[<graphs>, <query>]` |
| GET /api/v2.0/alert/list | `alert.list` `[]` |
| POST /api/v2.0/alert/dismiss | `alert.dismiss` `["<uuid>"]` |
| GET /api/v2.0/app | `app.query` `[]` |
| POST /api/v2.0/app/upgrade | `app.upgrade` `["<name>"]` (job) |
| POST /api/v2.0/app/start | `app.start` `["<name>"]` (job) |
| POST /api/v2.0/app/stop | `app.stop` `["<name>"]` (job) |
| GET /api/v2.0/core/get_jobs?id=N | `core.get_jobs` `[[["id","=",N]]]` |
| GET /api/v2.0/system/general | `system.general.config` `[]` (renamed) |
| GET /api/v2.0/network/configuration | `network.configuration.config` `[]` (renamed) |
| GET /api/v2.0/interface | `interface.query` `[]` |
| GET /api/v2.0/boot/get_state | `boot.get_state` `[]` |
| GET /api/v2.0/boot/environment/query | `boot.environment.query` `[]` (renamed) |
| GET /api/v2.0/service | `service.query` `[]` |
| POST /api/v2.0/service/start | `service.start` `["<name>"]` (sync, up to 120 s; app timeout 130 s) |
| POST /api/v2.0/service/stop | `service.stop` `["<name>"]` (same) |
| PUT /api/v2.0/service/id/{id} | `service.update` `[<id>, {"enable": <bool>}]` |
