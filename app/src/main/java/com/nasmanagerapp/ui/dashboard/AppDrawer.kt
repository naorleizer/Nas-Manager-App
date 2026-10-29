package com.nasmanagerapp.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nasmanagerapp.ui.ProjectInfo

/** Top-level destinations reachable from [AppDrawerContent], besides Alerts and Logout. */
enum class DrawerDestination { DASHBOARD, STORAGE, REPORTING, APPS, SYSTEM }

/**
 * Content of the left-hand navigation drawer opened from the hamburger button on [DashboardScreen].
 * Current user + Alerts + Logout up top, main navigation (Dashboard, Storage) below — see
 * `CLAUDE.md` for the app's planned navigation surface.
 */
@Composable
fun AppDrawerContent(
    username: String?,
    currentDestination: DrawerDestination?,
    alertCount: Int,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onOpenAlerts: () -> Unit,
    onLogout: () -> Unit,
    onSelectDestination: (DrawerDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalDrawerSheet(modifier = modifier) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.AccountCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.height(40.dp).width(40.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    username?.ifBlank { null } ?: "Not logged in",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Compatible with TrueNAS Scale",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        NavigationDrawerItem(
            label = { Text("Alerts") },
            icon = {
                BadgedBox(badge = { if (alertCount > 0) Badge { Text("$alertCount") } }) {
                    Icon(Icons.Filled.Notifications, contentDescription = null)
                }
            },
            selected = false,
            onClick = onOpenAlerts,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        NavigationDrawerItem(
            label = { Text("Logout") },
            icon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null) },
            selected = false,
            onClick = onLogout,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        NavigationDrawerItem(
            label = { Text("Dashboard") },
            icon = { Icon(Icons.Filled.Dashboard, contentDescription = null) },
            selected = currentDestination == DrawerDestination.DASHBOARD,
            onClick = { onSelectDestination(DrawerDestination.DASHBOARD) },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        NavigationDrawerItem(
            label = { Text("Storage") },
            icon = { Icon(Icons.Filled.Storage, contentDescription = null) },
            selected = currentDestination == DrawerDestination.STORAGE,
            onClick = { onSelectDestination(DrawerDestination.STORAGE) },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        NavigationDrawerItem(
            label = { Text("Reporting") },
            icon = { Icon(Icons.Filled.ShowChart, contentDescription = null) },
            selected = currentDestination == DrawerDestination.REPORTING,
            onClick = { onSelectDestination(DrawerDestination.REPORTING) },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        NavigationDrawerItem(
            label = { Text("Apps") },
            icon = { Icon(Icons.Filled.Apps, contentDescription = null) },
            selected = currentDestination == DrawerDestination.APPS,
            onClick = { onSelectDestination(DrawerDestination.APPS) },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        NavigationDrawerItem(
            label = { Text("System") },
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            selected = currentDestination == DrawerDestination.SYSTEM,
            onClick = { onSelectDestination(DrawerDestination.SYSTEM) },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(Modifier.weight(1f))
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Row(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (isDarkTheme) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                if (isDarkTheme) "Dark mode" else "Light mode",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = isDarkTheme, onCheckedChange = { onToggleTheme() })
        }
        ProjectInfo(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        )
    }
}
