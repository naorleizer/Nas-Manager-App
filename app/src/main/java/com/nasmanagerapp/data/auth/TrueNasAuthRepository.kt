package com.nasmanagerapp.data.auth

import com.nasmanagerapp.data.network.CredentialsStore
import com.nasmanagerapp.data.network.TrueNasUrl
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class TrueNasApiException(message: String) : Exception(message)

/**
 * Talks to the TrueNAS Scale REST API (`/api/v2.0`). TrueNAS has no REST login endpoint — every
 * request authenticates itself with an `Authorization: Basic <base64(user:pass)>` header, so
 * "logging in" here just means validating those credentials against a cheap endpoint and then
 * leaving them in [credentialsStore] for later requests.
 */
class TrueNasAuthRepository(
    private val okHttpClient: OkHttpClient,
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
                // Cleartext HTTP is otherwise unrestricted (any host, private or public) — the
                // user's explicit consent is the only gate, checked again here in case a caller
                // ever reaches login() without going through the checkbox in LoginScreen.
                if (parsedUrl.scheme == "http" && !acceptHttpRisks) {
                    return@withContext Result.failure(
                        TrueNasApiException(
                            "You must confirm you accept the risks of the unencrypted HTTP " +
                                "connection before continuing."
                        )
                    )
                }

                // Authenticate this validation call explicitly rather than through the shared
                // CredentialsStore/interceptor: the credentials aren't trusted yet, so they must
                // not be applied to any other in-flight request until they've proven valid.
                val request = Request.Builder()
                    .url("$baseUrl/api/v2.0/system/info")
                    .header("Authorization", Credentials.basic(username, password))
                    .get()
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    when {
                        response.code == 401 || response.code == 403 ->
                            Result.failure(TrueNasApiException("Invalid or unauthorized credentials."))

                        !response.isSuccessful ->
                            Result.failure(TrueNasApiException("The server responded with an error (${response.code})."))

                        else -> {
                            credentialsStore.username = username
                            credentialsStore.password = password
                            Result.success(Unit)
                        }
                    }
                }
            } catch (e: SSLHandshakeException) {
                Result.failure(
                    TrueNasApiException(
                        "Server certificate not trusted. If your TrueNAS uses a self-signed " +
                            "certificate, import it into Android's security settings."
                    )
                )
            } catch (e: IOException) {
                Result.failure(TrueNasApiException("Couldn't reach the server. Check the address and your network."))
            }
        }

    /**
     * Clears the local credentials. Unlike a revocable API key, there's no way to invalidate
     * this password server-side from here — "logging out" is purely local.
     */
    fun logout() {
        credentialsStore.username = null
        credentialsStore.password = null
    }
}
