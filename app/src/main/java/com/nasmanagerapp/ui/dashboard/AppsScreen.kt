package com.nasmanagerapp.ui.dashboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import com.nasmanagerapp.TrueNasApplication
import com.nasmanagerapp.data.dashboard.AppInfo
import com.nasmanagerapp.data.dashboard.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okio.BufferedSource

/**
 * Apps screen, reachable from the drawer's "Apps" entry — lists installed apps
 * ([DashboardViewModel.loadApps]/[DashboardRepository.getApps]), with a per-app "Update"
 * action and a bulk "Update All" button (both go through [DashboardViewModel.upgradeApp], a real
 * middleware job polled to completion — see `APPS_TODO.md`), plus a per-app status dot and Start/Stop button
 * ([DashboardViewModel.setAppRunning], same job mechanism). No CPU/Block I/O/Network columns:
 * those only exist as WebSocket subscription events server-side (curl-verified, see
 * `APPS_TODO.md`), and this app is REST-only.
 *
 * "Discover Apps" only opens a placeholder for now ([AppsDiscoverScreen]) — the real catalog
 * (`POST /api/v2.0/catalog/apps`) is a ~800KB payload across 5 trains, browsing/installing from it
 * is a separate, much bigger feature than what was asked here.
 */
@Composable
fun AppsRoute(
    uiState: DashboardUiState,
    onLoadApps: () -> Unit,
    onUpgradeApp: (String) -> Unit,
    onUpgradeAll: () -> Unit,
    onSetAppRunning: (String, Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDiscover by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { onLoadApps() }

    if (showDiscover) {
        AppsDiscoverScreen(onBack = { showDiscover = false }, modifier = modifier)
    } else {
        AppsScreen(
            apps = uiState.apps,
            isLoading = uiState.isLoadingApps,
            error = uiState.appsError,
            updatingAppIds = uiState.updatingAppIds,
            switchingAppIds = uiState.switchingAppIds,
            appActionErrors = uiState.appActionErrors,
            onRefresh = onLoadApps,
            onUpgradeApp = onUpgradeApp,
            onUpgradeAll = onUpgradeAll,
            onSetAppRunning = onSetAppRunning,
            onOpenDiscover = { showDiscover = true },
            onBack = onBack,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    apps: List<AppInfo>,
    isLoading: Boolean,
    error: String?,
    updatingAppIds: Set<String>,
    switchingAppIds: Set<String>,
    appActionErrors: Map<String, String>,
    onRefresh: () -> Unit,
    onUpgradeApp: (String) -> Unit,
    onUpgradeAll: () -> Unit,
    onSetAppRunning: (String, Boolean) -> Unit,
    onOpenDiscover: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val outdatedCount = apps.count { it.upgradeAvailable }
    var showConfirmUpdateAll by remember { mutableStateOf(false) }
    var appToStop by remember { mutableStateOf<AppInfo?>(null) }

    appToStop?.let { app ->
        AlertDialog(
            onDismissRequest = { appToStop = null },
            title = { Text("Stop ${app.title}?") },
            text = { Text("The app will be unavailable until it's started again.") },
            confirmButton = {
                TextButton(onClick = {
                    appToStop = null
                    onSetAppRunning(app.id, false)
                }) { Text("Stop") }
            },
            dismissButton = {
                TextButton(onClick = { appToStop = null }) { Text("Cancel") }
            },
        )
    }

    if (showConfirmUpdateAll) {
        AlertDialog(
            onDismissRequest = { showConfirmUpdateAll = false },
            title = { Text("Update all apps?") },
            text = { Text("$outdatedCount app(s) will be updated in parallel. This action can't be canceled once started.") },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmUpdateAll = false
                    onUpgradeAll()
                }) { Text("Update") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmUpdateAll = false }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Apps") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh apps")
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { showConfirmUpdateAll = true }, enabled = outdatedCount > 0) {
                    Text("Update All ($outdatedCount)")
                }
                OutlinedButton(onClick = onOpenDiscover) {
                    Text("Discover Apps")
                }
            }

            if (error != null && apps.isNotEmpty()) {
                Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            when {
                apps.isNotEmpty() -> Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        AppsTableHeader()
                        apps.forEachIndexed { index, app ->
                            if (index > 0) Spacer(Modifier.height(12.dp))
                            AppRow(
                                app = app,
                                isUpdating = app.id in updatingAppIds,
                                isSwitching = app.id in switchingAppIds,
                                actionError = appActionErrors[app.id],
                                onUpgrade = { onUpgradeApp(app.id) },
                                onSetRunning = { start -> if (start) onSetAppRunning(app.id, true) else appToStop = app },
                            )
                        }
                    }
                }
                isLoading -> LoadingRow()
                error != null -> ErrorRow(message = error, onRetry = onRefresh)
                else -> Text(
                    "No apps installed.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AppsTableHeader() {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(
            "Application",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            "Status",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.9f),
        )
        Text(
            "Updates",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.7f),
        )
    }
}

@Composable
private fun AppRow(
    app: AppInfo,
    isUpdating: Boolean,
    isSwitching: Boolean,
    actionError: String?,
    onUpgrade: () -> Unit,
    onSetRunning: (Boolean) -> Unit,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(url = app.iconUrl)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(app.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        "v${app.version}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(modifier = Modifier.weight(0.9f), verticalAlignment = Alignment.CenterVertically) {
                AppStateDot(app.state)
                Spacer(Modifier.width(10.dp))
                AppRunToggle(
                    state = app.state,
                    isSwitching = isSwitching,
                    enabled = !isUpdating,
                    onSetRunning = onSetRunning,
                )
            }
            Box(modifier = Modifier.weight(0.7f)) {
                when {
                    isUpdating -> CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
                    app.upgradeAvailable -> OutlinedButton(onClick = onUpgrade, enabled = !isSwitching) { Text("Update") }
                    else -> Text("Up to date", style = MaterialTheme.typography.bodyMedium, color = HealthyColor)
                }
            }
        }
        if (actionError != null) {
            Spacer(Modifier.height(4.dp))
            Text(actionError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * "■"/"▶" button next to the status dot — mirrors the TrueNAS web
 * UI: Stop for a running or deploying app, Start for a stopped or crashed one, nothing while it's
 * already stopping (or unknown). Stopping goes through a confirmation dialog in [AppsScreen],
 * starting doesn't (harmless). Deliberately a different shape from the "Update" button next to
 * it (tinted square vs. an outlined pill), so the two don't get mixed up when tapping.
 */
@Composable
private fun AppRunToggle(state: AppState, isSwitching: Boolean, enabled: Boolean, onSetRunning: (Boolean) -> Unit) {
    val start = canStartApp(state)
    when {
        isSwitching -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        start || canStopApp(state) -> {
            val tint = if (start) HealthyColor else MaterialTheme.colorScheme.error
            FilledTonalIconButton(
                onClick = { onSetRunning(start) },
                enabled = enabled,
                shape = RoundedCornerShape(8.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = tint.copy(alpha = 0.15f),
                    contentColor = tint,
                ),
            ) {
                Icon(
                    if (start) Icons.Filled.PlayArrow else Icons.Filled.Stop,
                    contentDescription = if (start) "Start app" else "Stop app",
                )
            }
        }
    }
}

internal fun canStartApp(state: AppState): Boolean = state == AppState.STOPPED || state == AppState.CRASHED

internal fun canStopApp(state: AppState): Boolean = state == AppState.RUNNING || state == AppState.DEPLOYING

/**
 * Green dot when running, red otherwise — the text label was dropped since the Start/Stop button
 * already says which way the app is; the full state stays available to screen readers.
 */
@Composable
private fun AppStateDot(state: AppState) {
    val label = when (state) {
        AppState.RUNNING -> "Running"
        AppState.STOPPED -> "Stopped"
        AppState.DEPLOYING -> "Deploying"
        AppState.STOPPING -> "Stopping"
        AppState.CRASHED -> "Crashed"
        AppState.UNKNOWN -> "Unknown state"
    }
    Box(modifier = Modifier.semantics { contentDescription = label }) {
        StatusDot(healthy = state == AppState.RUNNING)
    }
}

/**
 * Fetches [url] (a public app-catalog icon, e.g. `media.sys.truenas.net`) through
 * [TrueNasApplication.imageOkHttpClient] — deliberately not the authenticated client used for the
 * user's own TrueNAS, since this host isn't it. No caching beyond `remember(url)`: a handful of
 * small icons per screen visit, not worth a bitmap cache.
 */
@Composable
private fun AppIcon(url: String?) {
    val app = LocalContext.current.applicationContext as TrueNasApplication
    val sizePx = with(LocalDensity.current) { 40.dp.roundToPx() }
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(url) {
        bitmap = url?.let { fetchIconBitmap(app, it, sizePx) }
    }

    val loaded = bitmap
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (loaded != null) {
            Image(bitmap = loaded, contentDescription = null, modifier = Modifier.size(40.dp))
        } else {
            Icon(
                Icons.Filled.Apps,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * The catalog CDN serves icons as PNG/JPEG *or* SVG depending on the app (e.g. `plex` → `icon.png`,
 * `jellyfin` → `icon.svg`) — [android.graphics.BitmapFactory] only decodes raster formats, so SVGs
 * are rasterized via AndroidSVG at [sizePx] instead.
 *
 * The icon URL comes from the NAS (rewritable on the wire over `http://`, arbitrary for a custom
 * app), so both the download ([MAX_ICON_BYTES]) and the decoded bitmap (≈ [sizePx], see
 * [decodeRaster]) are bounded — an oversized file or a "decompression bomb" PNG can't exhaust memory.
 */
private suspend fun fetchIconBitmap(app: TrueNasApplication, url: String, sizePx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val request = Request.Builder().url(url).get().build()
        app.imageOkHttpClient.newCall(request).execute().use { response ->
            val bytes = if (response.isSuccessful) response.body?.source()?.let { readAtMost(it, MAX_ICON_BYTES) } else null
            bytes?.let {
                if (isSvgIcon(response.header("Content-Type"), url)) {
                    renderSvg(it, sizePx)
                } else {
                    decodeRaster(it, sizePx)
                }?.asImageBitmap()
            }
        }
    }.getOrNull()
}

/** Real catalog icons are ≤ ~80 KB (checked on the CDN 2026-10-08) — 1 MiB leaves ample margin. */
internal const val MAX_ICON_BYTES = 1L * 1024 * 1024

/** The whole body if it's at most [maxBytes], `null` (nothing more read) if it's larger. */
internal fun readAtMost(source: BufferedSource, maxBytes: Long): ByteArray? {
    if (source.request(maxBytes + 1)) return null
    return source.buffer.readByteArray()
}

/** Reads only the header for the dimensions, then decodes subsampled down to about [sizePx] (e.g. 1024 px → 128 px). */
private fun decodeRaster(bytes: ByteArray, sizePx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = iconSampleSize(bounds.outWidth, bounds.outHeight, sizePx)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

/**
 * Largest power of 2 that keeps the longer side ≥ [targetPx] — the icon is fitted by its longer
 * side, so it's never upscaled (blurry), and memory stays bounded even for an extreme aspect ratio.
 */
internal fun iconSampleSize(width: Int, height: Int, targetPx: Int): Int {
    val longerSide = maxOf(width, height)
    var sampleSize = 1
    while (longerSide / (sampleSize * 2) >= targetPx) {
        sampleSize *= 2
    }
    return sampleSize
}

/** `Content-Type` first (the CDN sends `image/svg+xml`), falling back to the URL's extension if the header is missing. */
internal fun isSvgIcon(contentType: String?, url: String): Boolean =
    contentType?.substringBefore(';')?.trim()?.equals("image/svg+xml", ignoreCase = true)
        ?: url.substringBefore('?').endsWith(".svg", ignoreCase = true)

internal fun renderSvg(bytes: ByteArray, sizePx: Int): Bitmap {
    val svg = SVG.getFromInputStream(bytes.inputStream())
    // Without a viewBox AndroidSVG can't scale the drawing to the viewport — derive one from width/height.
    if (svg.documentViewBox == null && svg.documentWidth > 0 && svg.documentHeight > 0) {
        svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
    }
    // A root width/height (e.g. Immich's `width="590"`) would otherwise be drawn at that size instead
    // of the viewport, leaving only the drawing's top-left corner in the bitmap.
    svg.setDocumentWidth("100%")
    svg.setDocumentHeight("100%")
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    svg.renderToCanvas(Canvas(bitmap), RenderOptions().viewPort(0f, 0f, sizePx.toFloat(), sizePx.toFloat()))
    return bitmap
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppsDiscoverScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Discover Apps") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.Apps,
                contentDescription = null,
                modifier = Modifier.padding(bottom = 12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "The app catalog isn't available in the app yet.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
