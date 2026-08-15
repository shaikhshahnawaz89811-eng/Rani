package com.sa.aidesktop.core.ai

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * These tests exercise GroqClient against a real HTTP server on loopback using only
 * standard Java networking APIs. No Android API, mock server library, or external
 * network is used. The server is bound to 127.0.0.1 only.
 */
class GroqClientTest {
    private var server: ServerSocket? = null
    private var serverThread: Thread? = null

    @After
    fun tearDown() {
        server?.close()
        serverThread?.join(1000)
        server = null
        serverThread = null
    }

    private class TestExchange(
        val requestHeaders: Map<String, String>,
        val requestBody: InputStream,
        private val socket: Socket
    ) {
        fun sendResponseHeaders(code: Int, length: Long) {
            val reason = when (code) {
                200 -> "OK"
                401 -> "Unauthorized"
                429 -> "Too Many Requests"
                503 -> "Service Unavailable"
                else -> "Response"
            }
            val output = socket.getOutputStream()
            output.write(
                (
                    "HTTP/1.1 $code $reason\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: $length\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(Charsets.UTF_8)
            )
        }

        val responseBody: java.io.OutputStream
            get() = socket.getOutputStream()
    }

    private fun startServer(handler: (TestExchange) -> Unit): String {
        val s = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        server = s

        serverThread = Thread {
            while (!s.isClosed) {
                try {
                    val socket = s.accept()
                    Thread {
                        socket.use { handleConnection(it, handler) }
                    }.start()
                } catch (_: java.net.SocketException) {
                    if (!s.isClosed) break
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        return "http://127.0.0.1:${s.localPort}/chat"
    }

    private fun handleConnection(socket: Socket, handler: (TestExchange) -> Unit) {
        val input = socket.getInputStream()
        val reader = BufferedReader(InputStreamReader(input, Charsets.ISO_8859_1))

        val requestLine = reader.readLine() ?: return
        if (requestLine.isEmpty()) return

        val headers = linkedMapOf<String, String>()
        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                val name = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                headers[name] = value
                if (name.equals("Content-Length", ignoreCase = true)) {
                    contentLength = value.toIntOrNull() ?: 0
                }
            }
        }

        val body = ByteArray(contentLength)
        var offset = 0
        while (offset < body.size) {
            val count = input.read(body, offset, body.size - offset)
            if (count < 0) break
            offset += count
        }

        val exchange = TestExchange(headers, body.inputStream(), socket)
        handler(exchange)
    }

    private fun respond(exchange: TestExchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    @Test fun missingApiKeyFailsWithoutAnyNetworkCall() = runBlocking {
        val client = GroqClient(apiKeyProvider = { null })
        val result = client.chat("hello", GroqSettings(), null, emptyList())
        assertTrue(result is GroqResult.Failure)
        assertTrue((result as GroqResult.Failure).error is GroqError.MissingApiKey)
    }

    @Test fun successfulChatReturnsRealParsedText() = runBlocking {
        var receivedAuth: String? = null
        val url = startServer { exchange ->
            receivedAuth = exchange.requestHeaders.entries
                .firstOrNull { it.key.equals("Authorization", ignoreCase = true) }?.value
            respond(exchange, 200, """{"choices":[{"message":{"role":"assistant","content":"Hello from Groq"},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}""")
        }
        val client = GroqClient(apiKeyProvider = { "sk-real-test-key" }, endpoint = url)
        val result = client.chat("hi", GroqSettings(), "system", emptyList())
        assertTrue(result is GroqResult.Success)
        val success = result as GroqResult.Success
        assertEquals("Hello from Groq", success.value.text)
        assertEquals(8, success.value.usage?.totalTokens)
        assertEquals("Bearer sk-real-test-key", receivedAuth)
    }

    @Test fun apiKeyNeverAppearsInRequestBody() = runBlocking {
        var capturedBody: String? = null
        val url = startServer { exchange ->
            capturedBody = exchange.requestBody.bufferedReader().readText()
            respond(exchange, 200, """{"choices":[{"message":{"content":"ok"}}]}""")
        }
        val client = GroqClient(apiKeyProvider = { "sk-should-not-leak" }, endpoint = url)
        client.chat("test prompt", GroqSettings(), null, emptyList())
        assertFalse(capturedBody!!.contains("sk-should-not-leak"))
    }

    @Test fun toolCallsAreParsedFromRealResponse() = runBlocking {
        val url = startServer { exchange ->
            respond(exchange, 200, """{"choices":[{"message":{"content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"src/main.py\"}"}}]},"finish_reason":"tool_calls"}]}""")
        }
        val client = GroqClient(apiKeyProvider = { "k" }, endpoint = url)
        val descriptor = ToolDescriptor("read_file", "Read a file", mapOf("path" to "file path"))
        val result = client.chat("read main.py", GroqSettings(), null, listOf(descriptor))
        assertTrue(result is GroqResult.Success)
        val call = (result as GroqResult.Success).value.toolCalls.single()
        assertEquals("read_file", call.name)
        assertEquals("src/main.py", call.arguments["path"])
    }

    @Test fun invalidApiKeyIsReportedAsSuchNotAsGenericFailure() = runBlocking {
        val url = startServer { exchange -> respond(exchange, 401, """{"error":{"message":"Invalid API Key"}}""") }
        val client = GroqClient(apiKeyProvider = { "bad-key" }, endpoint = url)
        val result = client.chat("hi", GroqSettings(retryLimit = 0), null, emptyList())
        assertTrue((result as GroqResult.Failure).error is GroqError.InvalidApiKey)
    }

    @Test fun rateLimitIsRetriedThenSucceedsWithinRetryLimit() = runBlocking {
        val attempts = AtomicInteger(0)
        val url = startServer { exchange ->
            if (attempts.getAndIncrement() == 0) respond(exchange, 429, """{"error":{"message":"rate limited"}}""")
            else respond(exchange, 200, """{"choices":[{"message":{"content":"recovered"}}]}""")
        }
        val client = GroqClient(apiKeyProvider = { "k" }, endpoint = url)
        val result = client.chat("hi", GroqSettings(retryLimit = 1), null, emptyList())
        assertTrue(result is GroqResult.Success)
        assertEquals("recovered", (result as GroqResult.Success).value.text)
        assertEquals(2, attempts.get())
    }

    @Test fun rateLimitExhaustsRetriesAndReportsFailure() = runBlocking {
        val url = startServer { exchange -> respond(exchange, 429, """{"error":{"message":"still limited"}}""") }
        val client = GroqClient(apiKeyProvider = { "k" }, endpoint = url)
        val result = client.chat("hi", GroqSettings(retryLimit = 1), null, emptyList())
        assertTrue((result as GroqResult.Failure).error is GroqError.RateLimited)
    }

    @Test fun malformedJsonIsReportedNotCrashed() = runBlocking {
        val url = startServer { exchange -> respond(exchange, 200, "not json at all {{{") }
        val client = GroqClient(apiKeyProvider = { "k" }, endpoint = url)
        val result = client.chat("hi", GroqSettings(retryLimit = 0), null, emptyList())
        assertTrue((result as GroqResult.Failure).error is GroqError.MalformedResponse)
    }

    @Test fun serverErrorMapsToServiceUnavailable() = runBlocking {
        val url = startServer { exchange -> respond(exchange, 503, """{"error":{"message":"overloaded"}}""") }
        val client = GroqClient(apiKeyProvider = { "k" }, endpoint = url)
        val result = client.chat("hi", GroqSettings(retryLimit = 0), null, emptyList())
        assertTrue((result as GroqResult.Failure).error is GroqError.ServiceUnavailable)
    }

    @Test fun realSocketTimeoutIsReportedAsTimeout() = runBlocking {
        val url = startServer { exchange ->
            Thread.sleep(1500)
            respond(exchange, 200, """{"choices":[{"message":{"content":"too late"}}]}""")
        }
        val client = GroqClient(apiKeyProvider = { "k" }, endpoint = url)
        val result = client.chat("hi", GroqSettings(timeoutMs = 300, retryLimit = 0), null, emptyList())
        assertTrue((result as GroqResult.Failure).error is GroqError.Timeout)
    }
}
