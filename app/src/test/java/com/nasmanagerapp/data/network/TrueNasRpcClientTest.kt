package com.nasmanagerapp.data.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nasmanagerapp.data.dashboard.DashboardApiException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class TrueNasRpcClientTest {

    private lateinit var server: MockWebServer
    private val received = CopyOnWriteArrayList<JsonObject>()

    /** Called for every non-auth frame the server receives; does nothing to leave it unanswered. */
    private var onRequest: (frame: JsonObject, ws: WebSocket) -> Unit = { frame, ws -> reply(ws, frame, "[]") }

    /** Called for `auth.login_ex` frames; defaults to accepting everything. */
    private var authResponseType: (payload: JsonObject) -> String = { "SUCCESS" }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = JsonParser.parseString(text).asJsonObject
                received.add(frame)
                when (frame.get("method").asString) {
                    "auth.login_ex" -> {
                        val payload = frame.getAsJsonArray("params")[0].asJsonObject
                        reply(webSocket, frame, """{"response_type":"${authResponseType(payload)}"}""")
                    }
                    "core.set_options" -> reply(webSocket, frame, """{"legacy_jobs":false}""")
                    else -> onRequest(frame, webSocket)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = Unit
        }))
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun reply(ws: WebSocket, frame: JsonObject, resultJson: String) {
        ws.send("""{"jsonrpc":"2.0","id":${frame.get("id")},"result":$resultJson}""")
    }

    private fun newClient() = TrueNasRpcClient(OkHttpClient(), CredentialsStore()) { server.url("/api/current").toString() }

    private fun TrueNasRpcClient.loginAsAdmin(secret: String = "hunter2") =
        runBlocking { login(server.url("/api/current").toString(), "admin", secret) }

    @Test
    fun `login sends auth_login_ex then core_set_options with array params`() {
        newClient().loginAsAdmin()

        val auth = received[0]
        assertEquals("2.0", auth.get("jsonrpc").asString)
        assertTrue(auth.get("id").isJsonPrimitive && auth.get("id").asString.isNotBlank())
        assertEquals("auth.login_ex", auth.get("method").asString)
        assertEquals(
            JsonParser.parseString("""[{"mechanism":"PASSWORD_PLAIN","username":"admin","password":"hunter2"}]"""),
            auth.get("params"),
        )
        assertEquals("core.set_options", received[1].get("method").asString)
        assertEquals(JsonParser.parseString("""[{"legacy_jobs":false}]"""), received[1].get("params"))
    }

    @Test
    fun `filters are nested three levels deep in positional arg 0`() {
        val client = newClient().also { it.loginAsAdmin() }

        runBlocking {
            client.call("pool.query", listOf(listOf(listOf("id", "=", 3))), { it })
        }

        val frame = received.last()
        assertEquals("pool.query", frame.get("method").asString)
        assertEquals(JsonParser.parseString("""[[["id","=",3]]]"""), frame.get("params"))
    }

    @Test
    fun `result is returned and an error frame maps to its reason`() {
        val client = newClient().also { it.loginAsAdmin() }
        onRequest = { frame, ws ->
            ws.send(
                """{"jsonrpc":"2.0","id":${frame.get("id")},"error":{"code":-32602,"message":"Invalid params",""" +
                    """"data":{"errname":"EINVAL","reason":"[EINVAL] filters: bad"}}}"""
            )
        }

        try {
            runBlocking { client.call("pool.query", emptyList(), { it }) }
            fail("expected DashboardApiException")
        } catch (e: DashboardApiException) {
            assertEquals("[EINVAL] filters: bad", e.message)
        }
    }

    @Test
    fun `notifications without an id are ignored`() {
        val client = newClient().also { it.loginAsAdmin() }
        onRequest = { frame, ws ->
            ws.send("""{"jsonrpc":"2.0","method":"collection_update","params":{"collection":"core.get_jobs","msg":"changed","fields":{"id":1}}}""")
            reply(ws, frame, """"pong"""")
        }

        val result = runBlocking { client.call("core.ping", emptyList(), { it.asString }) }

        assertEquals("pong", result)
    }

    @Test
    fun `job call resolves from the core_get_jobs notification`() {
        val client = newClient().also { it.loginAsAdmin() }
        onRequest = { frame, ws ->
            // No direct reply: only the job event naming our call id.
            ws.send(
                """{"jsonrpc":"2.0","method":"collection_update","params":{"collection":"core.get_jobs","msg":"added",""" +
                    """"fields":{"message_ids":[${frame.get("id")}],"id":4242}}}"""
            )
        }

        val jobId = runBlocking { client.call("app.start", listOf("ollama"), { it.asLong }, isJob = true) }

        assertEquals(4242L, jobId)
        assertEquals(JsonParser.parseString("""["ollama"]"""), received.last().get("params"))
    }

    @Test
    fun `job call resolves from a direct numeric result`() {
        val client = newClient().also { it.loginAsAdmin() }
        onRequest = { frame, ws -> reply(ws, frame, "77") }

        val jobId = runBlocking { client.call("app.stop", listOf("ollama"), { it.asLong }, isJob = true) }

        assertEquals(77L, jobId)
    }

    @Test
    fun `job call without any acknowledgement fails with a clear message`() {
        val client = newClient().also { it.loginAsAdmin() }
        onRequest = { _, _ -> }

        try {
            runBlocking { client.call("app.start", listOf("x"), { it.asLong }, timeoutMillis = 300, isJob = true) }
            fail("expected DashboardApiException")
        } catch (e: DashboardApiException) {
            assertEquals("The server didn't acknowledge the task in time.", e.message)
        }
    }

    @Test
    fun `denied login maps to a distinct message`() {
        authResponseType = { "DENIED" }

        try {
            newClient().loginAsAdmin()
            fail("expected TrueNasAuthFailedException")
        } catch (e: TrueNasAuthFailedException) {
            assertEquals("This account doesn't have API access.", e.message)
        }
    }

    @Test
    fun `API-key-shaped secret is retried with API_KEY_PLAIN after a password failure`() {
        val apiKey = "1-" + "A".repeat(16)
        authResponseType = { payload -> if (payload.get("mechanism").asString == "API_KEY_PLAIN") "SUCCESS" else "AUTH_ERR" }

        newClient().loginAsAdmin(apiKey)

        val mechanisms = received.take(2).map {
            it.getAsJsonArray("params")[0].asJsonObject.get("mechanism").asString
        }
        assertEquals(listOf("PASSWORD_PLAIN", "API_KEY_PLAIN"), mechanisms)
        assertEquals(apiKey, received[1].getAsJsonArray("params")[0].asJsonObject.get("api_key").asString)
    }

    @Test
    fun `plain wrong password is not retried as an API key`() {
        authResponseType = { "AUTH_ERR" }

        try {
            newClient().loginAsAdmin("not-an-api-key")
            fail("expected TrueNasAuthFailedException")
        } catch (e: TrueNasAuthFailedException) {
            assertEquals("Invalid username or password.", e.message)
        }
        assertEquals(1, received.count { it.get("method").asString == "auth.login_ex" })
    }
}
