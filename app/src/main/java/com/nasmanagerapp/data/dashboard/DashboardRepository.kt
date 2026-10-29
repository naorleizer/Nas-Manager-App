package com.nasmanagerapp.data.dashboard

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import java.io.IOException
import java.time.OffsetDateTime
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class DashboardApiException(message: String) : Exception(message)

/**
 * Reads TrueNAS system/pool/reporting data for the dashboard.
 *
 * `system.info` is a plain snapshot (called once). CPU and memory come from
 * `reporting.netdata_get_data`, which only returns time-series graphs — there is no "current
 * value" endpoint — so each call asks for a short recent window (last [LIVE_WINDOW_SECONDS]
 * seconds, un-aggregated) and keeps the most recent sample, instead of the default hour-long,
 * ~3600-point series that a naive call would return every poll.
 *
 * Doesn't re-check the HTTP-risk consent itself: it relies on `baseUrlProvider` only ever
 * returning a URL that [TrueNasAuthRepository][com.nasmanagerapp.data.auth.TrueNasAuthRepository]
 * already used for a successful login (which required that consent for cleartext HTTP) and
 * persisted as-is.
 */
class DashboardRepository(
    private val okHttpClient: OkHttpClient,
    private val baseUrlProvider: () -> String,
) {
    private val gson = Gson()

    suspend fun getSystemInfo(): Result<SystemInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val dto = get("/api/v2.0/system/info", SystemInfoDto::class.java)
            SystemInfo(
                hostname = dto.hostname,
                version = dto.version,
                model = dto.model ?: "",
                manufacturer = dto.systemManufacturer ?: "",
                physicalCores = dto.physicalCores,
                logicalCores = dto.cores,
                totalMemoryBytes = dto.physmem,
                uptimeSeconds = dto.uptimeSeconds,
                loadAverage1m = dto.loadavg.getOrElse(0) { 0.0 },
                loadAverage5m = dto.loadavg.getOrElse(1) { 0.0 },
                loadAverage15m = dto.loadavg.getOrElse(2) { 0.0 },
            )
        }.toDashboardResult()
    }

    suspend fun getPools(): Result<List<PoolSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val dtos = get("/api/v2.0/pool", Array<PoolDto>::class.java)
            dtos.map {
                PoolSummary(
                    id = it.id,
                    name = it.name,
                    status = it.status,
                    healthy = it.healthy,
                    statusDetail = it.statusDetail,
                    sizeBytes = it.size,
                    allocatedBytes = it.allocated,
                    freeBytes = it.free,
                    fragmentationPercent = it.fragmentation?.toDoubleOrNull()?.toInt(),
                )
            }
        }.toDashboardResult()
    }

    suspend fun getLiveMetrics(): Result<LiveMetrics> = withContext(Dispatchers.IO) {
        runCatching {
            val nowSeconds = System.currentTimeMillis() / 1000
            val body = NetdataRequest(
                graphs = listOf(GraphRequest("cpu"), GraphRequest("memory"), GraphRequest("arcsize")),
                query = QueryRequest(
                    start = nowSeconds - LIVE_WINDOW_SECONDS,
                    end = nowSeconds,
                    aggregate = false,
                ),
            )
            val series = post(
                "/api/v2.0/reporting/netdata_get_data",
                body,
                Array<NetdataSeriesDto>::class.java,
            )
            extractLiveMetrics(series.toList())
                ?: throw DashboardApiException("No monitoring data available yet.")
        }.toDashboardResult()
    }

    /**
     * Recent history for the graph screens: same endpoint and graph names as [getLiveMetrics],
     * just a wider window with `aggregate = true` (netdata down-samples instead of returning every
     * raw point) so the response stays a reasonable size to plot.
     *
     * [windowSeconds] is the user-selected time range (5 min to 1 month, see `TimeRange` in
     * `GraphScreen.kt`), same as [getCpuPageHistory].
     */
    suspend fun getMetricsHistory(windowSeconds: Long): Result<MetricsHistory> = withContext(Dispatchers.IO) {
        runCatching {
            val nowSeconds = System.currentTimeMillis() / 1000
            val body = NetdataRequest(
                graphs = listOf(GraphRequest("cpu"), GraphRequest("memory"), GraphRequest("arcsize")),
                query = QueryRequest(
                    start = nowSeconds - windowSeconds,
                    end = nowSeconds,
                    aggregate = true,
                ),
            )
            val series = post(
                "/api/v2.0/reporting/netdata_get_data",
                body,
                Array<NetdataSeriesDto>::class.java,
            )
            extractMetricsHistory(series.toList())
                ?: throw DashboardApiException("No monitoring history available yet.")
        }.toDashboardResult()
    }

    /**
     * History for the CPU graph screen: usage, temperature and load average, all in one call.
     * Same shape of call as [getMetricsHistory] (wide aggregated window on `netdata_get_data`),
     * but different graphs — see [extractCpuPageHistory] for how each is parsed (curl-verified
     * against a real server, see `REPORTING_TODO.md`).
     *
     * [windowSeconds] is the user-selected time range (5 min to 1 month, see `TimeRange` in
     * `GraphScreen.kt`) rather than a fixed constant like [getMetricsHistory]'s.
     */
    suspend fun getCpuPageHistory(windowSeconds: Long): Result<CpuPageHistory> = withContext(Dispatchers.IO) {
        runCatching {
            val nowSeconds = System.currentTimeMillis() / 1000
            val body = NetdataRequest(
                graphs = listOf(GraphRequest("cpu"), GraphRequest("cputemp"), GraphRequest("load")),
                query = QueryRequest(
                    start = nowSeconds - windowSeconds,
                    end = nowSeconds,
                    aggregate = true,
                ),
            )
            val series = post(
                "/api/v2.0/reporting/netdata_get_data",
                body,
                Array<NetdataSeriesDto>::class.java,
            )
            extractCpuPageHistory(series.toList())
                ?: throw DashboardApiException("No CPU history available yet.")
        }.toDashboardResult()
    }

    /**
     * Extra details for one pool — topology (for "Data Topology"/"Usable Capacity"), scrub history
     * and per-disk ZFS error counts — loaded on demand for the pool detail screen, not polled with
     * [getPools]. `/pool/id/{id}` is the standard middlewared REST convention for fetching a single
     * record (collection endpoint + `/id/{pk}`), same shape as the plural `/pool` already used —
     * curl-verified, along with `topology` and `scan`'s shape, against a real server (see
     * `REPORTING_TODO.md`). Parsing stays defensive (nullable fields, [epochSecondsFromTrueNasDate]
     * tolerates several date shapes) in case other hardware/pool configurations vary.
     */
    suspend fun getPoolDetail(poolId: Int): Result<PoolDetail> = withContext(Dispatchers.IO) {
        runCatching {
            val dto = get("/api/v2.0/pool/id/$poolId", PoolDetailDto::class.java)
            extractPoolDetail(dto)
        }.toDashboardResult()
    }

    /**
     * One entry per physical disk in the pool: identity (name/type/model/serial, from `disk.query`)
     * plus recent I/O and temperature history (from the `disk`/`disktemp` netdata reporting graphs,
     * one series per disk via each graph request's `identifier`). Loaded on demand for the pool
     * detail screen's disk-by-disk breakdown, not polled.
     *
     * Disk names come from the same `topology` already fetched for [getPoolDetail] rather than a
     * separate call — see [extractDiskNames]. `GET /disk` mirrors the already-verified `GET /pool`
     * (plural collection, no filter) rather than guessing a query-filter syntax; matching disks to
     * pool members is done client-side by name.
     *
     * Curl-verified against a real server (see `REPORTING_TODO.md`): the `disk`/`disktemp` graphs'
     * `identifier` is *not* the bare disk name, but a composite `"{name} | Type: ... | Model: ... |
     * Serial: ..."` string. Critically, **this can't be reconstructed from `disk.query`'s `model`
     * field** — TrueNAS's reporting module formats/truncates it differently internally (observed on
     * a real disk: `disk.query`'s `model` had an underscore and a longer suffix than the `Model`
     * segment of the real graph identifier, which used a space instead and was shorter). So instead
     * of building the string client-side, the exact identifiers are read from `GET /reporting/graphs`
     * (which lists every valid identifier per graph) and matched to a disk by its `"{name} | "`
     * prefix — see [findDiskGraphIdentifier]. [diskGraphIdentifier] (client-side reconstruction) is
     * kept only as a last-resort fallback for a disk missing from that list.
     */
    suspend fun getPoolDiskDetails(poolId: Int, windowSeconds: Long): Result<List<PoolDiskInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val poolDto = get("/api/v2.0/pool/id/$poolId", PoolDetailDto::class.java)
            val diskNames = extractDiskNames(poolDto.topology)
            if (diskNames.isEmpty()) return@runCatching emptyList()

            val disksByName = get("/api/v2.0/disk", Array<DiskDto>::class.java).associateBy { it.name }
            val reportingGraphs = get("/api/v2.0/reporting/graphs", Array<ReportingGraphDto>::class.java)
            val diskGraphIdentifiers = reportingGraphs.firstOrNull { it.name == "disk" }?.identifiers.orEmpty()
            val identifiersByName = diskNames.associateWith { name ->
                findDiskGraphIdentifier(name, diskGraphIdentifiers) ?: diskGraphIdentifier(name, disksByName[name])
            }

            val nowSeconds = System.currentTimeMillis() / 1000
            val body = NetdataRequest(
                graphs = diskNames.flatMap {
                    val identifier = identifiersByName.getValue(it)
                    listOf(GraphRequest(name = "disk", identifier = identifier), GraphRequest(name = "disktemp", identifier = identifier))
                },
                query = QueryRequest(start = nowSeconds - windowSeconds, end = nowSeconds, aggregate = true),
            )
            val series = post("/api/v2.0/reporting/netdata_get_data", body, Array<NetdataSeriesDto>::class.java).toList()

            diskNames.map { name ->
                val diskDto = disksByName[name]
                val identifier = identifiersByName.getValue(name)
                val io = series.firstOrNull { it.name == "disk" && it.identifier == identifier }?.let(::extractDiskIoSeries).orEmpty()
                val temp = series.firstOrNull { it.name == "disktemp" && it.identifier == identifier }?.let(::extractDiskTempSeries).orEmpty()
                PoolDiskInfo(
                    name = name,
                    type = diskDto?.type,
                    model = diskDto?.model,
                    serial = diskDto?.serial,
                    ioSeries = io,
                    temperatureSeries = temp,
                )
            }
        }.toDashboardResult()
    }

    /**
     * Current alerts, for the drawer's "Alertes" screen — curl-verified against a real server (see
     * `ALERTS_TODO.md`): `GET /api/v2.0/alert/list` returns every alert (not just active ones, per
     * the `dismissed` field), so dismissed ones are filtered out here rather than assuming the
     * server already does it.
     */
    suspend fun getAlerts(): Result<List<AlertInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            extractAlerts(get("/api/v2.0/alert/list", Array<AlertDto>::class.java).toList())
        }.toDashboardResult()
    }

    /**
     * Dismisses one alert (the drawer's "Alertes" screen's per-alert "Dismiss" button) — mirrors the
     * TrueNAS web dashboard's own Dismiss action. Curl-verified against a real server (see
     * `ALERTS_TODO.md`): unlike every other POST in this class, the body isn't a JSON object but the
     * bare JSON-encoded uuid string — `{"uuid": "..."}` or `["..."]` both 422 ("Input should be a
     * valid string"). `gson.toJson("some-uuid")` already produces exactly that quoted-string body,
     * same as [postRaw] here.
     */
    suspend fun dismissAlert(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { postRaw("/api/v2.0/alert/dismiss", id) }.toDashboardResult()
    }

    /**
     * Installed apps, for the drawer's "Apps" screen — curl-verified against a real server (see
     * `APPS_TODO.md`): `GET /api/v2.0/app`. No CPU/memory/network/block-IO here (see [AppInfo]'s
     * doc) — everything shown ([AppState], `upgrade_available`) comes straight from this call.
     */
    suspend fun getApps(): Result<List<AppInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            get("/api/v2.0/app", Array<AppDto>::class.java).map { dto ->
                AppInfo(
                    id = dto.id,
                    title = dto.metadata?.title?.ifBlank { null } ?: dto.name,
                    iconUrl = dto.metadata?.icon,
                    state = parseAppState(dto.state),
                    version = dto.version,
                    latestVersion = dto.latestVersion,
                    upgradeAvailable = dto.upgradeAvailable,
                )
            }
        }.toDashboardResult()
    }

    /**
     * Starts an upgrade job for one app, returning its middleware job id to poll via
     * [getJobStatus] — curl-verified against a real server (see `APPS_TODO.md`): `app.upgrade` is a
     * middleware *job* (long-running), and `POST /api/v2.0/app/upgrade`'s body is
     * `{"app_name": "<id>"}` (a plain kwargs-shaped object, unlike [dismissAlert]'s bare string) —
     * the response is the bare job id, e.g. `105554`, not wrapped in an object.
     */
    suspend fun upgradeApp(appId: String): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            post("/api/v2.0/app/upgrade", AppUpgradeRequest(appId), Long::class.java)
        }.toDashboardResult()
    }

    /**
     * Starts a stopped app, returning its middleware job id to poll via [getJobStatus]. Per the
     * `api.truenas.com` docs (v25.10), `app.start` is a job taking a single positional
     * `app_name` — confirmed working on a real server, see `APPS_TODO.md`. The body is sent as a bare JSON
     * string, like [dismissAlert] (the other single-parameter method, where an object 422s), not an
     * object like [upgradeApp] (two parameters).
     */
    suspend fun startApp(appId: String): Result<Long> = withContext(Dispatchers.IO) {
        runCatching { post("/api/v2.0/app/start", appId, Long::class.java) }.toDashboardResult()
    }

    /** Stops a running app — same shape and same caveat as [startApp] (`app.stop`, also a job). */
    suspend fun stopApp(appId: String): Result<Long> = withContext(Dispatchers.IO) {
        runCatching { post("/api/v2.0/app/stop", appId, Long::class.java) }.toDashboardResult()
    }

    /**
     * Polls one middleware job's status — curl-verified against a real server (see
     * `APPS_TODO.md`): `GET /api/v2.0/core/get_jobs?id=<id>` returns an array (filtered to that one
     * job), not a single object.
     */
    suspend fun getJobStatus(jobId: Long): Result<JobStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val job = get("/api/v2.0/core/get_jobs?id=$jobId", Array<JobDto>::class.java).firstOrNull()
                ?: throw DashboardApiException("Task not found.")
            JobStatus(
                state = parseJobState(job.state),
                progressPercent = job.progress?.percent,
                error = job.error,
            )
        }.toDashboardResult()
    }

    /**
     * "General settings" for the drawer's "System" screen — curl-verified against a real server
     * (see `SYSTEM_TODO.md`): `GET /api/v2.0/system/general`. Only a handful of fields are declared
     * on [SystemGeneralDto] (timezone, keyboard layout, GUI ports/protocols) even though the real
     * response is much bigger — notably it embeds the web UI's TLS certificate *and private key* —
     * so nothing beyond what's actually shown ever gets deserialized.
     */
    suspend fun getSystemGeneral(): Result<SystemGeneralSettings> = withContext(Dispatchers.IO) {
        runCatching {
            val dto = get("/api/v2.0/system/general", SystemGeneralDto::class.java)
            SystemGeneralSettings(
                timezone = dto.timezone,
                keyboardLayout = dto.kbdmap,
                httpPort = dto.uiPort,
                httpsPort = dto.uiHttpsPort,
                httpsRedirect = dto.uiHttpsRedirect,
                httpsProtocols = dto.uiHttpsProtocols,
            )
        }.toDashboardResult()
    }

    /**
     * Network configuration for the "System" screen's "Network" section — curl-verified against a
     * real server (see `SYSTEM_TODO.md`): combines `GET /api/v2.0/network/configuration`
     * (hostname/domain/gateways/nameservers) and `GET /api/v2.0/interface` (per-NIC link
     * state/addresses), two separate calls since TrueNAS has no single endpoint for both. Per-NIC
     * IP addresses live under `state.aliases` (the live/kernel view), not the top-level `aliases`
     * (the statically configured ones, empty here since DHCP is used) — confirmed live.
     */
    suspend fun getNetworkInfo(): Result<NetworkInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val config = get("/api/v2.0/network/configuration", NetworkConfigurationDto::class.java)
            val interfaces = get("/api/v2.0/interface", Array<InterfaceDto>::class.java)
            NetworkInfo(
                hostname = config.hostname,
                domain = config.domain,
                ipv4Gateway = config.ipv4Gateway?.ifBlank { null },
                ipv6Gateway = config.ipv6Gateway?.ifBlank { null },
                nameservers = listOfNotNull(config.nameserver1, config.nameserver2, config.nameserver3)
                    .filter { it.isNotBlank() },
                interfaces = interfaces.map { iface ->
                    NetworkInterfaceInfo(
                        name = iface.name,
                        type = iface.type,
                        linkUp = iface.state?.linkState == "LINK_STATE_UP",
                        dhcp = iface.ipv4Dhcp,
                        addresses = iface.state?.aliases.orEmpty().map { "${it.address}/${it.netmask}" },
                    )
                },
            )
        }.toDashboardResult()
    }

    /**
     * Boot pool + boot environments for the "System" screen's "Boot" section — curl-verified
     * against a real server (see `SYSTEM_TODO.md`): `GET /api/v2.0/boot/get_state` (same response
     * shape as a regular `pool.query` entry) and `GET /api/v2.0/boot/environment/query`. Unlike
     * `boot.environment`'s dotted name, the REST path is `/boot/environment/query` (a "query"
     * CRUD-style method, confirmed via `GET /api/v2.0/openapi.json` after a plain
     * `/boot/environment` 404'd).
     */
    suspend fun getBootInfo(): Result<BootInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val pool = get("/api/v2.0/boot/get_state", BootPoolDto::class.java)
            val environments = get("/api/v2.0/boot/environment/query", Array<BootEnvironmentDto>::class.java)
            BootInfo(
                poolName = pool.name,
                poolStatus = pool.status,
                poolHealthy = pool.healthy,
                sizeBytes = pool.size,
                allocatedBytes = pool.allocated,
                lastScan = pool.scan?.let { s ->
                    val endSeconds = epochSecondsFromTrueNasDate(s.endTime)
                    val startSeconds = epochSecondsFromTrueNasDate(s.startTime)
                    PoolScan(
                        function = s.function,
                        state = s.state,
                        endEpochSeconds = endSeconds,
                        durationSeconds = if (endSeconds != null && startSeconds != null) (endSeconds - startSeconds).coerceAtLeast(0) else null,
                        errors = s.errors ?: 0,
                    )
                },
                environments = environments.map { env ->
                    BootEnvironmentInfo(
                        id = env.id,
                        active = env.active,
                        activated = env.activated,
                        createdEpochSeconds = epochSecondsFromTrueNasDate(env.created),
                        usedBytes = env.usedBytes,
                    )
                },
            )
        }.toDashboardResult()
    }

    /**
     * System services for the "System" screen's "Services" section — curl-verified against a real
     * server (TrueNAS 25.10, see `SYSTEM_TODO.md`): `GET /api/v2.0/service` returns every service
     * in one call, with its run state and its "start automatically" flag.
     */
    suspend fun getServices(): Result<List<ServiceInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            get("/api/v2.0/service", Array<ServiceDto>::class.java).toServiceInfos()
        }.toDashboardResult()
    }

    /**
     * Starts or stops one service — curl-verified against a real server (see `SYSTEM_TODO.md`):
     * `POST /api/v2.0/service/start` or `/service/stop` with `{"service": "<name>"}` answers a plain
     * `true`/`false` once the service has actually started/stopped (synchronous, no job to poll).
     * TrueNAS waits up to 120s by default, hence [serviceControlClient] instead of the 15s default.
     */
    suspend fun setServiceRunning(name: String, running: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val path = if (running) "/api/v2.0/service/start" else "/api/v2.0/service/stop"
            val requestBody = gson.toJson(ServiceControlRequest(name)).toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder().url(baseUrlProvider() + path).post(requestBody).build()
            val succeeded = execute(request, Boolean::class.java, serviceControlClient)
            if (!succeeded) {
                throw DashboardApiException(if (running) "Couldn't be started." else "Couldn't be stopped.")
            }
        }.toDashboardResult()
    }

    /**
     * Turns "start automatically" on/off for one service — curl-verified against a real server
     * (see `SYSTEM_TODO.md`): `PUT /api/v2.0/service/id/{id}` with `{"enable": bool}`, answers the
     * service id. Doesn't start/stop the service itself, see [setServiceRunning].
     */
    suspend fun setServiceStartOnBoot(id: Int, enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val requestBody = gson.toJson(ServiceUpdateRequest(enabled)).toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder().url(baseUrlProvider() + "/api/v2.0/service/id/$id").put(requestBody).build()
            execute(request, Int::class.java)
            Unit
        }.toDashboardResult()
    }

    private fun <T> get(path: String, type: Class<T>): T {
        val request = Request.Builder().url(baseUrlProvider() + path).get().build()
        return execute(request, type)
    }

    private fun <T> post(path: String, body: Any, type: Class<T>): T {
        val requestBody = gson.toJson(body).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(baseUrlProvider() + path).post(requestBody).build()
        return execute(request, type)
    }

    /** Like [post], but for calls whose response body is discarded (`null` on success) — see [dismissAlert]. */
    private fun postRaw(path: String, body: Any) {
        val requestBody = gson.toJson(body).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(baseUrlProvider() + path).post(requestBody).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw DashboardApiException("The server responded with an error (${response.code}).")
            }
        }
    }

    private fun <T> execute(request: Request, type: Class<T>, client: OkHttpClient = okHttpClient): T {
        client.newCall(request).execute().use { response ->
            val bodyString = response.body?.string()
            if (!response.isSuccessful) {
                throw DashboardApiException("The server responded with an error (${response.code}).")
            }
            if (bodyString.isNullOrEmpty()) {
                throw DashboardApiException("Empty response from the server.")
            }
            return gson.fromJson(bodyString, type)
        }
    }

    private fun <T> Result<T>.toDashboardResult(): Result<T> = recoverCatching { error ->
        throw when (error) {
            is DashboardApiException -> error
            is IOException -> DashboardApiException("Couldn't reach the server.")
            else -> DashboardApiException(error.message ?: "Unknown error.")
        }
    }

    /** Same client (auth, connection pool), longer read timeout for start/stop — see [setServiceRunning]. */
    private val serviceControlClient: OkHttpClient by lazy {
        okHttpClient.newBuilder().readTimeout(SERVICE_CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    }

    private companion object {
        const val LIVE_WINDOW_SECONDS = 10L
        // TrueNAS's own start/stop timeout is 120s; leave it room to answer before giving up.
        const val SERVICE_CONTROL_TIMEOUT_SECONDS = 130L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** Every sample of one legend column, sorted oldest-first — index 0 in each row is the timestamp. */
private fun NetdataSeriesDto.columnPoints(columnIndex: Int): List<MetricHistoryPoint> =
    data.mapNotNull { row ->
        val time = row.getOrNull(0) ?: return@mapNotNull null
        val value = row.getOrNull(columnIndex) ?: return@mapNotNull null
        MetricHistoryPoint(timestampSeconds = time.toLong(), value = value)
    }.sortedBy { it.timestampSeconds }

/**
 * Same legend-based extraction as [extractLiveMetrics], but keeps every sample instead of only the
 * most recent one — used to plot a history line instead of reading a single current value.
 */
internal fun extractMetricsHistory(series: List<NetdataSeriesDto>): MetricsHistory? {
    val cpu = series.firstOrNull { it.name == "cpu" } ?: return null
    val memory = series.firstOrNull { it.name == "memory" } ?: return null
    val arc = series.firstOrNull { it.name == "arcsize" } ?: return null

    val cpuTotalIndex = cpu.legend.indexOf("cpu")
    val availableIndex = memory.legend.indexOf("available")
    val arcSizeIndex = arc.legend.indexOf("size")
    if (cpuTotalIndex < 0 || availableIndex < 0 || arcSizeIndex < 0) return null

    return MetricsHistory(
        cpuPercent = cpu.columnPoints(cpuTotalIndex),
        memoryAvailableBytes = memory.columnPoints(availableIndex),
        zfsArcSizeBytes = arc.columnPoints(arcSizeIndex),
    )
}

/**
 * CPU usage: `cpu` graph, same shape already verified for [extractLiveMetrics] — a `"cpu"`
 * dimension for the global average, then one `"cpuN"` dimension per core.
 *
 * CPU temperature: `cputemp` graph. Curl-verified against a real server: legend is
 * `["time", "cpu0", "cpu2", "cpu1", "cpu3", "cpu", ...]` — per-core dimensions *not* necessarily in
 * numeric order, plus a `"cpu"` dimension that's the real server-computed average (same name as the
 * `cpu` usage graph's own aggregate — not `"cputemp"`). When present, that dimension is used
 * directly as "Average" instead of averaging the cores client-side; only falls back to a computed
 * average if no such dimension is found (e.g. different hardware/sensor layout).
 *
 * Load average: `load` graph, curl-verified to carry exactly `"shortterm"`/`"midterm"`/`"longterm"`.
 * Matched by name when the legend contains "short"/"mid"/"long" (case-insensitive), falling back to
 * position order otherwise.
 *
 * `cpu` is required (matches [extractMetricsHistory]'s behavior for the other graphs); `cputemp`
 * and `load` are best-effort and degrade to an empty list if missing, since not every system
 * reports sensors.
 */
internal fun extractCpuPageHistory(series: List<NetdataSeriesDto>): CpuPageHistory? {
    val cpu = series.firstOrNull { it.name == "cpu" } ?: return null
    val cpuTotalIndex = cpu.legend.indexOf("cpu")
    if (cpuTotalIndex < 0) return null

    val cpuUsage = listOf(NamedSeries("Average", cpu.columnPoints(cpuTotalIndex))) +
        (cpuTotalIndex + 1 until cpu.legend.size).mapIndexed { coreNumber, index ->
            NamedSeries("Core $coreNumber", cpu.columnPoints(index))
        }

    val cpuTemperature = series.firstOrNull { it.name == "cputemp" }
        ?.let(::extractCpuTemperatureSeries)
        ?: emptyList()

    val loadAverage = series.firstOrNull { it.name == "load" }
        ?.let(::extractLoadAverageSeries)
        ?: emptyList()

    return CpuPageHistory(
        cpuUsagePercent = cpuUsage,
        cpuTemperatureCelsius = cpuTemperature,
        loadAverage = loadAverage,
    )
}

private fun extractCpuTemperatureSeries(cpuTemp: NetdataSeriesDto): List<NamedSeries> {
    if (cpuTemp.legend.size < 2) return emptyList()

    val aggregateIndex = cpuTemp.legend.indexOf("cpu")
    val sensorIndices = (1 until cpuTemp.legend.size).filter { it != aggregateIndex }
    val sensors = sensorIndices.mapIndexed { position, index ->
        NamedSeries(prettifySensorLabel(cpuTemp.legend[index], position), cpuTemp.columnPoints(index))
    }

    val average = if (aggregateIndex >= 0) {
        NamedSeries("Average", cpuTemp.columnPoints(aggregateIndex))
    } else {
        val byTimestamp = sensors.flatMap { it.points }.groupBy { it.timestampSeconds }
        val computed = byTimestamp.entries
            .map { (time, points) -> MetricHistoryPoint(time, points.map { it.value }.average()) }
            .sortedBy { it.timestampSeconds }
        NamedSeries("Average", computed)
    }

    return listOf(average) + sensors
}

private fun prettifySensorLabel(rawLegendName: String, position: Int): String {
    val coreNumber = Regex("(?i)cpu(temp)?(\\d+)").find(rawLegendName)?.groupValues?.get(2)
    return coreNumber?.let { "Core $it" } ?: rawLegendName.ifBlank { "Sensor $position" }
}

private fun extractLoadAverageSeries(load: NetdataSeriesDto): List<NamedSeries> {
    val positionalLabels = listOf("Short term (1 min)", "Mid term (5 min)", "Long term (15 min)")
    return (1 until load.legend.size).mapIndexed { position, index ->
        val legendName = load.legend[index].lowercase()
        val label = when {
            "short" in legendName -> positionalLabels[0]
            "mid" in legendName -> positionalLabels[1]
            "long" in legendName -> positionalLabels[2]
            position < positionalLabels.size -> positionalLabels[position]
            else -> load.legend[index]
        }
        NamedSeries(label, load.columnPoints(index))
    }
}

/**
 * Picks the most recent sample of each series and maps it back to [LiveMetrics] using the
 * `legend` each series carries (`["time", "cpu", "cpu0", "cpu1", ...]` for cpu,
 * `["time", "available"]` for memory, `["time", "size"]` for arcsize), rather than assuming a
 * fixed data layout.
 */
internal fun extractLiveMetrics(series: List<NetdataSeriesDto>): LiveMetrics? {
    val cpu = series.firstOrNull { it.name == "cpu" } ?: return null
    val memory = series.firstOrNull { it.name == "memory" } ?: return null
    val arc = series.firstOrNull { it.name == "arcsize" } ?: return null
    val cpuPoint = cpu.data.maxByOrNull { it.getOrElse(0) { Double.MIN_VALUE } } ?: return null
    val memoryPoint = memory.data.maxByOrNull { it.getOrElse(0) { Double.MIN_VALUE } } ?: return null
    val arcPoint = arc.data.maxByOrNull { it.getOrElse(0) { Double.MIN_VALUE } } ?: return null

    val cpuTotalIndex = cpu.legend.indexOf("cpu")
    val availableIndex = memory.legend.indexOf("available")
    val arcSizeIndex = arc.legend.indexOf("size")
    if (cpuTotalIndex < 0 || availableIndex < 0 || arcSizeIndex < 0) return null

    val cpuTotal = cpuPoint.getOrNull(cpuTotalIndex) ?: return null
    val available = memoryPoint.getOrNull(availableIndex) ?: return null
    val arcSize = arcPoint.getOrNull(arcSizeIndex) ?: return null
    val perCore = (cpuTotalIndex + 1 until cpu.legend.size).mapNotNull { cpuPoint.getOrNull(it) }

    return LiveMetrics(
        cpuTotalPercent = cpuTotal,
        cpuPerCorePercent = perCore,
        memoryAvailableBytes = available.toLong(),
        zfsArcSizeBytes = arcSize.toLong(),
    )
}

private data class SystemInfoDto(
    val version: String,
    val hostname: String,
    val physmem: Long,
    val model: String?,
    val cores: Int,
    @SerializedName("physical_cores") val physicalCores: Int,
    val loadavg: List<Double>,
    @SerializedName("uptime_seconds") val uptimeSeconds: Double,
    @SerializedName("system_manufacturer") val systemManufacturer: String?,
)

private data class PoolDto(
    val id: Int,
    val name: String,
    val status: String,
    val healthy: Boolean,
    @SerializedName("status_detail") val statusDetail: String?,
    val size: Long,
    val allocated: Long,
    val free: Long,
    val fragmentation: String?,
)

/**
 * Groups a pool's top-level `topology.data` vdevs by shape (type/width/size) for "Data Topology",
 * reads `scan` for the last scrub, and walks the whole topology (all vdev categories, not just
 * `data`) for [PoolDetail.disksWithZfsErrors].
 */
internal fun extractPoolDetail(dto: PoolDetailDto): PoolDetail {
    val dataVdevGroups = dto.topology?.data.orEmpty()
        .map { vdev ->
            val isRedundant = vdev.type in REDUNDANT_VDEV_TYPES
            val type = if (isRedundant) requireNotNull(vdev.type) else "STRIPE"
            val width = if (isRedundant) vdev.children.orEmpty().size.coerceAtLeast(1) else 1
            Triple(type, width, vdev.stats?.size ?: 0L)
        }
        .groupingBy { it }
        .eachCount()
        .map { (shape, count) -> PoolVdevGroup(type = shape.first, count = count, width = shape.second, sizeBytes = shape.third) }

    val scan = dto.scan?.let { s ->
        val endSeconds = epochSecondsFromTrueNasDate(s.endTime)
        val startSeconds = epochSecondsFromTrueNasDate(s.startTime)
        PoolScan(
            function = s.function,
            state = s.state,
            endEpochSeconds = endSeconds,
            durationSeconds = if (endSeconds != null && startSeconds != null) (endSeconds - startSeconds).coerceAtLeast(0) else null,
            errors = s.errors ?: 0,
        )
    }

    return PoolDetail(
        dataVdevGroups = dataVdevGroups,
        usableCapacityBytes = dto.size,
        lastScan = scan,
        disksWithZfsErrors = countDisksWithErrors(dto.topology),
    )
}

private val REDUNDANT_VDEV_TYPES = setOf("RAIDZ1", "RAIDZ2", "RAIDZ3", "MIRROR")

private fun countDisksWithErrors(topology: TopologyDto?): Int {
    if (topology == null) return 0
    val topLevelVdevs = listOfNotNull(
        topology.data,
        topology.log,
        topology.cache,
        topology.spare,
        topology.special,
        topology.dedup,
    ).flatten()

    fun VDevDto.hasErrors(): Boolean =
        (stats?.readErrors ?: 0) > 0 || (stats?.writeErrors ?: 0) > 0 || (stats?.checksumErrors ?: 0) > 0

    fun countLeaves(vdev: VDevDto): Int {
        val children = vdev.children.orEmpty()
        return if (children.isEmpty()) {
            if (vdev.hasErrors()) 1 else 0
        } else {
            children.sumOf(::countLeaves)
        }
    }

    return topLevelVdevs.sumOf(::countLeaves)
}

/** Every leaf disk's bare device name (e.g. "sda"), across every vdev category, deduplicated. */
internal fun extractDiskNames(topology: TopologyDto?): List<String> {
    if (topology == null) return emptyList()
    val topLevelVdevs = listOfNotNull(
        topology.data,
        topology.log,
        topology.cache,
        topology.spare,
        topology.special,
        topology.dedup,
    ).flatten()

    val names = mutableListOf<String>()
    fun walk(vdev: VDevDto) {
        val children = vdev.children.orEmpty()
        if (children.isEmpty()) {
            vdev.disk?.let(names::add)
        } else {
            children.forEach(::walk)
        }
    }
    topLevelVdevs.forEach(::walk)
    return names.distinct()
}

/**
 * Picks the real identifier `GET /reporting/graphs` reports for [diskName] out of [allIdentifiers]
 * (that graph's full `identifiers` list) by matching its `"{name} | "` prefix — e.g. for `"sdc"`,
 * matches `"sdc | Type: HDD | Model: Example Model | Serial: EXAMPLE0001"`. Preferred over
 * [diskGraphIdentifier] (client-side reconstruction) because the `Model` segment doesn't always
 * match `disk.query`'s `model` field verbatim — see [DashboardRepository.getPoolDiskDetails].
 */
internal fun findDiskGraphIdentifier(diskName: String, allIdentifiers: List<String>): String? =
    allIdentifiers.firstOrNull { it.startsWith("$diskName | ") }

/**
 * Last-resort fallback for a disk absent from `GET /reporting/graphs`'s identifiers list: rebuilds
 * the same composite shape client-side from `disk.query` fields. Not guaranteed to match the real
 * identifier (see [findDiskGraphIdentifier]'s doc), but better than not attempting the graph call.
 */
internal fun diskGraphIdentifier(name: String, disk: DiskDto?): String =
    "$name | Type: ${disk?.type ?: "—"} | Model: ${disk?.model ?: "—"} | Serial: ${disk?.serial ?: "—"}"

/**
 * `disk` graph: curl-verified legend `["time", "reads", "writes"]`, unit Kibibytes/s (confirmed via
 * `GET /reporting/graphs`'s `vertical_label`) — see [formatMebibytesPerSecond] in
 * `DashboardFormat.kt` for the KiB/s → MiB/s conversion. Matched by name (case-insensitive
 * substring) when present, falling back to position otherwise — same defensive pattern as
 * [extractLoadAverageSeries], kept in case dimension order or naming ever differs.
 */
internal fun extractDiskIoSeries(series: NetdataSeriesDto): List<NamedSeries> {
    if (series.legend.size < 2) return emptyList()
    val positionalLabels = listOf("Read", "Write")
    return (1 until series.legend.size).mapIndexed { position, index ->
        val legendName = series.legend[index].lowercase()
        val label = when {
            "read" in legendName -> positionalLabels[0]
            "write" in legendName -> positionalLabels[1]
            position < positionalLabels.size -> positionalLabels[position]
            else -> series.legend[index]
        }
        NamedSeries(label, series.columnPoints(index))
    }
}

/** `disktemp` graph: curl-verified legend `["time", "temperature_value"]`, unit Celsius. */
internal fun extractDiskTempSeries(series: NetdataSeriesDto): List<NamedSeries> {
    if (series.legend.size < 2) return emptyList()
    return listOf(NamedSeries("Temperature", series.columnPoints(1)))
}

/**
 * TrueNAS middleware serializes `datetime` fields as `{"$date": epochMillis}` — curl-verified for
 * `pool.query`'s `scan.start_time`/`end_time` over REST. Also tolerates a bare epoch number
 * (seconds or millis, guessed from magnitude — 10 billion is a safe cutoff between the two for any
 * date between 2001 and 2286) or an ISO-8601 string as a fallback for other fields/versions, and
 * returns null rather than throwing on anything unrecognized.
 */
private fun epochSecondsFromTrueNasDate(element: JsonElement?): Long? {
    if (element == null || element.isJsonNull) return null
    return runCatching {
        when {
            element.isJsonObject -> element.asJsonObject.get("\$date")?.asLong?.let { it / 1000 }
            element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> {
                val raw = element.asJsonPrimitive.asLong
                if (raw > 10_000_000_000L) raw / 1000 else raw
            }
            element.isJsonPrimitive && element.asJsonPrimitive.isString ->
                OffsetDateTime.parse(element.asJsonPrimitive.asString).toEpochSecond()
            else -> null
        }
    }.getOrNull()
}

/** Filters out already-dismissed alerts, then maps each [AlertDto] to the domain [AlertInfo]. */
internal fun extractAlerts(dtos: List<AlertDto>): List<AlertInfo> =
    dtos.filterNot { it.dismissed }.map { dto ->
        AlertInfo(
            id = dto.id,
            level = parseAlertLevel(dto.level),
            message = stripAlertHtml(dto.formatted ?: dto.text ?: ""),
            epochSeconds = epochSecondsFromTrueNasDate(dto.datetime),
            dismissed = dto.dismissed,
        )
    }

/** `alert.list`'s `level` is a free-form string server-side — falls back to [AlertLevel.UNKNOWN] rather than throwing on a value not yet seen live. */
internal fun parseAlertLevel(raw: String): AlertLevel =
    runCatching { AlertLevel.valueOf(raw.uppercase(Locale.ROOT)) }.getOrDefault(AlertLevel.UNKNOWN)

/**
 * `formatted` carries basic HTML markup (curl-verified: `<br>` and `<a href="...">…</a>`) — there's
 * no HTML rendering in the UI, so `<br>` becomes a newline and every other tag is dropped, keeping
 * its inner text (e.g. a link's label).
 */
internal fun stripAlertHtml(formatted: String): String =
    formatted
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .trim()

internal data class AlertDto(
    val id: String,
    val level: String,
    val text: String?,
    val formatted: String?,
    val dismissed: Boolean,
    val datetime: JsonElement?,
)

/** `app.query`'s `state` is a free-form string server-side — falls back to [AppState.UNKNOWN] rather than throwing on a value not yet seen live. */
internal fun parseAppState(raw: String): AppState =
    runCatching { AppState.valueOf(raw.uppercase(Locale.ROOT)) }.getOrDefault(AppState.UNKNOWN)

/** `core.get_jobs`'s `state` is a free-form string server-side — falls back to [JobState.UNKNOWN] rather than throwing on a value not yet seen live. */
internal fun parseJobState(raw: String): JobState =
    runCatching { JobState.valueOf(raw.uppercase(Locale.ROOT)) }.getOrDefault(JobState.UNKNOWN)

internal data class AppMetadataDto(val title: String?, val icon: String?)

internal data class AppDto(
    val id: String,
    val name: String,
    val state: String,
    val version: String,
    @SerializedName("latest_version") val latestVersion: String?,
    @SerializedName("upgrade_available") val upgradeAvailable: Boolean,
    val metadata: AppMetadataDto?,
)

internal data class AppUpgradeRequest(@SerializedName("app_name") val appName: String)

internal data class JobProgressDto(val percent: Int?)

internal data class JobDto(val state: String, val progress: JobProgressDto?, val error: String?)

internal data class PoolDetailDto(val size: Long, val topology: TopologyDto?, val scan: ScanDto?)

internal data class TopologyDto(
    val data: List<VDevDto>?,
    val log: List<VDevDto>?,
    val cache: List<VDevDto>?,
    val spare: List<VDevDto>?,
    val special: List<VDevDto>?,
    val dedup: List<VDevDto>?,
)

internal data class VDevDto(
    val type: String?,
    val stats: VDevStatsDto?,
    val children: List<VDevDto>?,
    val disk: String? = null,
)

internal data class VDevStatsDto(
    val size: Long?,
    @SerializedName("read_errors") val readErrors: Long?,
    @SerializedName("write_errors") val writeErrors: Long?,
    @SerializedName("checksum_errors") val checksumErrors: Long?,
)

internal data class ScanDto(
    val function: String?,
    val state: String?,
    @SerializedName("start_time") val startTime: JsonElement?,
    @SerializedName("end_time") val endTime: JsonElement?,
    val errors: Int?,
)

/** Only the fields actually used by [DashboardRepository.getSystemGeneral] — see its doc for why. */
internal data class SystemGeneralDto(
    val timezone: String,
    val kbdmap: String,
    @SerializedName("ui_port") val uiPort: Int,
    @SerializedName("ui_httpsport") val uiHttpsPort: Int,
    @SerializedName("ui_httpsredirect") val uiHttpsRedirect: Boolean,
    @SerializedName("ui_httpsprotocols") val uiHttpsProtocols: List<String>,
)

internal data class NetworkConfigurationDto(
    val hostname: String,
    val domain: String,
    @SerializedName("ipv4gateway") val ipv4Gateway: String?,
    @SerializedName("ipv6gateway") val ipv6Gateway: String?,
    val nameserver1: String?,
    val nameserver2: String?,
    val nameserver3: String?,
)

internal data class InterfaceAliasDto(val type: String, val address: String, val netmask: Int)

internal data class InterfaceStateDto(
    @SerializedName("link_state") val linkState: String?,
    val aliases: List<InterfaceAliasDto>?,
)

internal data class InterfaceDto(
    val name: String,
    val type: String,
    val state: InterfaceStateDto?,
    @SerializedName("ipv4_dhcp") val ipv4Dhcp: Boolean,
)

/** Same top-level shape as a `pool.query` entry (boot pool isn't a regular pool, but the middleware serializes it identically). */
internal data class BootPoolDto(
    val name: String,
    val status: String,
    val healthy: Boolean,
    val size: Long,
    val allocated: Long,
    val scan: ScanDto?,
)

internal data class BootEnvironmentDto(
    val id: String,
    val active: Boolean,
    val activated: Boolean,
    val created: JsonElement?,
    @SerializedName("used_bytes") val usedBytes: Long,
)

internal data class ServiceControlRequest(val service: String)

internal data class ServiceUpdateRequest(val enable: Boolean)

internal data class ServiceDto(
    val id: Int,
    val service: String,
    val enable: Boolean,
    val state: String,
)

/**
 * Internal middleware names → the labels shown in the TrueNAS web UI (System > Services). Only
 * names that differ from a plain uppercase are listed; anything else (`ftp`, `nfs`, `ssh`...) is
 * shown uppercased, so a service added by a future TrueNAS version still shows up readably.
 */
private val SERVICE_LABELS = mapOf(
    "cifs" to "SMB",
    "iscsitarget" to "iSCSI",
    "nvmet" to "NVMe-oF",
)

internal fun serviceLabel(name: String): String = SERVICE_LABELS[name] ?: name.uppercase(Locale.ROOT)

/** Maps `service.query` entries to [ServiceInfo], sorted by label like the TrueNAS web UI. */
internal fun Array<ServiceDto>.toServiceInfos(): List<ServiceInfo> =
    map { dto ->
        ServiceInfo(
            id = dto.id,
            name = dto.service,
            label = serviceLabel(dto.service),
            running = dto.state == "RUNNING",
            startOnBoot = dto.enable,
        )
    }.sortedBy { it.label.lowercase(Locale.ROOT) }

internal data class NetdataSeriesDto(
    val name: String,
    val legend: List<String>,
    val data: List<List<Double>>,
    val identifier: String? = null,
)

internal data class DiskDto(val name: String, val type: String?, val model: String?, val serial: String?)

/** One entry of `GET /reporting/graphs` — `identifiers` is the authoritative list of valid instance identifiers for that graph. */
internal data class ReportingGraphDto(val name: String, val identifiers: List<String>?)

private data class NetdataRequest(val graphs: List<GraphRequest>, val query: QueryRequest)
private data class GraphRequest(val name: String, val identifier: String? = null)
private data class QueryRequest(val start: Long, val end: Long, val aggregate: Boolean)
