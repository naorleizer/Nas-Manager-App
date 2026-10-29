package com.nasmanagerapp.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nasmanagerapp.data.dashboard.NamedSeries
import com.nasmanagerapp.data.dashboard.PoolDiskInfo
import com.nasmanagerapp.data.dashboard.PoolSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun PoolDetailRoute(
    poolId: Int,
    uiState: DashboardUiState,
    onLoadPoolDiskDetails: (poolId: Int, windowSeconds: Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var timeRange by remember(poolId) { mutableStateOf(TimeRange.FIFTEEN_MIN) }
    var autoRefreshOn by remember(poolId) { mutableStateOf(false) }

    val onRefresh: () -> Unit = { onLoadPoolDiskDetails(poolId, timeRange.seconds) }

    LaunchedEffect(poolId, timeRange) { onRefresh() }

    LaunchedEffect(poolId, autoRefreshOn, timeRange) {
        if (autoRefreshOn) {
            while (isActive) {
                delay(AUTO_REFRESH_INTERVAL_MILLIS)
                onRefresh()
            }
        }
    }

    val pool = uiState.pools.firstOrNull { it.id == poolId }
    PoolDetailScreen(
        pool = pool,
        disks = uiState.poolDiskDetails,
        isLoadingDisks = uiState.isLoadingPoolDiskDetails,
        disksError = uiState.poolDiskDetailsError,
        timeRange = timeRange,
        onTimeRangeChange = { timeRange = it },
        autoRefreshOn = autoRefreshOn,
        onAutoRefreshChange = { autoRefreshOn = it },
        onRefresh = onRefresh,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * Reached from the Pools graph screen's "Open this pool" button — the pool-wide overview
 * (topology, capacity, status, last scrub) lives one level up, on that screen's merged card; this
 * screen is purely the disk-by-disk breakdown: for each disk, "Disk I/O" then "Disk Temperature",
 * each with the graph and a Max/Mean/Min line per series underneath.
 *
 * [pool] is looked up live from [DashboardUiState.pools] (polled every 2s) by id, just to detect if
 * it disappears (e.g. destroyed while this screen is open) and say so instead of crashing. [disks]
 * is loaded on entry, on time-range change, and on every auto-refresh tick — same mechanics as the
 * CPU/Memory graph screens (`GraphScreen.kt`): a framed `[-] Period shown: XXX [+]` selector
 * and an `Auto` switch + manual refresh in the top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoolDetailScreen(
    pool: PoolSummary?,
    disks: List<PoolDiskInfo>?,
    isLoadingDisks: Boolean,
    disksError: String?,
    timeRange: TimeRange,
    onTimeRangeChange: (TimeRange) -> Unit,
    autoRefreshOn: Boolean,
    onAutoRefreshChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(pool?.name ?: "Pool") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
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
            TimeRangeSelector(current = timeRange, onChange = onTimeRangeChange)
            when {
                pool == null -> Text(
                    "This pool is no longer available.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )

                disks != null && disks.isNotEmpty() -> disks.forEach { disk -> DiskSection(disk, timeRange.fullLabel) }

                disks != null -> Text(
                    "No disk found for this pool.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                isLoadingDisks -> LoadingRow()

                else -> ErrorRow(message = disksError ?: "Disks unavailable.", onRetry = null)
            }
        }
    }
}

@Composable
private fun DiskSection(disk: PoolDiskInfo, period: String) {
    val identity = " | Type: ${disk.type ?: "—"} | Model: ${disk.model ?: "—"} | Serial: ${disk.serial ?: "—"}"
    DiskGraphCard(
        title = "Disk I/O ${disk.name}$identity — $period",
        series = disk.ioSeries,
        valueFormatter = ::formatMebibytesPerSecond,
    )
    DiskGraphCard(
        title = "Disk Temperature ${disk.name}$identity — $period",
        series = disk.temperatureSeries,
        valueFormatter = ::formatCelsius,
    )
}

@Composable
private fun DiskGraphCard(title: String, series: List<NamedSeries>, valueFormatter: (Double) -> String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            if (series.any { it.points.size >= 2 }) {
                MultiLineChart(series = series, valueFormatter = valueFormatter)
                Spacer(Modifier.height(12.dp))
                series.forEach { line -> MinMeanMaxRow(line, valueFormatter) }
            } else {
                Text(
                    "Not enough data yet for a graph.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MinMeanMaxRow(series: NamedSeries, valueFormatter: (Double) -> String) {
    if (series.points.isEmpty()) return
    val values = series.points.map { it.value }
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(series.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "Max: ${valueFormatter(values.max())}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Average: ${valueFormatter(values.average())}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Min: ${valueFormatter(values.min())}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
