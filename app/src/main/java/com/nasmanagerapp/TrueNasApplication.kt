package com.nasmanagerapp

import android.app.Application
import com.caverock.androidsvg.SVG
import com.nasmanagerapp.data.auth.SessionPreferences
import com.nasmanagerapp.data.auth.TrueNasAuthRepository
import com.nasmanagerapp.data.dashboard.DashboardRepository
import com.nasmanagerapp.data.network.CredentialsStore
import com.nasmanagerapp.data.network.NetworkModule
import com.nasmanagerapp.data.network.TrueNasUrl
import com.nasmanagerapp.data.theme.ThemePreferences
import okhttp3.OkHttpClient

class TrueNasApplication : Application() {

    private val credentialsStore: CredentialsStore by lazy { CredentialsStore() }

    private val okHttpClient: OkHttpClient by lazy {
        NetworkModule.createOkHttpClient(credentialsStore)
    }

    /**
     * Plain client (no Basic Auth interceptor) for external, unauthenticated resources — currently
     * just the app catalog's icon URLs (`https://media.sys.truenas.net/...`, see `APPS_TODO.md`),
     * which aren't served by the user's own TrueNAS and shouldn't receive its credentials.
     */
    val imageOkHttpClient: OkHttpClient by lazy { OkHttpClient() }

    val sessionPreferences: SessionPreferences by lazy {
        SessionPreferences(this)
    }

    val themePreferences: ThemePreferences by lazy {
        ThemePreferences(this)
    }

    val authRepository: TrueNasAuthRepository by lazy {
        TrueNasAuthRepository(okHttpClient, credentialsStore)
    }

    val dashboardRepository: DashboardRepository by lazy {
        DashboardRepository(okHttpClient) { TrueNasUrl.normalize(sessionPreferences.serverUrl) }
    }

    /** The user currently authenticated against the API, if any — read from the single source of truth ([CredentialsStore]) rather than [sessionPreferences], which only holds a username when "Stay logged in" was checked. */
    val currentUsername: String?
        get() = credentialsStore.username

    override fun onCreate() {
        super.onCreate()
        // App icons are SVGs fetched from a NAS-supplied URL (see `SECURITY_TODO.md`): refuse
        // `<!ENTITY>` definitions so a crafted icon can't expand into gigabytes ("billion laughs").
        // External entities are already disabled by AndroidSVG itself; no real icon uses either.
        SVG.setInternalEntitiesEnabled(false)
        // Restore a "Stay logged in" session so requests are authenticated as soon as the
        // app starts, before the first screen (which may skip straight to HomeScreen) renders.
        if (shouldRestoreSession(sessionPreferences.isLoggedIn, sessionPreferences.serverUrl)) {
            credentialsStore.username = sessionPreferences.username
            credentialsStore.password = sessionPreferences.password
        } else if (sessionPreferences.isLoggedIn) {
            // A session saved for an http:// address (older install, or the server was since
            // switched from https to http) must never be auto-restored: it would resend the
            // password automatically, in the clear, on whatever network the device joins next.
            sessionPreferences.password = ""
            sessionPreferences.isLoggedIn = false
        }
    }
}

/** Whether a saved "Stay logged in" session should be restored on app start. */
internal fun shouldRestoreSession(isLoggedIn: Boolean, serverUrl: String): Boolean =
    isLoggedIn && !TrueNasUrl.isHttp(serverUrl)
