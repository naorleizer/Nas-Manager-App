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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nasmanagerapp.data.dashboard.AlertInfo
import com.nasmanagerapp.data.dashboard.AlertLevel

/**
 * Alerts screen, reachable from the drawer's "Alerts" entry — see
 * [DashboardViewModel.loadAlerts]/[DashboardRepository.getAlerts]. Each alert has a "Dismiss"
 * button ([DashboardViewModel.dismissAlert]/[DashboardRepository.dismissAlert]) that mirrors the
 * TrueNAS web dashboard's own Dismiss action (curl-verified against a real server, see
 * `ALERTS_TODO.md`) — it's a real server-side dismissal, not just a local "seen" flag.
 */
@Composable
fun AlertsRoute(
    uiState: DashboardUiState,
    onLoadAlerts: () -> Unit,
    onDismissAlert: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onLoadAlerts() }
    AlertsScreen(
        alerts = uiState.alerts,
        isLoading = uiState.isLoadingAlerts,
        error = uiState.alertsError,
        dismissingAlertIds = uiState.dismissingAlertIds,
        onRefresh = onLoadAlerts,
        onDismiss = onDismissAlert,
        onBack = onBack,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(
    alerts: List<AlertInfo>,
    isLoading: Boolean,
    error: String?,
    dismissingAlertIds: Set<String>,
    onRefresh: () -> Unit,
    onDismiss: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Alerts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh alerts")
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // A dismiss failure shouldn't hide an already-loaded list behind a full-screen error —
            // shown as a banner above it instead, [ErrorRow] below stays for the "couldn't load at
            // all" case.
            if (error != null && alerts.isNotEmpty()) {
                Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            when {
                alerts.isNotEmpty() -> alerts.forEach { alert ->
                    AlertRow(
                        alert = alert,
                        isDismissing = alert.id in dismissingAlertIds,
                        onDismiss = { onDismiss(alert.id) },
                    )
                }
                isLoading -> LoadingRow()
                error != null -> ErrorRow(message = error, onRetry = onRefresh)
                else -> Text(
                    "No active alerts.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AlertRow(alert: AlertInfo, isDismissing: Boolean, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AlertLevelChip(alert.level)
                if (alert.epochSeconds != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatEpochSeconds(alert.epochSeconds),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(alert.message, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onDismiss, enabled = !isDismissing) {
                if (isDismissing) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Dismiss")
                }
            }
        }
    }
}

@Composable
private fun AlertLevelChip(level: AlertLevel) {
    val (background, foreground) = when (level) {
        AlertLevel.CRITICAL, AlertLevel.ALERT, AlertLevel.EMERGENCY, AlertLevel.ERROR ->
            MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.onError
        AlertLevel.WARNING -> ZfsCacheColor to Color.White
        AlertLevel.NOTICE -> ServicesColor to Color.White
        AlertLevel.INFO, AlertLevel.UNKNOWN ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = background, shape = RoundedCornerShape(6.dp)) {
        Text(
            alertLevelLabel(level),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = foreground,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

private fun alertLevelLabel(level: AlertLevel): String = when (level) {
    AlertLevel.INFO -> "Info"
    AlertLevel.NOTICE -> "Notice"
    AlertLevel.WARNING -> "Warning"
    AlertLevel.ERROR -> "Error"
    AlertLevel.CRITICAL -> "Critical"
    AlertLevel.ALERT -> "Alert"
    AlertLevel.EMERGENCY -> "Emergency"
    AlertLevel.UNKNOWN -> "Alert"
}
