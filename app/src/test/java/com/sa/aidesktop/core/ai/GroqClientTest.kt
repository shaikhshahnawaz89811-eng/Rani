package com.sa.aidesktop.core.ai

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

class GroqClientTest {
    private var server: ServerSocket? = null
    private var serverThread: Thread? = null
    private var serverError: Throwable? = null

    @After
    fun tearDown() {
        server?.close()
        serverThread?.join(1000)
    }

    private fun startServer(handler: (TestHttpRequest) -> TestHttpResponse): String {
        val s = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = s
        serverThread = Thread {
            try {
                while (!s.isClosed) {
                    val socket = try { s.accept() } catch (_: IOException) { break }
                    Thread {
                        socket.use { handleConnection(it, handler) }
                    }.apply { isDaemon = true; start() }
                }
            } catch (t: Throwable) {
                if (!s.isClosed) serverError = t
            }
        }.apply { isDaemon = true; start() }
        return "http://127.0.0.1:${s.localPort}/chat"
    }

    private fun handleConnection(socket: Socket, handler: (TestHttpRequest) -> TestHttpResponse) {
        val input = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
        val requestLine = input.readLine() ?: return
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = input.readLine() ?: return
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = CharArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(body, offset, length - offset)
            if (n < 0) break
            offset += n
        }
        val response = handler(TestHttpRequest(requestLine, headers, String(body, 0, offset)))
        val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
        val output = socket.getOutputStream()
        output.write("HTTP/1.1 ${response.code} OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private data class TestHttpRequest(val requestLine: String, val headers: Map<String, String>, val body: String)
    private data class TestHttpResponse(val code: Int, val body: String)

    private fun respond(request: TestHttpRequest, code: Int, body: String) = TestHttpResponse(code, body)

    @Test fun missingApiKeyFailsWithoutAnyNetworkCall() = runBlocking {
        val client = GroqClient(apiKeyProvider = { null })
        val result = client.chat("hello", GroqSettings(), null, emptyList())
        assertTrue(result is GroqResult.Failure)
        assertTrue((result as GroqResult.Failure).error is GroqError.MissingApiKey)
    }

    @Test fun successfulChatReturnsRealParsedText() = runBlocking {
        var receivedAuth: String? = null
        val url = startServer { request ->
            receivedAuth = request.headers["authorization"]
            respond(request, 200, """{"choices":[{"message":{"role":"assistant","content":"Hello from Groq"},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}""")
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
        val url = startServer { request ->
            capturedBody = request.body
            respond(request, 200, """{"choices":[{"message":{"content":"ok"}}]}""")
        }
        val client = GroqClient(apiKeyProvider = { "sk-should-not-leak" }, endpoint = url)
        client.chat("test prompt", GroqSettings(), null, emptyList())
        assertFalse(capturedBody!!.contains("sk-should-not-leak"))
    }

    @Test fun toolCallsAreParsedFromRealResponse() = runBlocking {
        val url = startServer { request ->
            respond(request, 200, """{"choices":[{"message":{"content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"src/main.py\"}"}}]},"finish_reason":"tool_calls"}]}""")
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
        val url = startServer { request -> respond(request, 401, """{"error":{"message":"Invalid API Key"}}""") }
        val client = GroqClient(apiKeyProvider = { "bad-key" }, endpoint = url)
        val result = client.chat("hi", GroqSettings(retryLimit = 0), null, emptyList())
        assertTrue((result as GroqResult.Failure).error is GroqError.InvalidApiKey)
    }

    @Test fun rateLimitIsRetriedThenSucceedsWithinRetryLimit() = runBlocking {
        val attempts = AtomicInteger(0)
        val url = startServer { request ->
            if (attempts.getAndIncrement() == 0) respond(request, 429, """{"error":{"message":"rate limited"}}""")
            else respond(request, 200, """{"choices":[{"message":{"content":"recovered"}}]}""")
        }
        val client = GroqClient(apiKeyProvider = { "k" }, endpoint = url)
        val result = client.chat("hi", GroqSettings(retryLimit = 1), null, emptyList())
        assertTrue(result is GroqResult.Success)
        assertEquals("recovered", (result as GroqResult.Success).value.text)
        assertEquals(2, attempts.get())
    }

    @Test fun rateLimitExhaustsRetriesAndReportsFailure() = runBlocking {
        val url = startServer { request -> respond(request, 429, """{"error":{"message":"still limited"}}""") }
        val client = GroqClient(apiKeyProvider = { "k" }, endpoint = url)
        val result = client.chat("hi", GroqSettings(retryLimit = 1), null, emptyList())
        assertTrue((result as GroqResult.Failure).error is GroqError.RateLimited)
    }

    @Test fun malformedJsonIsReportedNotCrashed() = runBlocking {
        val url = startServer { request -> respond(request, 200, "not json at all {{{") }
        val client = GroqClient(apiKeyProvider = { "k" }, endpoint = url)
        val result = client.chat("hi", GroqSettings(retryLimit = 0), null, emptyList())
        assertTrue((result as GroqResult.Failure).error is GroqError.MalformedResponse)
    }

    @Test fun serverErrorMapsToServiceUnavailable() = runBlocking {
        val url = startServer { request -> respond(request, 503, """{"error":{"message":"overloaded"}}""") }
        val client = GroqClient(apiKeyProvider = { "k" }, endpoint = url)
        val result = client.chat("hi", GroqSettings(retryLimit = 0), null, emptyList())
        assertTrue((result as GroqResult.Failure).error is GroqError.ServiceUnavailable)
    }

    @Test fun realSocketTimeoutIsReportedAsTimeout() = runBlocking {
        val url = startServer { request ->
            Thread.sleep(1500)
            respond(request, 200, """{"choices":[{"message":{"content":"too late"}}]}""")
        }
        val client = GroqClient(apiKeyProvider = { "k" }, endpoint = url)
        val result = client.chat("hi", GroqSettings(timeoutMs = 300, retryLimit = 0), null, emptyList())
        assertTrue((result as GroqResult.Failure).error is GroqError.Timeout)
    }
    @Test fun conversationRequestPreservesAssistantAndToolMessages() = runBlocking {
        var capturedBody: String? = null
        val url = startServer { request ->
            capturedBody = request.body
            respond(request, 200, """{"choices":[{"message":{"content":"done"}}]}""")
        }
        val client = GroqClient(apiKeyProvider = { "k" }, endpoint = url)
        client.chatConversation(
            messages = listOf(
                GroqMessage("user", "read file"),
                GroqMessage(
                    "assistant",
                    "",
                    toolCalls = listOf(GroqToolCall("call_1", "read_file", mapOf("path" to "a.txt")))
                ),
                GroqMessage(
                    "tool",
                    "hello",
                    toolCallId = "call_1",
                    name = "read_file"
                )
            ),
            settings = GroqSettings(),
            systemPrompt = "system",
            tools = listOf(ToolDescriptor("read_file", "Read", mapOf("path" to "file")))
        )
        assertTrue(capturedBody!!.contains("\"role\":\"assistant\""))
        assertTrue(capturedBody!!.contains("\"tool_calls\""))
        assertTrue(capturedBody!!.contains("\"tool_call_id\":\"call_1\""))
        assertTrue(capturedBody!!.contains("\"name\":\"read_file\""))
        assertTrue(capturedBody!!.contains("\"content\":\"hello\""))
    }

    @Test fun payloadTooLarge413IsRetriedWithTrimmedMessagesThenSucceeds() = runBlocking {
        val attempts = AtomicInteger(0)
        var lastRequestMessageCount = -1
        val url = startServer { request ->
            val count = Regex("\"role\"").findAll(request.body).count()
            lastRequestMessageCount = count
            if (attempts.getAndIncrement() == 0) respond(request, 413, """{"error":{"message":"Request too large"}}""")
            else respond(request, 200, """{"choices":[{"message":{"content":"recovered"}}]}""")
        }
        val client = GroqClient(apiKeyProvider = { "k" }, endpoint = url)
        val result = client.chatConversation(
            messages = listOf(
                GroqMessage("user", "first old message"),
                GroqMessage("assistant", "old reply"),
                GroqMessage("user", "latest message")
            ),
            settings = GroqSettings(retryLimit = 1),
            systemPrompt = null,
            tools = emptyList()
        )
        assertTrue(result is GroqResult.Success)
        assertEquals(2, attempts.get())
        // The retry after 413 must have dropped the oldest message, not resent the same payload.
        assertEquals(2, lastRequestMessageCount)
    }

    @Test fun payloadTooLargeExhaustsRetriesAndReportsFailure() = runBlocking {
        val url = startServer { request -> respond(request, 413, """{"error":{"message":"still too large"}}""") }
        val client = GroqClient(apiKeyProvider = { "k" }, endpoint = url)
        val result = client.chatConversation(
            messages = listOf(GroqMessage("user", "a"), GroqMessage("user", "b"), GroqMessage("user", "c")),
            settings = GroqSettings(retryLimit = 5),
            systemPrompt = null,
            tools = emptyList()
        )
        assertTrue((result as GroqResult.Failure).error is GroqError.PayloadTooLarge)
    }

    @Test fun contextLengthWordedHttp400IsTreatedAsPayloadTooLarge() = runBlocking {
        val url = startServer { request ->
            respond(request, 400, """{"error":{"message":"This model's maximum context length is exceeded, please reduce the length of the messages"}}""")
        }
        val client = GroqClient(apiKeyProvider = { "k" }, endpoint = url)
        val result = client.chat("hi", GroqSettings(retryLimit = 0), null, emptyList())
        assertTrue((result as GroqResult.Failure).error is GroqError.PayloadTooLarge)
    }

    @Test fun unrelatedHttp400StaysGenericHttpError() = runBlocking {
        val url = startServer { request -> respond(request, 400, """{"error":{"message":"Invalid request: bad JSON schema"}}""") }
        val client = GroqClient(apiKeyProvider = { "k" }, endpoint = url)
        val result = client.chat("hi", GroqSettings(retryLimit = 0), null, emptyList())
        assertTrue((result as GroqResult.Failure).error is GroqError.Http)
    }

    @Test fun malformedToolCallNameIsRecoveredFromGroq400() = runBlocking {
        val rawMessage = "tool call validation failed: attempted to call tool " +
            "'browser.open{\"url\": \"https://www.youtube.com/results?search_query=sad+songs\", " +
            "\"window_id\": \"music\"}' which was not in request.tools"
        val url = startServer { request ->
            respond(request, 400, """{"error":{"message":${JSONObject.quote(rawMessage)}}}""")
        }
        val client = GroqClient(apiKeyProvider = { "k" }, endpoint = url)
        val result = client.chat("sad song lagao", GroqSettings(retryLimit = 0), null, emptyList())
        val success = result as GroqResult.Success
        assertTrue(success.value.recoveredFromMalformedToolCall)
        assertEquals(1, success.value.toolCalls.size)
        val call = success.value.toolCalls.first()
        assertEquals("browser.open", call.name)
        assertEquals("https://www.youtube.com/results?search_query=sad+songs", call.arguments["url"])
        assertEquals("music", call.arguments["window_id"])
    }

    @Test fun http400WithoutEmbeddedJsonStaysGenericHttpError() = runBlocking {
        // Same wording, but the model's bogus name has no '{' at all — nothing safe to recover,
        // so this must fall through to a normal Http failure instead of guessing.
        val rawMessage = "tool call validation failed: attempted to call tool 'made_up_tool' which was not in request.tools"
        val url = startServer { request ->
            respond(request, 400, """{"error":{"message":${JSONObject.quote(rawMessage)}}}""")
        }
        val client = GroqClient(apiKeyProvider = { "k" }, endpoint = url)
        val result = client.chat("hi", GroqSettings(retryLimit = 0), null, emptyList())
        assertTrue((result as GroqResult.Failure).error is GroqError.Http)
    }

}
