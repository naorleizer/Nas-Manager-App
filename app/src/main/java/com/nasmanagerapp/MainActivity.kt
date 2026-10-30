package com.nasmanagerapp

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.nasmanagerapp.data.theme.ThemeMode
import com.nasmanagerapp.ui.dashboard.DashboardRoute
import com.nasmanagerapp.ui.login.LoginRoute
import com.nasmanagerapp.ui.theme.NasManagerAppTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // Local Network Protections: LAN sockets time out unless ACCESS_LOCAL_NETWORK is granted.
    private val localNetPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.local_network_denied, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestLocalNetworkAccess()
        setContent {
            TrueNasApp()
        }
    }

    private fun maybeRequestLocalNetworkAccess() {
        // The permission doesn't exist before API 37; enforcement targets Android 17.
        if (Build.VERSION.SDK_INT < 37) return
        if (checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            localNetPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }
}

@Composable
private fun TrueNasApp() {
    val context = LocalContext.current
    val app = context.applicationContext as TrueNasApplication
    val scope = rememberCoroutineScope()
    var isLoggedIn by remember { mutableStateOf(app.sessionPreferences.isLoggedIn) }

    // Defaults to the phone's system setting until the user explicitly toggles it from the
    // drawer, at which point the explicit choice is cached in ThemePreferences and takes
    // priority from then on (see ThemePreferences).
    var themeMode by remember { mutableStateOf(app.themePreferences.themeMode) }
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    // FLAG_SECURE only while the login screen (server address + password field) is shown — blocks
    // screenshots and the multitasking preview there, without affecting the dashboard once logged
    // in. A single Activity hosts both screens, so the flag is toggled as isLoggedIn changes rather
    // than set once in onCreate.
    DisposableEffect(isLoggedIn) {
        val window = (context as? Activity)?.window
        if (!isLoggedIn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    NasManagerAppTheme(darkTheme = darkTheme) {
        if (isLoggedIn) {
            DashboardRoute(
                isDarkTheme = darkTheme,
                onToggleTheme = {
                    val newMode = if (darkTheme) ThemeMode.LIGHT else ThemeMode.DARK
                    app.themePreferences.themeMode = newMode
                    themeMode = newMode
                },
                onLogout = {
                    scope.launch {
                        app.authRepository.logout()
                        app.sessionPreferences.clear()
                        isLoggedIn = false
                    }
                },
            )
        } else {
            LoginRoute(onLoginSuccess = { isLoggedIn = true })
        }
    }
}
