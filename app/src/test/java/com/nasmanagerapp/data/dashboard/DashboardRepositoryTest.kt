package com.nasmanagerapp.data.dashboard

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardRepositoryTest {

    @Test
    fun `picks the most recent sample and maps it using each series' legend`() {
        val series = listOf(
            NetdataSeriesDto(
                name = "cpu",
                legend = listOf("time", "cpu", "cpu0", "cpu1"),
                data = listOf(
                    listOf(100.0, 5.0, 4.0, 6.0),
                    listOf(101.0, 23.0, 8.0, 38.0),
                ),
            ),
            NetdataSeriesDto(
                name = "memory",
                legend = listOf("time", "available"),
                data = listOf(
                    listOf(100.0, 3_600_000_000.0),
                    listOf(101.0, 3_606_524_000.0),
                ),
            ),
            NetdataSeriesDto(
                name = "arcsize",
                legend = listOf("time", "size"),
                data = listOf(
                    listOf(100.0, 6_400_000_000.0),
                    listOf(101.0, 6_483_481_000.0),
                ),
            ),
        )

        val metrics = extractLiveMetrics(series)

        assertEquals(23.0, metrics?.cpuTotalPercent)
        assertEquals(listOf(8.0, 38.0), metrics?.cpuPerCorePercent)
        assertEquals(3_606_524_000L, metrics?.memoryAvailableBytes)
        assertEquals(6_483_481_000L, metrics?.zfsArcSizeBytes)
    }

    @Test
    fun `returns null when the cpu series is missing`() {
        val series = listOf(
            NetdataSeriesDto(
                name = "memory",
                legend = listOf("time", "available"),
                data = listOf(listOf(100.0, 3_600_000_000.0)),
            ),
            NetdataSeriesDto(
                name = "arcsize",
                legend = listOf("time", "size"),
                data = listOf(listOf(100.0, 6_400_000_000.0)),
            ),
        )

        assertNull(extractLiveMetrics(series))
    }

    @Test
    fun `returns null when a series has no data points yet`() {
        val series = listOf(
            NetdataSeriesDto(name = "cpu", legend = listOf("time", "cpu"), data = emptyList()),
            NetdataSeriesDto(
                name = "memory",
                legend = listOf("time", "available"),
                data = listOf(listOf(100.0, 3_600_000_000.0)),
            ),
            NetdataSeriesDto(
                name = "arcsize",
                legend = listOf("time", "size"),
                data = listOf(listOf(100.0, 6_400_000_000.0)),
            ),
        )

        assertNull(extractLiveMetrics(series))
    }

    @Test
    fun `keeps every sample sorted by timestamp for the history graphs`() {
        val series = listOf(
            NetdataSeriesDto(
                name = "cpu",
                legend = listOf("time", "cpu", "cpu0", "cpu1"),
                // Deliberately out of order, like a real netdata response isn't guaranteed to be.
                data = listOf(
                    listOf(101.0, 23.0, 8.0, 38.0),
                    listOf(100.0, 5.0, 4.0, 6.0),
                ),
            ),
            NetdataSeriesDto(
                name = "memory",
                legend = listOf("time", "available"),
                data = listOf(
                    listOf(100.0, 3_600_000_000.0),
                    listOf(101.0, 3_606_524_000.0),
                ),
            ),
            NetdataSeriesDto(
                name = "arcsize",
                legend = listOf("time", "size"),
                data = listOf(
                    listOf(100.0, 6_400_000_000.0),
                    listOf(101.0, 6_483_481_000.0),
                ),
            ),
        )

        val history = extractMetricsHistory(series)

        assertEquals(
            listOf(MetricHistoryPoint(100, 5.0), MetricHistoryPoint(101, 23.0)),
            history?.cpuPercent,
        )
        assertEquals(
            listOf(MetricHistoryPoint(100, 3_600_000_000.0), MetricHistoryPoint(101, 3_606_524_000.0)),
            history?.memoryAvailableBytes,
        )
        assertEquals(
            listOf(MetricHistoryPoint(100, 6_400_000_000.0), MetricHistoryPoint(101, 6_483_481_000.0)),
            history?.zfsArcSizeBytes,
        )
    }

    @Test
    fun `returns null history when the memory series is missing`() {
        val series = listOf(
            NetdataSeriesDto(name = "cpu", legend = listOf("time", "cpu"), data = listOf(listOf(100.0, 5.0))),
            NetdataSeriesDto(
                name = "arcsize",
                legend = listOf("time", "size"),
                data = listOf(listOf(100.0, 6_400_000_000.0)),
            ),
        )

        assertNull(extractMetricsHistory(series))
    }

    @Test
    fun `splits cpu usage into a global line plus one per core`() {
        val series = listOf(
            NetdataSeriesDto(
                name = "cpu",
                legend = listOf("time", "cpu", "cpu0", "cpu1"),
                data = listOf(listOf(100.0, 23.0, 8.0, 38.0)),
            ),
        )

        val history = extractCpuPageHistory(series)

        assertEquals(
            listOf(
                NamedSeries("Average", listOf(MetricHistoryPoint(100, 23.0))),
                NamedSeries("Core 0", listOf(MetricHistoryPoint(100, 8.0))),
                NamedSeries("Core 1", listOf(MetricHistoryPoint(100, 38.0))),
            ),
            history?.cpuUsagePercent,
        )
    }

    @Test
    fun `falls back to a client-side average temperature line when no cpu dimension is present`() {
        val series = listOf(
            NetdataSeriesDto(name = "cpu", legend = listOf("time", "cpu"), data = listOf(listOf(100.0, 5.0))),
            NetdataSeriesDto(
                name = "cputemp",
                legend = listOf("time", "cputemp0", "cputemp1"),
                data = listOf(listOf(100.0, 40.0, 50.0)),
            ),
        )

        val history = extractCpuPageHistory(series)

        assertEquals(
            listOf(
                NamedSeries("Average", listOf(MetricHistoryPoint(100, 45.0))),
                NamedSeries("Core 0", listOf(MetricHistoryPoint(100, 40.0))),
                NamedSeries("Core 1", listOf(MetricHistoryPoint(100, 50.0))),
            ),
            history?.cpuTemperatureCelsius,
        )
    }

    @Test
    fun `uses the real server-computed cpu dimension as Average when present, out-of-order cores included`() {
        // Real shape curl-verified against a live TrueNAS server: cores aren't necessarily in
        // numeric legend order, and "cpu" (not "cputemp") is the pre-computed average.
        val series = listOf(
            NetdataSeriesDto(name = "cpu", legend = listOf("time", "cpu"), data = listOf(listOf(100.0, 5.0))),
            NetdataSeriesDto(
                name = "cputemp",
                legend = listOf("time", "cpu0", "cpu2", "cpu1", "cpu3", "cpu"),
                data = listOf(listOf(100.0, 45.0, 45.0, 50.0, 50.0, 47.0)),
            ),
        )

        val history = extractCpuPageHistory(series)

        assertEquals(
            listOf(
                NamedSeries("Average", listOf(MetricHistoryPoint(100, 47.0))),
                NamedSeries("Core 0", listOf(MetricHistoryPoint(100, 45.0))),
                NamedSeries("Core 2", listOf(MetricHistoryPoint(100, 45.0))),
                NamedSeries("Core 1", listOf(MetricHistoryPoint(100, 50.0))),
                NamedSeries("Core 3", listOf(MetricHistoryPoint(100, 50.0))),
            ),
            history?.cpuTemperatureCelsius,
        )
    }

    @Test
    fun `matches load average dimensions by short-mid-long term, case-insensitively`() {
        val series = listOf(
            NetdataSeriesDto(name = "cpu", legend = listOf("time", "cpu"), data = listOf(listOf(100.0, 5.0))),
            NetdataSeriesDto(
                name = "load",
                legend = listOf("time", "Longterm", "Shortterm", "Midterm"),
                data = listOf(listOf(100.0, 0.33, 0.31, 0.39)),
            ),
        )

        val history = extractCpuPageHistory(series)

        assertEquals(
            listOf(
                NamedSeries("Long term (15 min)", listOf(MetricHistoryPoint(100, 0.33))),
                NamedSeries("Short term (1 min)", listOf(MetricHistoryPoint(100, 0.31))),
                NamedSeries("Mid term (5 min)", listOf(MetricHistoryPoint(100, 0.39))),
            ),
            history?.loadAverage,
        )
    }

    @Test
    fun `degrades gracefully when cputemp and load are missing, since not every system reports sensors`() {
        val series = listOf(
            NetdataSeriesDto(name = "cpu", legend = listOf("time", "cpu"), data = listOf(listOf(100.0, 5.0))),
        )

        val history = extractCpuPageHistory(series)

        assertEquals(emptyList<NamedSeries>(), history?.cpuTemperatureCelsius)
        assertEquals(emptyList<NamedSeries>(), history?.loadAverage)
    }

    @Test
    fun `returns null cpu page history when the cpu series itself is missing`() {
        val series = listOf(
            NetdataSeriesDto(
                name = "load",
                legend = listOf("time", "shortterm"),
                data = listOf(listOf(100.0, 0.31)),
            ),
        )

        assertNull(extractCpuPageHistory(series))
    }

    @Test
    fun `groups identical data vdevs into one topology group with the right width and size`() {
        fun disk() = VDevDto(type = "DISK", stats = null, children = null)
        val dto = PoolDetailDto(
            size = 5_670_000_000_000L,
            topology = TopologyDto(
                data = listOf(
                    VDevDto(
                        type = "RAIDZ1",
                        stats = VDevStatsDto(size = 2_000_000_000_000L, readErrors = 0, writeErrors = 0, checksumErrors = 0),
                        children = listOf(disk(), disk(), disk(), disk()),
                    ),
                ),
                log = null,
                cache = null,
                spare = null,
                special = null,
                dedup = null,
            ),
            scan = null,
        )

        val detail = extractPoolDetail(dto)

        assertEquals(
            listOf(PoolVdevGroup(type = "RAIDZ1", count = 1, width = 4, sizeBytes = 2_000_000_000_000L)),
            detail.dataVdevGroups,
        )
        assertEquals(5_670_000_000_000L, detail.usableCapacityBytes)
    }

    @Test
    fun `treats a bare disk vdev as a 1-wide STRIPE`() {
        val dto = PoolDetailDto(
            size = 1_000_000_000_000L,
            topology = TopologyDto(
                data = listOf(VDevDto(type = "DISK", stats = VDevStatsDto(1_000_000_000_000L, 0, 0, 0), children = null)),
                log = null,
                cache = null,
                spare = null,
                special = null,
                dedup = null,
            ),
            scan = null,
        )

        val detail = extractPoolDetail(dto)

        assertEquals(
            listOf(PoolVdevGroup(type = "STRIPE", count = 1, width = 1, sizeBytes = 1_000_000_000_000L)),
            detail.dataVdevGroups,
        )
    }

    @Test
    fun `counts leaf disks with any nonzero error stat, across every vdev category`() {
        fun disk(readErrors: Long = 0, writeErrors: Long = 0, checksumErrors: Long = 0) = VDevDto(
            type = "DISK",
            stats = VDevStatsDto(size = null, readErrors = readErrors, writeErrors = writeErrors, checksumErrors = checksumErrors),
            children = null,
        )
        val dto = PoolDetailDto(
            size = 1L,
            topology = TopologyDto(
                data = listOf(
                    VDevDto(type = "MIRROR", stats = null, children = listOf(disk(), disk(checksumErrors = 3))),
                ),
                log = listOf(disk(writeErrors = 1)),
                cache = null,
                spare = null,
                special = null,
                dedup = null,
            ),
            scan = null,
        )

        assertEquals(2, extractPoolDetail(dto).disksWithZfsErrors)
    }

    @Test
    fun `computes scan duration from start and end dates encoded as {$date millis}, and reads errors`() {
        val dto = PoolDetailDto(
            size = 1L,
            topology = null,
            scan = ScanDto(
                function = "SCRUB",
                state = "FINISHED",
                startTime = JsonObject().apply { addProperty("\$date", 1_754_094_652_000L - 5_449_000L) },
                endTime = JsonObject().apply { addProperty("\$date", 1_754_094_652_000L) },
                errors = 2,
            ),
        )

        val scan = extractPoolDetail(dto).lastScan

        assertEquals("SCRUB", scan?.function)
        assertEquals("FINISHED", scan?.state)
        assertEquals(1_754_094_652_000L / 1000, scan?.endEpochSeconds)
        assertEquals(5_449L, scan?.durationSeconds)
        assertEquals(2, scan?.errors)
    }

    @Test
    fun `also tolerates a bare epoch-seconds number for scan dates`() {
        val dto = PoolDetailDto(
            size = 1L,
            topology = null,
            scan = ScanDto(
                function = "SCRUB",
                state = "FINISHED",
                startTime = JsonPrimitive(1_754_089_203L),
                endTime = JsonPrimitive(1_754_094_652L),
                errors = 0,
            ),
        )

        val scan = extractPoolDetail(dto).lastScan

        assertEquals(1_754_094_652L, scan?.endEpochSeconds)
        assertEquals(5_449L, scan?.durationSeconds)
    }

    @Test
    fun `degrades gracefully when scan and topology are both missing`() {
        val dto = PoolDetailDto(size = 42L, topology = null, scan = null)

        val detail = extractPoolDetail(dto)

        assertEquals(emptyList<PoolVdevGroup>(), detail.dataVdevGroups)
        assertNull(detail.lastScan)
        assertEquals(0, detail.disksWithZfsErrors)
        assertEquals(42L, detail.usableCapacityBytes)
    }

    @Test
    fun `collects every leaf disk name across all vdev categories, deduplicated`() {
        fun disk(name: String) = VDevDto(type = "DISK", stats = null, children = null, disk = name)
        val topology = TopologyDto(
            data = listOf(VDevDto(type = "MIRROR", stats = null, children = listOf(disk("sda"), disk("sdb")))),
            log = listOf(disk("sdc")),
            cache = listOf(disk("sdc")), // same disk reused as both log and cache device
            spare = null,
            special = null,
            dedup = null,
        )

        assertEquals(listOf("sda", "sdb", "sdc"), extractDiskNames(topology))
    }

    @Test
    fun `returns no disk names when topology is missing`() {
        assertEquals(emptyList<String>(), extractDiskNames(null))
    }

    @Test
    fun `matches disk IO dimensions by read-write, case-insensitively`() {
        val series = NetdataSeriesDto(
            name = "disk",
            identifier = "sda",
            legend = listOf("time", "Writes", "Reads"),
            data = listOf(listOf(100.0, 12.0, 34.0)),
        )

        val io = extractDiskIoSeries(series)

        assertEquals(
            listOf(
                NamedSeries("Write", listOf(MetricHistoryPoint(100, 12.0))),
                NamedSeries("Read", listOf(MetricHistoryPoint(100, 34.0))),
            ),
            io,
        )
    }

    @Test
    fun `falls back to positional read then write labels when dimension names don't match`() {
        val series = NetdataSeriesDto(
            name = "disk",
            identifier = "sda",
            legend = listOf("time", "dim0", "dim1"),
            data = listOf(listOf(100.0, 5.0, 6.0)),
        )

        assertEquals(
            listOf(
                NamedSeries("Read", listOf(MetricHistoryPoint(100, 5.0))),
                NamedSeries("Write", listOf(MetricHistoryPoint(100, 6.0))),
            ),
            extractDiskIoSeries(series),
        )
    }

    @Test
    fun `reads a single temperature line from the disktemp graph`() {
        val series = NetdataSeriesDto(
            name = "disktemp",
            identifier = "sda",
            legend = listOf("time", "temperature_c"),
            data = listOf(listOf(100.0, 38.0)),
        )

        assertEquals(
            listOf(NamedSeries("Temperature", listOf(MetricHistoryPoint(100, 38.0)))),
            extractDiskTempSeries(series),
        )
    }

    @Test
    fun `builds the disk graph identifier as name pipe type pipe model pipe serial`() {
        // Exact format curl-verified via GET /reporting/graphs against a real server, e.g.
        // "sdc | Type: HDD | Model: Example Model | Serial: EXAMPLE0001" — the bare disk name
        // alone does NOT match any real graph instance.
        val disk = DiskDto(name = "sdc", type = "HDD", model = "Example Model", serial = "EXAMPLE0001")

        assertEquals(
            "sdc | Type: HDD | Model: Example Model | Serial: EXAMPLE0001",
            diskGraphIdentifier("sdc", disk),
        )
    }

    @Test
    fun `falls back to dashes in the disk graph identifier when metadata is missing`() {
        assertEquals(
            "sdz | Type: — | Model: — | Serial: —",
            diskGraphIdentifier("sdz", null),
        )
    }

    @Test
    fun `finds the real reporting graph identifier by disk name prefix`() {
        // disk.query's own "model" field doesn't always match this verbatim (observed on a real
        // server: disk.query's model had an underscore and a longer suffix, the real graph
        // identifier used a space and was shorter) — findDiskGraphIdentifier must be used over
        // diskGraphIdentifier whenever GET /reporting/graphs has an entry for the disk.
        val identifiers = listOf(
            "sdf | Type: HDD | Model: Other Example | Serial: EXAMPLE0002",
            "sdc | Type: HDD | Model: Example Model | Serial: EXAMPLE0001",
        )

        assertEquals(
            "sdc | Type: HDD | Model: Example Model | Serial: EXAMPLE0001",
            findDiskGraphIdentifier("sdc", identifiers),
        )
    }

    @Test
    fun `returns null when no reporting graph identifier matches the disk name`() {
        assertNull(findDiskGraphIdentifier("sdz", listOf("sdc | Type: HDD | Model: X | Serial: Y")))
    }

    @Test
    fun `does not match a disk name that is only a prefix of another disk's identifier`() {
        // "sd" must not match "sdc | ..." — the prefix check requires the " | " separator right
        // after the name, not just any starts-with.
        assertNull(findDiskGraphIdentifier("sd", listOf("sdc | Type: HDD | Model: X | Serial: Y")))
    }

    @Test
    fun `parses every alert level curl-verified or documented for alert list`() {
        assertEquals(AlertLevel.WARNING, parseAlertLevel("WARNING"))
        assertEquals(AlertLevel.NOTICE, parseAlertLevel("NOTICE"))
        assertEquals(AlertLevel.INFO, parseAlertLevel("INFO"))
        assertEquals(AlertLevel.CRITICAL, parseAlertLevel("critical"))
    }

    @Test
    fun `falls back to UNKNOWN for an alert level not yet seen live`() {
        assertEquals(AlertLevel.UNKNOWN, parseAlertLevel("SOMETHING_NEW"))
    }

    @Test
    fun `strips alert html markup, turning br into a newline and dropping tags but keeping their text`() {
        // Real `formatted` value curl-verified against a live server (GET /api/v2.0/alert/list).
        val formatted = "The deprecated REST API was used to authenticate 11439 times in the last 24 hours from the following IP addresses:<br>203.0.113.10, 203.0.113.20.<br>The REST API will be removed in version 26.04. To avoid service disruption, migrate any remaining integrations to the supported JSON-RPC 2.0 over WebSocket API before upgrading. For migration guidance, see the <a href=\"https://api.truenas.com/v25.10/jsonrpc.html\" target=\"_blank\">documentation</a>."

        val plain = stripAlertHtml(formatted)

        assertEquals(
            "The deprecated REST API was used to authenticate 11439 times in the last 24 hours from the following IP addresses:\n" +
                "203.0.113.10, 203.0.113.20.\n" +
                "The REST API will be removed in version 26.04. To avoid service disruption, migrate any remaining integrations to the supported JSON-RPC 2.0 over WebSocket API before upgrading. For migration guidance, see the documentation.",
            plain,
        )
    }

    @Test
    fun `parses and maps a real GET api v2_0 alert list response, dropping any dismissed entries`() {
        // Curl-verified against a live server (see ALERTS_TODO.md) — trimmed to the fields AlertDto
        // reads, but every value below (ids, levels, dates, formatted text) is the real response.
        val json = """
            [
              {
                "id": "537d613b-6524-4f8d-b36e-56d7e0d2754c",
                "level": "WARNING",
                "text": "unused when formatted is present",
                "formatted": "The deprecated REST API was used to authenticate 11439 times in the last 24 hours from the following IP addresses:<br>203.0.113.10, 203.0.113.20.<br>The REST API will be removed in version 26.04.",
                "dismissed": false,
                "datetime": { "${'$'}date": 1787698849000 }
              },
              {
                "id": "efce150c-d755-45b4-a2d7-786d3171b7c4",
                "level": "NOTICE",
                "text": "New ZFS version or feature flags are available for pool '%s'.",
                "formatted": "New ZFS version or feature flags are available for pool 'Miyota'.",
                "dismissed": false,
                "datetime": { "${'$'}date": 1784902280000 }
              },
              {
                "id": "68aa7a89-171d-4255-ab04-a8e0eb7a1988",
                "level": "INFO",
                "text": "Updates are available for %(count)d application%(plural)s: %(apps)s",
                "formatted": "Updates are available for 6 applications: planka, onlyoffice-document-server, nextcloud3, immich, home-assistant, firefly-iii",
                "dismissed": false,
                "datetime": { "${'$'}date": 1787926769000 }
              },
              {
                "id": "already-dismissed-alert",
                "level": "INFO",
                "text": "should not appear",
                "formatted": "should not appear",
                "dismissed": true,
                "datetime": { "${'$'}date": 1787000000000 }
              }
            ]
        """.trimIndent()

        val dtos = Gson().fromJson(json, Array<AlertDto>::class.java).toList()
        val alerts = extractAlerts(dtos)

        assertEquals(3, alerts.size)
        assertEquals(
            AlertInfo(
                id = "537d613b-6524-4f8d-b36e-56d7e0d2754c",
                level = AlertLevel.WARNING,
                message = "The deprecated REST API was used to authenticate 11439 times in the last 24 hours from the following IP addresses:\n203.0.113.10, 203.0.113.20.\nThe REST API will be removed in version 26.04.",
                epochSeconds = 1787698849000L / 1000,
                dismissed = false,
            ),
            alerts[0],
        )
        assertEquals(AlertLevel.NOTICE, alerts[1].level)
        assertEquals("New ZFS version or feature flags are available for pool 'Miyota'.", alerts[1].message)
        assertEquals(AlertLevel.INFO, alerts[2].level)
        assertEquals(true, alerts.none { it.id == "already-dismissed-alert" })
    }

    @Test
    fun `parses every app state curl-verified for app query`() {
        assertEquals(AppState.RUNNING, parseAppState("RUNNING"))
        assertEquals(AppState.STOPPED, parseAppState("stopped"))
        assertEquals(AppState.DEPLOYING, parseAppState("DEPLOYING"))
        assertEquals(AppState.CRASHED, parseAppState("CRASHED"))
        assertEquals(AppState.STOPPING, parseAppState("STOPPING"))
    }

    @Test
    fun `falls back to UNKNOWN for an app state not yet seen live`() {
        assertEquals(AppState.UNKNOWN, parseAppState("SOMETHING_NEW"))
    }

    @Test
    fun `parses a real GET api v2_0 app response, preferring metadata title over the raw id`() {
        // Curl-verified against a live server (see APPS_TODO.md) — trimmed to the fields AppDto
        // reads, but every value is real (planka: has an upgrade pending; nginx-proxy-manager: up
        // to date, and its metadata has no "title" to check the fallback to `name`).
        val json = """
            [
              {
                "id": "planka",
                "name": "planka",
                "state": "RUNNING",
                "version": "2.3.27",
                "latest_version": "2.3.28",
                "upgrade_available": true,
                "metadata": { "title": "Planka", "icon": "https://media.sys.truenas.net/apps/planka/icons/icon.png" }
              },
              {
                "id": "nginx-proxy-manager",
                "name": "nginx-proxy-manager",
                "state": "RUNNING",
                "version": "1.3.9",
                "latest_version": "1.3.9",
                "upgrade_available": false,
                "metadata": { "title": null, "icon": null }
              }
            ]
        """.trimIndent()

        val dtos = Gson().fromJson(json, Array<AppDto>::class.java).toList()
        val apps = dtos.map { dto ->
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

        assertEquals(
            AppInfo(
                id = "planka",
                title = "Planka",
                iconUrl = "https://media.sys.truenas.net/apps/planka/icons/icon.png",
                state = AppState.RUNNING,
                version = "2.3.27",
                latestVersion = "2.3.28",
                upgradeAvailable = true,
            ),
            apps[0],
        )
        assertEquals("nginx-proxy-manager", apps[1].title)
        assertEquals(false, apps[1].upgradeAvailable)
    }

    @Test
    fun `parses every job state curl-verified for core get_jobs`() {
        assertEquals(JobState.RUNNING, parseJobState("RUNNING"))
        assertEquals(JobState.SUCCESS, parseJobState("success"))
        assertEquals(JobState.FAILED, parseJobState("FAILED"))
        assertEquals(JobState.WAITING, parseJobState("WAITING"))
        assertEquals(JobState.ABORTED, parseJobState("ABORTED"))
    }

    @Test
    fun `falls back to UNKNOWN for a job state not yet seen live`() {
        assertEquals(JobState.UNKNOWN, parseJobState("SOMETHING_NEW"))
    }

    @Test
    fun `parses a real failed core get_jobs response from a dispatched app upgrade`() {
        // Curl-verified against a live server (see APPS_TODO.md) — real response from dispatching
        // app.upgrade on a nonexistent app name to confirm the dismiss-style body-shape trick,
        // trimmed to the fields JobDto reads.
        val json = """
            [
              {
                "id": 105554,
                "state": "FAILED",
                "progress": { "percent": 0, "description": "" },
                "error": "[ENOENT] None: App this-app-does-not-exist-zzz does not exist"
              }
            ]
        """.trimIndent()

        val job = Gson().fromJson(json, Array<JobDto>::class.java).first()

        assertEquals(JobState.FAILED, parseJobState(job.state))
        assertEquals(0, job.progress?.percent)
        assertEquals("[ENOENT] None: App this-app-does-not-exist-zzz does not exist", job.error)
    }

    @Test
    fun `parses a real service query response into labelled services sorted like the web UI`() {
        // Curl-verified against a live TrueNAS 25.10 server (see SYSTEM_TODO.md), pids trimmed.
        val json = """
            [
              {"id": 4, "service": "cifs", "enable": true, "state": "RUNNING", "pids": [4933]},
              {"id": 6, "service": "ftp", "enable": false, "state": "STOPPED", "pids": []},
              {"id": 7, "service": "iscsitarget", "enable": false, "state": "STOPPED", "pids": []},
              {"id": 9, "service": "nfs", "enable": false, "state": "STOPPED", "pids": []},
              {"id": 10, "service": "snmp", "enable": false, "state": "STOPPED", "pids": []},
              {"id": 11, "service": "ssh", "enable": false, "state": "STOPPED", "pids": []},
              {"id": 14, "service": "ups", "enable": false, "state": "STOPPED", "pids": []},
              {"id": 27, "service": "nvmet", "enable": false, "state": "STOPPED", "pids": []}
            ]
        """.trimIndent()

        val services = Gson().fromJson(json, Array<ServiceDto>::class.java).toServiceInfos()

        assertEquals(
            listOf("FTP", "iSCSI", "NFS", "NVMe-oF", "SMB", "SNMP", "SSH", "UPS"),
            services.map { it.label },
        )
        val smb = services.single { it.label == "SMB" }
        assertEquals(ServiceInfo(id = 4, name = "cifs", label = "SMB", running = true, startOnBoot = true), smb)
        assertEquals(1, services.count { it.running })
    }

    @Test
    fun `shows an unknown service name uppercased`() {
        assertEquals("WEBDAV", serviceLabel("webdav"))
    }
}
