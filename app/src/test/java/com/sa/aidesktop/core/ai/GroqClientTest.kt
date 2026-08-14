package com.sa.aidesktop.core.ai

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * These tests exercise GroqClient against a real HTTP server on loopback (JDK's built-in
 * com.sun.net.httpserver, not a mock/fake) so the request/response handling — headers, status
 * codes, JSON parsing, retries, and real socket timeouts — is genuinely tested, not simulated.
 * No call ever leaves localhost.
 */
class GroqClientTest {
    private var server: HttpServer? = null

    @After fun tearDown() { server?.stop(0) }

    private fun startServer(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/chat") { exchange -> handler(exchange) }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}/chat"
    }

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
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
            receivedAuth = exchange.requestHeaders.getFirst("Authorization")
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
