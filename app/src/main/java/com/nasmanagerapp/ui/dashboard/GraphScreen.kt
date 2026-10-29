package com.nasmanagerapp.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nasmanagerapp.data.dashboard.MetricHistoryPoint
import com.nasmanagerapp.data.dashboard.NamedSeries
import com.nasmanagerapp.data.dashboard.PoolDetail
import com.nasmanagerapp.data.dashboard.PoolScan
import com.nasmanagerapp.data.dashboard.PoolSummary
import com.nasmanagerapp.data.dashboard.PoolVdevGroup
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Which card's "view graph" button was tapped — drives [GraphScreen]'s content. */
enum class GraphTarget { CPU, MEMORY, POOLS }

/** How often the CPU screen re-fetches history while its auto-refresh switch is on. */
internal const val AUTO_REFRESH_INTERVAL_MILLIS = 5_000L

/**
 * Selectable history window for the CPU graph screen, from 5 minutes to 1 month, ordered from
 * shortest to longest — [next]/[previous] step through this order ("+"/"-" in [TimeRangeSelector]).
 */
enum class TimeRange(val seconds: Long, val fullLabel: String) {
    FIVE_MIN(5 * 60, "5 minutes"),
    TEN_MIN(10 * 60, "10 minutes"),
    FIFTEEN_MIN(15 * 60, "15 minutes"),
    THIRTY_MIN(30 * 60, "30 minutes"),
    ONE_HOUR(60 * 60, "1 hour"),
    THREE_HOURS(3 * 60 * 60, "3 hours"),
    SIX_HOURS(6 * 60 * 60, "6 hours"),
    TWELVE_HOURS(12 * 60 * 60, "12 hours"),
    ONE_DAY(24 * 60 * 60, "1 day"),
    THREE_DAYS(3 * 24 * 60 * 60, "3 days"),
    ONE_WEEK(7 * 24 * 60 * 60, "1 week"),
    TWO_WEEKS(14 * 24 * 60 * 60, "2 weeks"),
    ONE_MONTH(30 * 24 * 60 * 60, "1 month"),
    ;

    fun next(): TimeRange = entries.getOrElse(ordinal + 1) { this }
    fun previous(): TimeRange = entries.getOrElse(ordinal - 1) { this }
}

/** Screens that have a time-range selector + auto-refresh switch (rolled out page by page). */
private fun GraphTarget.supportsTimeRange() = this == GraphTarget.CPU || this == GraphTarget.MEMORY

@Composable
fun GraphRoute(
    target: GraphTarget,
    uiState: DashboardUiState,
    onLoadCpuHistory: (windowSeconds: Long) -> Unit,
    onLoadMemoryHistory: (windowSeconds: Long) -> Unit,
    onLoadPoolDetail: (poolId: Int) -> Unit,
    onSelectPool: (poolId: Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var timeRange by remember(target) { mutableStateOf(TimeRange.FIFTEEN_MIN) }
    var autoRefreshOn by remember(target) { mutableStateOf(false) }

    val onRefresh: () -> Unit = when (target) {
        GraphTarget.CPU -> ({ onLoadCpuHistory(timeRange.seconds) })
        GraphTarget.MEMORY -> ({ onLoadMemoryHistory(timeRange.seconds) })
        GraphTarget.POOLS -> ({})
    }

    LaunchedEffect(target, timeRange) {
        if (target.supportsTimeRange()) onRefresh()
    }

    LaunchedEffect(target, autoRefreshOn, timeRange) {
        if (target.supportsTimeRange() && autoRefreshOn) {
            while (isActive) {
                delay(AUTO_REFRESH_INTERVAL_MILLIS)
                onRefresh()
            }
        }
    }

    // Loads each pool's extra details once its id first appears — re-keyed on the id list only
    // (not the whole `pools` list, which gets a new instance every 2s poll) so this doesn't refire
    // on every live-data refresh, only when a pool is added.
    val poolIds = uiState.pools.map { it.id }
    LaunchedEffect(target, poolIds) {
        if (target == GraphTarget.POOLS) {
            poolIds.forEach { id ->
                if (id !in uiState.poolDetails && id !in uiState.loadingPoolDetailIds) {
                    onLoadPoolDetail(id)
                }
            }
        }
    }

    GraphScreen(
        target = target,
        uiState = uiState,
        timeRange = timeRange,
        onTimeRangeChange = { timeRange = it },
        autoRefreshOn = autoRefreshOn,
        onAutoRefreshChange = { autoRefreshOn = it },
        onRefresh = onRefresh,
        onSelectPool = onSelectPool,
        onBack = onBack,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphScreen(
    target: GraphTarget,
    uiState: DashboardUiState,
    timeRange: TimeRange,
    onTimeRangeChange: (TimeRange) -> Unit,
    autoRefreshOn: Boolean,
    onAutoRefreshChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onSelectPool: (poolId: Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(graphTitle(target)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Time range + auto-refresh are rolled out page by page — currently CPU and
                    // Memory — via [GraphTarget.supportsTimeRange].
                    if (target.supportsTimeRange()) {
                        Text(
                            "Auto",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Switch(checked = autoRefreshOn, onCheckedChange = onAutoRefreshChange)
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh history")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (target.supportsTimeRange()) {
                TimeRangeSelector(current = timeRange, onChange = onTimeRangeChange)
            }
            when (target) {
                GraphTarget.CPU -> CpuGraphContent(uiState, timeRange)
                GraphTarget.MEMORY -> MemoryGraphContent(uiState, timeRange)
                GraphTarget.POOLS -> PoolsGraphContent(
                    pools = uiState.pools,
                    poolDetails = uiState.poolDetails,
                    loadingPoolDetailIds = uiState.loadingPoolDetailIds,
                    poolDetailErrors = uiState.poolDetailErrors,
                    onSelectPool = onSelectPool,
                )
            }
        }
    }
}

/**
 * The "-" / current range / "+" control, framed to signal it's clickable — tapping "-"/"+" steps
 * through [TimeRange] one notch at a time, tapping the current range opens a menu to jump straight
 * to any value.
 */
@Composable
internal fun TimeRangeSelector(current: TimeRange, onChange: (TimeRange) -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onChange(current.previous()) }, enabled = current != TimeRange.entries.first()) {
                Icon(Icons.Filled.Remove, contentDescription = "Decrease time range")
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                TextButton(onClick = { menuExpanded = true }) {
                    Text(
                        "Period shown: ${current.fullLabel}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    TimeRange.entries.forEach { range ->
                        DropdownMenuItem(
                            text = { Text(range.fullLabel) },
                            onClick = {
                                onChange(range)
                                menuExpanded = false
                            },
                        )
                    }
                }
            }
            IconButton(onClick = { onChange(current.next()) }, enabled = current != TimeRange.entries.last()) {
                Icon(Icons.Filled.Add, contentDescription = "Increase time range")
            }
        }
    }
}

private fun graphTitle(target: GraphTarget): String = when (target) {
    GraphTarget.CPU -> "Graph — CPU"
    GraphTarget.MEMORY -> "Graph — Memory"
    GraphTarget.POOLS -> "Graph — Pools"
}

@Composable
private fun CpuGraphContent(uiState: DashboardUiState, timeRange: TimeRange) {
    val history = uiState.cpuPageHistory
    val isLoading = uiState.isLoadingCpuPageHistory
    val error = uiState.cpuPageHistoryError
    val period = timeRange.fullLabel

    GraphSection(
        title = "CPU usage — $period",
        series = history?.cpuUsagePercent.orEmpty(),
        isLoading = isLoading,
        error = error,
        valueFormatter = ::formatPercent,
        emptyMessage = "Not enough data yet for a graph.",
    )

    GraphSection(
        title = "CPU temperature — $period",
        series = history?.cpuTemperatureCelsius.orEmpty(),
        isLoading = isLoading,
        error = error,
        valueFormatter = { String.format(java.util.Locale.US, "%.0f °C", it) },
        emptyMessage = if (history != null) {
            "Temperature not available on this system."
        } else {
            "Not enough data yet for a graph."
        },
    )

    GraphSection(
        title = "Load average — $period",
        series = history?.loadAverage.orEmpty(),
        isLoading = isLoading,
        error = error,
        valueFormatter = { String.format(java.util.Locale.US, "%.2f", it) },
        emptyMessage = if (history != null) {
            "Load average not available on this system."
        } else {
            "Not enough data yet for a graph."
        },
    )
}

/**
 * A titled, carded chart block — same framed-card look as the dashboard's own cards. Pass
 * [singleColor] to pin a specific color instead of the default palette cycling — used for
 * single-line sections (Memory) that should match the color already used for that value elsewhere
 * in the app, rather than always drawing as the first palette color.
 */
@Composable
private fun GraphSection(
    title: String,
    series: List<NamedSeries>,
    isLoading: Boolean,
    error: String?,
    valueFormatter: (Double) -> String,
    emptyMessage: String,
    singleColor: Color? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            when {
                series.any { it.points.size >= 2 } -> MultiLineChart(
                    series = series,
                    valueFormatter = valueFormatter,
                    colorOverride = singleColor,
                )
                isLoading -> LoadingRow()
                error != null -> ErrorRow(message = error, onRetry = null)
                else -> Text(
                    emptyMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MemoryGraphContent(uiState: DashboardUiState, timeRange: TimeRange) {
    val history = uiState.metricsHistory
    val isLoading = uiState.isLoadingHistory
    val error = uiState.historyError
    val total = uiState.systemInfo?.totalMemoryBytes ?: 0L
    val period = timeRange.fullLabel
    val notEnoughDataMessage = "Not enough data yet for a graph."

    GraphSection(
        title = "Available memory — $period",
        series = history?.memoryAvailableBytes?.let { listOf(NamedSeries("Available", it)) }.orEmpty(),
        isLoading = isLoading,
        error = error,
        valueFormatter = { formatBytes(it.toLong()) },
        emptyMessage = notEnoughDataMessage,
        singleColor = AvailableColor,
    )

    GraphSection(
        title = "Used memory — $period",
        series = if (history != null && total > 0) {
            listOf(
                NamedSeries(
                    "Used",
                    history.memoryAvailableBytes.map { it.copy(value = (total - it.value).coerceAtLeast(0.0)) },
                ),
            )
        } else {
            emptyList()
        },
        isLoading = isLoading,
        error = error,
        valueFormatter = { formatBytes(it.toLong()) },
        emptyMessage = notEnoughDataMessage,
        singleColor = ServicesColor,
    )

    GraphSection(
        title = "ZFS Cache (ARC) — $period",
        series = history?.zfsArcSizeBytes?.let { listOf(NamedSeries("ZFS Cache (ARC)", it)) }.orEmpty(),
        isLoading = isLoading,
        error = error,
        valueFormatter = { formatBytes(it.toLong()) },
        emptyMessage = notEnoughDataMessage,
        singleColor = ZfsCacheColor,
    )
}

/**
 * Space breakdown, Information (topology, capacity, ZFS errors, fragmentation) and
 * Last scrub are all merged into one card per pool here — disk-by-disk detail lives one level
 * deeper, on `PoolDetailScreen` ("Open this pool").
 */
@Composable
private fun PoolsGraphContent(
    pools: List<PoolSummary>,
    poolDetails: Map<Int, PoolDetail>,
    loadingPoolDetailIds: Set<Int>,
    poolDetailErrors: Map<Int, String>,
    onSelectPool: (poolId: Int) -> Unit,
) {
    if (pools.isEmpty()) {
        Text("No pools to display.", style = MaterialTheme.typography.bodyLarge)
        return
    }
    pools.forEach { pool ->
        val detail = poolDetails[pool.id]
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(healthy = pool.healthy)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        pool.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        pool.status,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (pool.healthy) HealthyColor else MaterialTheme.colorScheme.error,
                    )
                }
                if (!pool.healthy && pool.statusDetail != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(pool.statusDetail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }

                Spacer(Modifier.height(16.dp))
                Text("Space breakdown", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DonutChart(
                        segments = listOf(
                            pool.allocatedBytes.toFloat() to ServicesColor,
                            pool.freeBytes.toFloat() to FreeColor,
                        ),
                        modifier = Modifier.size(120.dp),
                    )
                    Spacer(Modifier.width(20.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        MemoryLegendRow(color = ServicesColor, label = "Allocated", bytes = pool.allocatedBytes)
                        MemoryLegendRow(color = FreeColor, label = "Free", bytes = pool.freeBytes)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Total", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(formatBytes(pool.sizeBytes), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }

                Spacer(Modifier.height(16.dp))
                when {
                    detail != null -> {
                        Text("Information", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        InfoRow("Data topology", formatTopology(detail.dataVdevGroups))
                        InfoRow("Usable capacity", formatBytes(detail.usableCapacityBytes))
                        InfoRow("Disks with ZFS errors", "${detail.disksWithZfsErrors}")
                        pool.fragmentationPercent?.let { InfoRow("Fragmentation", "$it %") }

                        Spacer(Modifier.height(16.dp))
                        Text("Last scrub", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        ScanInfo(detail.lastScan)
                    }
                    pool.id in loadingPoolDetailIds -> {
                        Spacer(Modifier.height(4.dp))
                        LoadingRow()
                    }
                    else -> {
                        Spacer(Modifier.height(4.dp))
                        ErrorRow(message = poolDetailErrors[pool.id] ?: "Information unavailable.", onRetry = null)
                    }
                }

                Spacer(Modifier.height(16.dp))
                Button(onClick = { onSelectPool(pool.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Open this pool")
                }
            }
        }
    }
}

@Composable
private fun ScanInfo(scan: PoolScan?) {
    if (scan == null || scan.endEpochSeconds == null) {
        Text(
            "No scrub performed yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    InfoRow("Date", formatEpochSeconds(scan.endEpochSeconds))
    scan.durationSeconds?.let {
        Spacer(Modifier.height(4.dp))
        InfoRow("Duration", formatDurationLong(it))
    }
    Spacer(Modifier.height(4.dp))
    InfoRow("Errors", "${scan.errors}")
}

/** e.g. "1 x RAIDZ1 | 4 wide | 1.82 TiB", joining multiple groups with ", " if the pool's data vdevs aren't all the same shape. */
private fun formatTopology(groups: List<PoolVdevGroup>): String {
    if (groups.isEmpty()) return "Not available"
    return groups.joinToString(", ") { g -> "${g.count} x ${g.type} | ${g.width} wide | ${formatBytes(g.sizeBytes)}" }
}

/** Distinct colors cycled across the lines of a [MultiLineChart], in a stable draw order. */
private val ChartPalette = listOf(
    Color(0xFF3F51B5), // indigo
    Color(0xFFE53935), // red
    Color(0xFF43A047), // green
    Color(0xFFFFA726), // orange
    Color(0xFF8E24AA), // purple
    Color(0xFF00ACC1), // cyan
    Color(0xFFFDD835), // yellow
    Color(0xFF6D4C41), // brown
    Color(0xFFEC407A), // pink
    Color(0xFF757575), // grey
)

private data class ColoredSeries(val label: String, val points: List<MetricHistoryPoint>, val color: Color)

/**
 * One or more overlaid history lines sharing the same axes, with a wrapping legend below — used
 * for CPU usage/temperature (global + per-core) and load average (short/mid/long term), and for
 * Memory's single-line sections. Each line gets a different [ChartPalette] color in draw order,
 * unless [colorOverride] pins them all to the same color (single-line sections that should match a
 * color already used for that value elsewhere in the app, e.g. Memory's Available/Used/ARC).
 */
@Composable
internal fun MultiLineChart(series: List<NamedSeries>, valueFormatter: (Double) -> String, colorOverride: Color? = null) {
    val colored = series.mapIndexed { index, s ->
        ColoredSeries(s.label, s.points, colorOverride ?: ChartPalette[index % ChartPalette.size])
    }
    val plottable = colored.filter { it.points.size >= 2 }
    if (plottable.isEmpty()) return

    val allPoints = plottable.flatMap { it.points }
    val minTime = allPoints.minOf { it.timestampSeconds }
    val maxTime = allPoints.maxOf { it.timestampSeconds }
    val timeRange = (maxTime - minTime).takeIf { it > 0 } ?: 1L
    val maxValue = allPoints.maxOf { it.value }
    val minValue = allPoints.minOf { it.value }
    val valueRange = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            valueFormatter(maxValue),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .padding(vertical = 4.dp),
        ) {
            plottable.forEach { s ->
                val path = Path()
                s.points.forEachIndexed { index, point ->
                    val x = size.width * (point.timestampSeconds - minTime).toFloat() / timeRange.toFloat()
                    val y = size.height - size.height * ((point.value - minValue) / valueRange).toFloat()
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path = path, color = s.color, style = Stroke(width = 2.5.dp.toPx()))
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                valueFormatter(minValue),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${formatMinutes(timeRange)} ago",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "now",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            plottable.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.height(10.dp).width(10.dp),
                        shape = RoundedCornerShape(3.dp),
                        color = s.color,
                        content = {},
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(s.label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
internal fun DonutChart(segments: List<Pair<Float, Color>>, modifier: Modifier = Modifier) {
    val total = segments.sumOf { it.first.toDouble() }.toFloat().coerceAtLeast(1f)
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.22f
        // Inset the arc bounds by half the stroke width, otherwise the outer half of the ring
        // gets drawn past the canvas edge and clipped.
        val inset = strokeWidth / 2
        val arcSize = androidx.compose.ui.geometry.Size(size.width - strokeWidth, size.height - strokeWidth)
        var startAngle = -90f
        segments.filter { it.first > 0f }.forEach { (value, color) ->
            val sweep = 360f * (value / total)
            drawArc(
                color = color,
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokeWidth),
            )
            startAngle += sweep
        }
    }
}

private fun formatMinutes(totalSeconds: Long): String {
    val minutes = (totalSeconds / 60).coerceAtLeast(1)
    return "$minutes min"
}
