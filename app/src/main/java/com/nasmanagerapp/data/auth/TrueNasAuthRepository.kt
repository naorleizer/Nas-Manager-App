package com.nasmanagerapp.data.auth

import com.nasmanagerapp.data.dashboard.DashboardApiException
import com.nasmanagerapp.data.network.CredentialsStore
import com.nasmanagerapp.data.network.TrueNasAuthFailedException
import com.nasmanagerapp.data.network.TrueNasRpcClient
import com.nasmanagerapp.data.network.TrueNasUrl
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class TrueNasApiException(message: String) : Exception(message)

/**
 * Talks to the TrueNAS JSON-RPC 2.0 WebSocket API (`/api/current`). "Logging in" opens the
 * session socket through [rpcClient] and authenticates it with `auth.login_ex` (password, or an
 * API key pasted in the password field), then leaves the credentials in [credentialsStore] so the
 * client can re-authenticate if it has to reconnect.
 */
class TrueNasAuthRepository(
    private val rpcClient: TrueNasRpcClient,
    private val credentialsStore: CredentialsStore,
) {

    suspend fun login(
        serverUrl: String,
        username: String,
        password: String,
        acceptHttpRisks: Boolean,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val baseUrl = TrueNasUrl.normalize(serverUrl)
                val parsedUrl = baseUrl.toHttpUrlOrNull()
                    ?: return@withContext Result.failure(TrueNasApiException("Invalid server address."))
                // Cleartext HTTP (ws://) is otherwise unrestricted (any host, private or public) —
                // the user's explicit consent is the only gate, checked again here in case a
                // caller ever reaches login() without going through the checkbox in LoginScreen.
                if (parsedUrl.scheme == "http" && !acceptHttpRisks) {
                    return@withContext Result.failure(
                        TrueNasApiException(
                            "You must confirm you accept the risks of the unencrypted HTTP " +
                                "connection before continuing."
                        )
                    )
                }
                val uri = TrueNasUrl.deriveWebSocketUri(serverUrl)
                    ?: return@withContext Result.failure(TrueNasApiException("Invalid server address."))

                // The credentials go into the shared CredentialsStore only once auth.login_ex has
                // accepted them: they aren't trusted before that.
                rpcClient.login(uri, username, password)
                credentialsStore.username = username
                credentialsStore.password = password
                Result.success(Unit)
            } catch (e: TrueNasAuthFailedException) {
                Result.failure(TrueNasApiException(e.message ?: "Invalid username or password."))
            } catch (e: DashboardApiException) {
                Result.failure(TrueNasApiException(e.message ?: "The server responded with an error."))
            } catch (e: SSLHandshakeException) {
                Result.failure(
                    TrueNasApiException(
                        "Server certificate not trusted (wss). If your TrueNAS uses a self-signed " +
                            "certificate, import it into Android's security settings."
                    )
                )
            } catch (e: IOException) {
                Result.failure(TrueNasApiException("Couldn't reach the server. Check the address and your network."))
            }
        }

    /**
     * Closes the session socket and clears the local credentials. Unlike a revocable API key,
     * there's no way to invalidate this password server-side from here — "logging out" is purely
     * local.
     */
    fun logout() {
        rpcClient.close()
        credentialsStore.username = null
        credentialsStore.password = null
    }
}
