package com.nasmanagerapp.data.network

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.nasmanagerapp.data.dashboard.DashboardApiException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** `auth.login_ex` refused the credentials; [message] is already user-facing. */
class TrueNasAuthFailedException(message: String) : Exception(message)

/** A JSON-RPC `error` frame. */
internal class RpcErrorException(val code: Int, message: String) : Exception(message)

/**
 * JSON-RPC 2.0 client for TrueNAS's `/api/current` WebSocket: one persistent socket per session,
 * opened lazily (and re-opened after a drop, with exponential backoff) and authenticated with
 * `auth.login_ex` before any data call. Frames are matched to callers by id; notifications (no
 * `id`) are ignored, except `core.get_jobs` `collection_update`s, which resolve job-yielding calls.
 */
class TrueNasRpcClient(
    private val okHttpClient: OkHttpClient,
    private val credentialsStore: CredentialsStore,
    private val uriProvider: () -> String?,
) {
    private val gson = Gson()
    private val connectLock = Mutex()

    @Volatile
    private var session: Session? = null

    private var consecutiveFailures = 0
    private var retryNotBeforeMillis = 0L

    /**
     * Opens a fresh session on [uri] authenticated as [username]/[secret] (password, or an API key
     * pasted in its place). Replaces any existing session. Network errors propagate as
     * [IOException]s (e.g. `SSLHandshakeException`); a refusal is a [TrueNasAuthFailedException].
     */
    suspend fun login(uri: String, username: String, secret: String) {
        connectLock.withLock {
            session?.close()
            session = null
            consecutiveFailures = 0
            retryNotBeforeMillis = 0L
            session = openAuthenticated(uri, username, secret)
        }
    }

    /** Drops the session; the next [call] reconnects (and fails if the credentials were cleared). */
    fun close() {
        session?.close()
        session = null
    }

    /**
     * Calls [method] with positional [params] and parses its `result`. [isJob] marks a
     * job-yielding method (`app.upgrade`/`start`/`stop`): the result is the job id, taken from
     * whichever of the direct reply or the `core.get_jobs` notification arrives first.
     */
    suspend fun <T> call(
        method: String,
        params: List<Any?>,
        parse: (JsonElement) -> T,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        isJob: Boolean = false,
    ): T {
        val result = try {
            withTimeout(timeoutMillis) { obtainSession().request(method, params, isJob) }
        } catch (e: TimeoutCancellationException) {
            throw DashboardApiException(
                if (isJob) "The server didn't acknowledge the task in time." else "The server didn't respond in time."
            )
        } catch (e: RpcErrorException) {
            throw DashboardApiException(e.message ?: "Unknown error.")
        } catch (e: TrueNasAuthFailedException) {
            throw DashboardApiException(e.message ?: "Authentication failed.")
        } catch (e: IOException) {
            android.util.Log.w(TAG, "call '$method' failed: ${e.javaClass.name}: ${e.message}")
            throw DashboardApiException("Couldn't reach the server.")
        }
        return parse(result)
    }

    private suspend fun obtainSession(): Session = connectLock.withLock {
        session?.takeIf { it.isOpen }?.let { return@withLock it }
        session = null
        val username = credentialsStore.username
        val secret = credentialsStore.password
        val uri = uriProvider()
        if (username == null || secret == null || uri == null) {
            throw DashboardApiException("Not signed in.")
        }
        val now = System.currentTimeMillis()
        if (now < retryNotBeforeMillis) throw DashboardApiException("Couldn't reach the server.")
        try {
            openAuthenticated(uri, username, secret).also {
                session = it
                consecutiveFailures = 0
            }
        } catch (e: IOException) {
            consecutiveFailures++
            val backoff = (BACKOFF_BASE_MILLIS shl (consecutiveFailures - 1).coerceAtMost(10)).coerceAtMost(BACKOFF_MAX_MILLIS)
            android.util.Log.w(TAG, "open session failed: ${e.javaClass.name}: ${e.message} uri=$uri backoff=$backoff")
            retryNotBeforeMillis = System.currentTimeMillis() + backoff
            throw e
        }
    }

    private suspend fun openAuthenticated(uri: String, username: String, secret: String): Session {
        val host = runCatching { java.net.URI(uri).host }.getOrNull()
        val resolved = runCatching { java.net.InetAddress.getAllByName(host).joinToString { "${it.hostAddress}" } }
        android.util.Log.w(
            TAG,
            "resolve host=$host -> ${resolved.getOrNull() ?: ("FAILED " + resolved.exceptionOrNull()?.javaClass?.simpleName + ": " + resolved.exceptionOrNull()?.message)}",
        )
        val opened = CompletableDeferred<Unit>()
        val newSession = Session(gson, opened)
        android.util.Log.w(TAG, "openAuth: connecting uri=$uri")
        val socket = runCatching { okHttpClient.newWebSocket(Request.Builder().url(uri).build(), newSession) }
            .onFailure { android.util.Log.w(TAG, "newWebSocket THREW synchronously: ${it.javaClass.name}: ${it.message}") }
            .getOrThrow()
        newSession.socket = socket
        try {
            try {
                withTimeout(CONNECT_TIMEOUT_MILLIS) { opened.await() }
            } catch (e: TimeoutCancellationException) {
                throw IOException("Connection timed out.")
            }
            withTimeout(DEFAULT_TIMEOUT_MILLIS) {
                authenticate(newSession, username, secret)
                try {
                    newSession.request("core.set_options", listOf(mapOf("legacy_jobs" to false)), false)
                } catch (e: RpcErrorException) {
                    if (e.code != METHOD_NOT_FOUND) throw e
                }
            }
        } catch (e: TimeoutCancellationException) {
            android.util.Log.w(TAG, "openAuth: connect/auth timeout after ${CONNECT_TIMEOUT_MILLIS}ms uri=$uri")
            newSession.close()
            throw IOException("The server didn't respond in time.")
        } catch (e: RpcErrorException) {
            android.util.Log.w(TAG, "openAuth: RPC error ${e.code}: ${e.message}")
            newSession.close()
            throw DashboardApiException(e.message ?: "Unknown error.")
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "openAuth: ${e.javaClass.name}: ${e.message}")
            newSession.close()
            throw e
        }
        return newSession
    }

    private suspend fun authenticate(session: Session, username: String, secret: String) {
        val passwordFailure = tryLogin(
            session,
            mapOf("mechanism" to "PASSWORD_PLAIN", "username" to username, "password" to secret),
        ) ?: return
        if (API_KEY_PATTERN.matches(secret)) {
            val keyFailure = tryLogin(
                session,
                mapOf("mechanism" to "API_KEY_PLAIN", "username" to username, "api_key" to secret),
            )
            if (keyFailure == null) return
        }
        throw TrueNasAuthFailedException(passwordFailure)
    }

    /** Runs one `auth.login_ex`; null on success, else the user-facing failure message. */
    private suspend fun tryLogin(session: Session, payload: Map<String, String>): String? {
        val result = session.request("auth.login_ex", listOf(payload), false)
        val type = (result as? JsonObject)?.get("response_type")?.takeIf { it.isJsonPrimitive }?.asString
        return when (type) {
            "SUCCESS" -> null
            "OTP_REQUIRED" -> "This account requires two-factor authentication."
            "DENIED" -> "This account doesn't have API access."
            "EXPIRED" -> "Account credentials have expired."
            "REDIRECT" -> "Authentication must be performed on the active storage controller."
            else -> "Invalid username or password."
        }
    }

    /** One WebSocket connection and its in-flight calls. Listener callbacks run on OkHttp threads. */
    private class Session(
        private val gson: Gson,
        private val opened: CompletableDeferred<Unit>,
    ) : WebSocketListener() {
        lateinit var socket: WebSocket
        private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
        private val jobCalls = ConcurrentHashMap.newKeySet<String>()

        @Volatile
        var isOpen = true
            private set

        suspend fun request(method: String, params: List<Any?>, isJob: Boolean): JsonElement {
            val id = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<JsonElement>()
            pending[id] = deferred
            if (isJob) jobCalls.add(id)
            try {
                if (!isOpen) throw IOException("Connection closed.")
                val frame = JsonObject().apply {
                    addProperty("jsonrpc", "2.0")
                    addProperty("id", id)
                    addProperty("method", method)
                    add("params", gson.toJsonTree(params))
                }
                if (!socket.send(gson.toJson(frame))) throw IOException("Connection closed.")
                return deferred.await()
            } finally {
                pending.remove(id)
                jobCalls.remove(id)
            }
        }

        fun close() {
            fail(IOException("Connection closed."))
            socket.close(NORMAL_CLOSURE, null)
        }

        private fun fail(cause: Throwable) {
            isOpen = false
            opened.completeExceptionally(cause)
            pending.values.forEach { it.completeExceptionally(cause) }
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            android.util.Log.w(TAG, "WS onOpen code=${response.code} ${response.message}")
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = runCatching { gson.fromJson(text, JsonElement::class.java) }.getOrNull() as? JsonObject
                ?: return
            val idElement = frame.get("id")
            if (idElement == null || idElement.isJsonNull) {
                handleNotification(frame)
                return
            }
            val deferred = pending[idElement.asString] ?: return
            val error = frame.get("error")
            if (error is JsonObject) {
                val reason = error.getAsJsonObject("data")?.get("reason")
                    ?.takeIf { it.isJsonPrimitive }?.asString?.ifBlank { null }
                val message = reason
                    ?: error.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: "Unknown error."
                val code = error.get("code")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                deferred.completeExceptionally(RpcErrorException(code, message))
            } else {
                deferred.complete(frame.get("result") ?: JsonNull.INSTANCE)
            }
        }

        /** Resolves job-yielding calls from `core.get_jobs` `added`/`changed` events; ignores the rest. */
        private fun handleNotification(frame: JsonObject) {
            if (frame.get("method")?.takeIf { it.isJsonPrimitive }?.asString != "collection_update") return
            val params = frame.get("params") as? JsonObject ?: return
            if (params.get("collection")?.takeIf { it.isJsonPrimitive }?.asString != "core.get_jobs") return
            val fields = params.get("fields") as? JsonObject ?: return
            val jobId = fields.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber } ?: return
            val messageIds = fields.get("message_ids") as? JsonArray ?: return
            for (messageId in messageIds) {
                if (!messageId.isJsonPrimitive) continue
                val callId = messageId.asString
                if (callId in jobCalls) pending[callId]?.complete(JsonPrimitive(jobId.asLong))
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            android.util.Log.w(TAG, "WS onClosing code=$code reason=$reason")
            fail(IOException("Connection closed."))
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            android.util.Log.w(TAG, "WS onClosed code=$code reason=$reason")
            fail(IOException("Connection closed."))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            android.util.Log.w(TAG, "WS onFailure: ${t.javaClass.name}: ${t.message} response=${response?.code ?: "-"}")
            fail(t)
        }
    }

    private companion object {
        const val TAG = "NasManager"
        const val DEFAULT_TIMEOUT_MILLIS = 15_000L
        const val CONNECT_TIMEOUT_MILLIS = 15_000L
        const val BACKOFF_BASE_MILLIS = 1_000L
        const val BACKOFF_MAX_MILLIS = 30_000L
        const val METHOD_NOT_FOUND = -32601
        const val NORMAL_CLOSURE = 1000
        val API_KEY_PATTERN = Regex("^[0-9]+-[A-Za-z0-9_-]{16,}$")
    }
}
