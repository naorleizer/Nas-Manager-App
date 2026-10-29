package com.nasmanagerapp.data.dashboard

/** Loaded once when the dashboard opens — doesn't change often enough to be worth polling. */
data class SystemInfo(
    val hostname: String,
    val version: String,
    val model: String,
    val manufacturer: String,
    val physicalCores: Int,
    val logicalCores: Int,
    val totalMemoryBytes: Long,
    val uptimeSeconds: Double,
    val loadAverage1m: Double,
    val loadAverage5m: Double,
    val loadAverage15m: Double,
)

/** One ZFS pool, as shown in the Pool/Storage section. Polled every 2s. */
data class PoolSummary(
    val id: Int,
    val name: String,
    val status: String,
    val healthy: Boolean,
    val statusDetail: String?,
    val sizeBytes: Long,
    val allocatedBytes: Long,
    val freeBytes: Long,
    val fragmentationPercent: Int?,
)

/** CPU + memory snapshot from the netdata reporting graphs. Polled every 2s. */
data class LiveMetrics(
    val cpuTotalPercent: Double,
    val cpuPerCorePercent: List<Double>,
    val memoryAvailableBytes: Long,
    val zfsArcSizeBytes: Long,
)

/** One sample of a netdata reporting series, as returned by `reporting.netdata_get_data`. */
data class MetricHistoryPoint(val timestampSeconds: Long, val value: Double)

/**
 * Recent history for the graph screens (see `ui/dashboard/GraphScreen.kt`), fetched on demand —
 * unlike [LiveMetrics] this isn't polled, only loaded when a graph screen is opened.
 */
data class MetricsHistory(
    val cpuPercent: List<MetricHistoryPoint>,
    val memoryAvailableBytes: List<MetricHistoryPoint>,
    val zfsArcSizeBytes: List<MetricHistoryPoint>,
)

/** One labeled line in a multi-line history graph, e.g. a single CPU core. */
data class NamedSeries(val label: String, val points: List<MetricHistoryPoint>)

/**
 * History for the CPU graph screen: usage and temperature per core (plus a global/average line),
 * and system load average (short/mid/long term). Sourced from the `cpu`, `cputemp` and `load`
 * netdata reporting graphs — see [DashboardRepository.getCpuPageHistory].
 */
data class CpuPageHistory(
    val cpuUsagePercent: List<NamedSeries>,
    val cpuTemperatureCelsius: List<NamedSeries>,
    val loadAverage: List<NamedSeries>,
)

/**
 * One group of identically-shaped top-level data vdevs in a pool's topology, e.g. "1 x RAIDZ1 |
 * 4 wide | 1.82 TiB" is [count]=1, [type]="RAIDZ1", [width]=4, [sizeBytes]=that vdev's usable size.
 * [type] is "STRIPE" for a bare single-disk vdev (no redundancy).
 */
data class PoolVdevGroup(val type: String, val count: Int, val width: Int, val sizeBytes: Long)

/** Last scrub/resilver info for a pool, from its `scan` field. Null fields mean "never run yet". */
data class PoolScan(
    val function: String?,
    val state: String?,
    val endEpochSeconds: Long?,
    val durationSeconds: Long?,
    val errors: Int,
)

/**
 * Extra pool details beyond [PoolSummary] — heavier/rarer fields (topology, scrub history) loaded
 * on demand for the pool detail screen, not polled every 2s like the dashboard.
 */
data class PoolDetail(
    val dataVdevGroups: List<PoolVdevGroup>,
    val usableCapacityBytes: Long,
    val lastScan: PoolScan?,
    val disksWithZfsErrors: Int,
)

/**
 * One physical disk belonging to a pool: identity (for the "Disk I/O"/"Disk Temperature" section
 * headers) plus its recent I/O and temperature history — see
 * [DashboardRepository.getPoolDiskDetails].
 */
data class PoolDiskInfo(
    val name: String,
    val type: String?,
    val model: String?,
    val serial: String?,
    val ioSeries: List<NamedSeries>,
    val temperatureSeries: List<NamedSeries>,
)

/**
 * Severity of an [AlertInfo], from `alert.list`'s `level` field. Curl-verified values so far:
 * INFO, NOTICE, WARNING (see `ALERTS_TODO.md`) — the rest are TrueNAS's documented syslog-style
 * levels, not yet observed live; [UNKNOWN] is the fallback for anything that doesn't parse.
 */
enum class AlertLevel { INFO, NOTICE, WARNING, ERROR, CRITICAL, ALERT, EMERGENCY, UNKNOWN }

/**
 * One system alert, loaded on demand for the Alerts screen (drawer) — not polled. [message] is
 * `alert.list`'s `formatted` field (falls back to `text`) with its HTML markup stripped — see
 * [DashboardRepository.getAlerts].
 */
data class AlertInfo(
    val id: String,
    val level: AlertLevel,
    val message: String,
    val epochSeconds: Long?,
    val dismissed: Boolean,
)

/** Curl-verified enum values of `app.query`'s `state` field (see `APPS_TODO.md`); [UNKNOWN] is the fallback for anything that doesn't parse. */
enum class AppState { RUNNING, STOPPED, DEPLOYING, CRASHED, STOPPING, UNKNOWN }

/**
 * One installed app, for the drawer's "Apps" screen — not polled, loaded on demand. No live
 * CPU/memory/network/block-IO here: `app.stats`/`container.metrics` only exist as WebSocket
 * subscription events, not REST (curl-verified, see `APPS_TODO.md`), and this app is REST-only.
 */
data class AppInfo(
    val id: String,
    val title: String,
    val iconUrl: String?,
    val state: AppState,
    val version: String,
    val latestVersion: String?,
    val upgradeAvailable: Boolean,
)

/** Curl-verified job states from `core.get_jobs` (see `APPS_TODO.md`); [UNKNOWN] is the fallback for anything that doesn't parse. */
enum class JobState { WAITING, RUNNING, SUCCESS, FAILED, ABORTED, UNKNOWN }

/** Status of a middleware job (e.g. an in-flight `app.upgrade`), polled via `core.get_jobs`. */
data class JobStatus(val state: JobState, val progressPercent: Int?, val error: String?)

/**
 * "General settings" for the drawer's "System" screen, from `system.general`
 * (`GET /api/v2.0/system/general`) — only the fields actually shown are read; the response also
 * carries the web UI's TLS certificate/private key, deliberately never parsed into this app (see
 * [DashboardRepository.getSystemGeneral], `SYSTEM_TODO.md`).
 */
data class SystemGeneralSettings(
    val timezone: String,
    val keyboardLayout: String,
    val httpPort: Int,
    val httpsPort: Int,
    val httpsRedirect: Boolean,
    val httpsProtocols: List<String>,
)

/** One network interface, for the "Network" section of the "System" screen — see [NetworkInfo]. */
data class NetworkInterfaceInfo(
    val name: String,
    val type: String,
    val linkUp: Boolean,
    val dhcp: Boolean,
    val addresses: List<String>,
)

/**
 * Network configuration, for the "Network" section of the drawer's "System" screen — combines
 * `network.configuration` (hostname/domain/gateways/nameservers) and `interface` (per-NIC state)
 * in one call, see [DashboardRepository.getNetworkInfo].
 */
data class NetworkInfo(
    val hostname: String,
    val domain: String,
    val ipv4Gateway: String?,
    val ipv6Gateway: String?,
    val nameservers: List<String>,
    val interfaces: List<NetworkInterfaceInfo>,
)

/** One ZFS boot environment (OS snapshot), for the "Boot" section — see [BootInfo]. */
data class BootEnvironmentInfo(
    val id: String,
    val active: Boolean,
    val activated: Boolean,
    val createdEpochSeconds: Long?,
    val usedBytes: Long,
)

/**
 * Boot pool + boot environments, for the "Boot" section of the drawer's "System" screen — combines
 * `boot.get_state` (boot pool health/scrub, same shape as a regular ZFS pool) and
 * `boot.environment.query` (list of OS snapshots), see [DashboardRepository.getBootInfo].
 */
data class BootInfo(
    val poolName: String,
    val poolStatus: String,
    val poolHealthy: Boolean,
    val sizeBytes: Long,
    val allocatedBytes: Long,
    val lastScan: PoolScan?,
    val environments: List<BootEnvironmentInfo>,
)

/**
 * One system service (SMB, NFS, SSH...), for the "Services" section of the drawer's "System"
 * screen, from `service.query` (`GET /api/v2.0/service`) — see [DashboardRepository.getServices].
 * [name] is the internal middleware name used by start/stop, [label] the name shown in the
 * TrueNAS web UI (`cifs` → "SMB", `nvmet` → "NVMe-oF"...).
 */
data class ServiceInfo(
    val id: Int,
    val name: String,
    val label: String,
    val running: Boolean,
    val startOnBoot: Boolean,
)

/**
 * Pending, not-yet-saved edits to one service on the "System" screen — `null` means "unchanged".
 * See [DashboardRepository.setServiceRunning]/[DashboardRepository.setServiceStartOnBoot].
 */
data class ServiceChange(
    val service: ServiceInfo,
    val running: Boolean? = null,
    val startOnBoot: Boolean? = null,
)
