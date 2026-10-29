package com.nasmanagerapp.ui.dashboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.filled.Menu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nasmanagerapp.TrueNasApplication
import com.nasmanagerapp.data.dashboard.LiveMetrics
import com.nasmanagerapp.data.dashboard.PoolSummary
import com.nasmanagerapp.data.dashboard.SystemInfo
import com.nasmanagerapp.ui.theme.NasManagerAppTheme
import kotlinx.coroutines.launch

internal val HealthyColor = Color(0xFF2E7D32)

// Shared across CPU, Memory and Pool cards so all progress/usage indicators read consistently.
internal val ServicesColor = Color(0xFF3F51B5) // indigo — "in use" (CPU load, services, allocated space)
internal val ZfsCacheColor = Color(0xFFFFA726) // orange — ZFS ARC cache (memory card only)
internal val FreeColor = Color(0xFFB0BEC5) // blue-grey — free/idle/remaining
internal val AvailableColor = Color(0xFF9C7480) // marked pink-grey — Memory's "Available" graph only

@Composable
fun DashboardRoute(
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as TrueNasApplication
    val viewModel: DashboardViewModel = viewModel(
        factory = remember { DashboardViewModelFactory(app) },
    )
    val uiState by viewModel.uiState.collectAsState()
    var selectedGraph by remember { mutableStateOf<GraphTarget?>(null) }
    var selectedPoolId by remember { mutableStateOf<Int?>(null) }
    var showAlerts by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    var showSystem by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    fun closeDrawer() = scope.launch { drawerState.close() }

    // Mirrors the onBack callback of whichever nested screen is currently shown, so the system
    // back gesture/button steps back through the drawer navigation instead of closing the app.
    // Only enabled away from the true root (drawer closed, no screen open), so back still exits
    // the app there as expected.
    BackHandler(
        enabled = drawerState.isOpen || showAlerts || showApps || showSystem ||
            selectedPoolId != null || selectedGraph != null,
    ) {
        when {
            drawerState.isOpen -> closeDrawer()
            showAlerts -> showAlerts = false
            showApps -> showApps = false
            showSystem -> showSystem = false
            selectedPoolId != null -> selectedPoolId = null
            selectedGraph != null -> selectedGraph = null
        }
    }

    val currentDestination = when {
        showAlerts -> null
        showApps -> DrawerDestination.APPS
        showSystem -> DrawerDestination.SYSTEM
        selectedGraph == GraphTarget.POOLS -> DrawerDestination.STORAGE
        selectedGraph == GraphTarget.CPU -> DrawerDestination.REPORTING
        selectedGraph == null -> DrawerDestination.DASHBOARD
        else -> null
    }

    ModalNavigationDrawer(
        modifier = modifier,
        drawerState = drawerState,
        drawerContent = {
            AppDrawerContent(
                username = app.currentUsername,
                currentDestination = currentDestination,
                alertCount = uiState.alerts.size,
                isDarkTheme = isDarkTheme,
                onToggleTheme = onToggleTheme,
                onOpenAlerts = {
                    closeDrawer()
                    showApps = false
                    showSystem = false
                    showAlerts = true
                },
                onLogout = {
                    closeDrawer()
                    onLogout()
                },
                onSelectDestination = { destination ->
                    closeDrawer()
                    showAlerts = false
                    showApps = destination == DrawerDestination.APPS
                    showSystem = destination == DrawerDestination.SYSTEM
                    selectedPoolId = null
                    selectedGraph = when (destination) {
                        DrawerDestination.STORAGE -> GraphTarget.POOLS
                        DrawerDestination.REPORTING -> GraphTarget.CPU
                        DrawerDestination.DASHBOARD, DrawerDestination.APPS, DrawerDestination.SYSTEM -> null
                    }
                },
            )
        },
    ) {
        val graph = selectedGraph
        val poolId = selectedPoolId
        when {
            showAlerts -> {
                AlertsRoute(
                    uiState = uiState,
                    onLoadAlerts = viewModel::loadAlerts,
                    onDismissAlert = viewModel::dismissAlert,
                    onBack = { showAlerts = false },
                )
            }

            showApps -> {
                AppsRoute(
                    uiState = uiState,
                    onLoadApps = viewModel::loadApps,
                    onUpgradeApp = viewModel::upgradeApp,
                    onUpgradeAll = viewModel::upgradeAllOutdatedApps,
                    onSetAppRunning = viewModel::setAppRunning,
                    onBack = { showApps = false },
                )
            }

            showSystem -> {
                SystemRoute(
                    uiState = uiState,
                    onLoadSystemSettings = viewModel::loadSystemSettings,
                    onSaveServiceChanges = viewModel::saveServiceChanges,
                    onBack = { showSystem = false },
                )
            }

            graph == GraphTarget.POOLS && poolId != null -> {
                PoolDetailRoute(
                    poolId = poolId,
                    uiState = uiState,
                    onLoadPoolDiskDetails = viewModel::loadPoolDiskDetails,
                    onBack = { selectedPoolId = null },
                )
            }

            graph != null -> {
                GraphRoute(
                    target = graph,
                    uiState = uiState,
                    onLoadCpuHistory = viewModel::loadCpuPageHistory,
                    onLoadMemoryHistory = viewModel::loadMetricsHistory,
                    onLoadPoolDetail = viewModel::loadPoolDetail,
                    onSelectPool = { selectedPoolId = it },
                    onBack = { selectedGraph = null },
                )
            }

            else -> {
                DashboardScreen(
                    uiState = uiState,
                    fallbackTitle = app.sessionPreferences.serverUrl,
                    onRetrySystemInfo = viewModel::retryLoadSystemInfo,
                    onOpenGraph = { selectedGraph = it },
                    onOpenMenu = { scope.launch { drawerState.open() } },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    uiState: DashboardUiState,
    fallbackTitle: String,
    onRetrySystemInfo: () -> Unit,
    onOpenGraph: (GraphTarget) -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(uiState.systemInfo?.hostname?.ifBlank { null } ?: fallbackTitle) },
                navigationIcon = {
                    IconButton(onClick = onOpenMenu) {
                        Icon(Icons.Filled.Menu, contentDescription = "Open menu")
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Compatible with TrueNAS Scale",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SystemInfoCard(
                systemInfo = uiState.systemInfo,
                isLoading = uiState.isLoadingSystemInfo,
                error = uiState.systemInfoError,
                onRetry = onRetrySystemInfo,
            )
            CpuOverviewCard(
                systemInfo = uiState.systemInfo,
                liveMetrics = uiState.liveMetrics,
                onOpenGraph = { onOpenGraph(GraphTarget.CPU) },
            )
            MemoryCard(
                systemInfo = uiState.systemInfo,
                liveMetrics = uiState.liveMetrics,
                onOpenGraph = { onOpenGraph(GraphTarget.MEMORY) },
            )
            PoolStorageCard(
                pools = uiState.pools,
                isLoading = uiState.isLoadingPools,
                error = uiState.poolsError,
                onOpenGraph = { onOpenGraph(GraphTarget.POOLS) },
            )
        }
    }
}

@Composable
private fun DashboardCard(
    title: String,
    icon: @Composable () -> Unit,
    onOpenGraph: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (onOpenGraph != null) {
                    IconButton(onClick = onOpenGraph) {
                        Icon(
                            Icons.Filled.ShowChart,
                            contentDescription = "View graph",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SystemInfoCard(
    systemInfo: SystemInfo?,
    isLoading: Boolean,
    error: String?,
    onRetry: () -> Unit,
) {
    DashboardCard(
        title = "System",
        icon = { Icon(Icons.Filled.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
    ) {
        when {
            systemInfo != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InfoRow("Model", systemInfo.model.ifBlank { "—" })
                InfoRow("Manufacturer", systemInfo.manufacturer.ifBlank { "—" })
                InfoRow("TrueNAS version", systemInfo.version)
                InfoRow("Uptime", formatUptime(systemInfo.uptimeSeconds))
                InfoRow(
                    "Load average",
                    String.format(
                        java.util.Locale.US,
                        "%.2f / %.2f / %.2f",
                        systemInfo.loadAverage1m,
                        systemInfo.loadAverage5m,
                        systemInfo.loadAverage15m,
                    ),
                )
            }

            isLoading -> LoadingRow()

            else -> ErrorRow(message = error ?: "Unknown error.", onRetry = onRetry)
        }
    }
}

@Composable
private fun CpuOverviewCard(systemInfo: SystemInfo?, liveMetrics: LiveMetrics?, onOpenGraph: () -> Unit) {
    DashboardCard(
        title = "CPU",
        icon = { Icon(Icons.Filled.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        onOpenGraph = onOpenGraph,
    ) {
        if (liveMetrics == null) {
            LoadingRow()
            return@DashboardCard
        }

        val coreCountLabel = systemInfo?.let { "${it.physicalCores} physical cores · ${it.logicalCores} threads" }
        if (coreCountLabel != null) {
            Text(coreCountLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }

        LabeledProgress(label = "Total usage", percent = liveMetrics.cpuTotalPercent)

        if (liveMetrics.cpuPerCorePercent.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            val rows = (liveMetrics.cpuPerCorePercent.size + 1) / 2
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.height((rows * 56).dp),
            ) {
                items(liveMetrics.cpuPerCorePercent.size) { index ->
                    Column(modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)) {
                        LabeledProgress(
                            label = "Core $index",
                            percent = liveMetrics.cpuPerCorePercent[index],
                            compact = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryCard(systemInfo: SystemInfo?, liveMetrics: LiveMetrics?, onOpenGraph: () -> Unit) {
    DashboardCard(
        title = "Memory",
        icon = { Icon(Icons.Filled.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        onOpenGraph = onOpenGraph,
    ) {
        if (systemInfo == null || liveMetrics == null) {
            LoadingRow()
            return@DashboardCard
        }

        val total = systemInfo.totalMemoryBytes
        // Same breakdown as the TrueNAS web dashboard: the ZFS ARC and the OS-reported "free"
        // memory are both directly measured, and "Services" (everything else in use) is whatever
        // is left over — there's no single API field for it.
        val free = liveMetrics.memoryAvailableBytes.coerceIn(0, total)
        val zfsCache = liveMetrics.zfsArcSizeBytes.coerceIn(0, total - free)
        val services = (total - free - zfsCache).coerceAtLeast(0)

        SegmentedBar(
            segments = listOf(
                services.toFloat() to ServicesColor,
                zfsCache.toFloat() to ZfsCacheColor,
                free.toFloat() to FreeColor,
            ),
            height = 14.dp,
        )
        Spacer(Modifier.height(12.dp))
        MemoryLegendRow(color = ServicesColor, label = "Services", bytes = services)
        MemoryLegendRow(color = ZfsCacheColor, label = "ZFS Cache (ARC)", bytes = zfsCache)
        MemoryLegendRow(color = FreeColor, label = "Free", bytes = free)
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Total", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(formatBytes(total), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * A usage bar with a sharp cut between colored segments — unlike Material3's
 * [LinearProgressIndicator], which always leaves a gap between the progress and the track.
 * Segment sizes are passed as raw weights (bytes, percent, anything comparable), not
 * pre-normalized fractions: [Row] weights size children proportionally on their own.
 */
@Composable
private fun SegmentedBar(segments: List<Pair<Float, Color>>, height: Dp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2)),
    ) {
        segments
            .filter { (weight, _) -> weight > 0f }
            .forEach { (weight, color) ->
                Box(
                    modifier = Modifier
                        .weight(weight)
                        .fillMaxHeight()
                        .background(color),
                )
            }
    }
}

@Composable
internal fun MemoryLegendRow(color: Color, label: String, bytes: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.height(10.dp).width(10.dp),
                shape = RoundedCornerShape(3.dp),
                color = color,
                content = {},
            )
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
        Text(formatBytes(bytes), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PoolStorageCard(
    pools: List<PoolSummary>,
    isLoading: Boolean,
    error: String?,
    onOpenGraph: () -> Unit,
) {
    DashboardCard(
        title = "Pools & Storage",
        icon = { Icon(Icons.Filled.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        onOpenGraph = onOpenGraph,
    ) {
        when {
            pools.isNotEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                pools.forEach { pool -> PoolRow(pool) }
            }

            isLoading -> LoadingRow()

            else -> ErrorRow(message = error ?: "No pool found.", onRetry = null)
        }
    }
}

@Composable
private fun PoolRow(pool: PoolSummary) {
    val usedPercent = if (pool.sizeBytes > 0) pool.allocatedBytes * 100.0 / pool.sizeBytes else 0.0
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(healthy = pool.healthy)
                Spacer(Modifier.width(8.dp))
                Text(pool.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                pool.status,
                style = MaterialTheme.typography.labelLarge,
                color = if (pool.healthy) HealthyColor else MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(6.dp))
        val active = usedPercent.toFloat().coerceIn(0f, 100f)
        SegmentedBar(
            segments = listOf(active to ServicesColor, (100f - active) to FreeColor),
            height = 6.dp,
        )
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${formatBytes(pool.allocatedBytes)} / ${formatBytes(pool.sizeBytes)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("${formatBytes(pool.freeBytes)} free", style = MaterialTheme.typography.bodyMedium)
        }
        pool.fragmentationPercent?.let {
            Text(
                "Fragmentation: $it %",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!pool.healthy && pool.statusDetail != null) {
            Text(
                pool.statusDetail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
internal fun StatusDot(healthy: Boolean) {
    Surface(
        modifier = Modifier
            .height(10.dp)
            .width(10.dp),
        shape = RoundedCornerShape(50),
        color = if (healthy) HealthyColor else MaterialTheme.colorScheme.error,
        content = {},
    )
}

@Composable
private fun LabeledProgress(label: String, percent: Double, compact: Boolean = false) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                label,
                style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            )
            Text(
                formatPercent(percent),
                style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(4.dp))
        val active = percent.toFloat().coerceIn(0f, 100f)
        SegmentedBar(
            segments = listOf(active to ServicesColor, (100f - active) to FreeColor),
            height = if (compact) 4.dp else 8.dp,
        )
    }
}

@Composable
internal fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun LoadingRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text("Loading…", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun ErrorRow(message: String, onRetry: (() -> Unit)?) {
    Column {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
        if (onRetry != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRetry) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.height(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Retry")
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 900)
@Composable
private fun DashboardScreenPreview() {
    NasManagerAppTheme {
        DashboardScreen(
            uiState = DashboardUiState(
                systemInfo = SystemInfo(
                    hostname = "truenas",
                    version = "25.10.5",
                    model = "Intel(R) Core(TM) i3-4160 CPU @ 3.60GHz",
                    manufacturer = "Gigabyte Technology Co., Ltd.",
                    physicalCores = 2,
                    logicalCores = 4,
                    totalMemoryBytes = 16_664_072_192,
                    uptimeSeconds = 2_784_052.0,
                    loadAverage1m = 0.31,
                    loadAverage5m = 0.39,
                    loadAverage15m = 0.33,
                ),
                isLoadingSystemInfo = false,
                pools = listOf(
                    PoolSummary(
                        id = 1,
                        name = "Miyota",
                        status = "ONLINE",
                        healthy = true,
                        statusDetail = null,
                        sizeBytes = 996_432_412_672,
                        allocatedBytes = 631_483_105_280,
                        freeBytes = 364_949_307_392,
                        fragmentationPercent = 9,
                    ),
                    PoolSummary(
                        id = 2,
                        name = "Omega",
                        status = "ONLINE",
                        healthy = true,
                        statusDetail = null,
                        sizeBytes = 7_988_639_170_560,
                        allocatedBytes = 1_571_882_328_064,
                        freeBytes = 6_416_756_842_496,
                        fragmentationPercent = 6,
                    ),
                ),
                isLoadingPools = false,
                liveMetrics = LiveMetrics(
                    cpuTotalPercent = 12.0,
                    cpuPerCorePercent = listOf(8.0, 15.0, 22.0, 4.0),
                    memoryAvailableBytes = 3_600_000_000,
                    zfsArcSizeBytes = 6_483_481_000,
                ),
            ),
            fallbackTitle = "192.168.*.*",
            onRetrySystemInfo = {},
            onOpenGraph = {},
            onOpenMenu = {},
        )
    }
}
