package com.sa.aidesktop.core.website

import com.sa.aidesktop.core.browser.AndroidBrowserService
import com.sa.aidesktop.core.browser.BrowserElement
import com.sa.aidesktop.core.browser.BrowserPage
import com.sa.aidesktop.core.browser.BrowserResult
import kotlinx.coroutines.delay

/** Phase 3 universal AI-website layer. It uses the live Phase-2 browser only; there are no
 * website-specific coordinates, fake DOMs, or synthetic responses. */
class AIWebService(private val browser: AndroidBrowserService) {
    suspend fun inspect(windowId: String): BrowserResult<AIWebPage> = browser.inspect(windowId).map { page ->
        AIWebPage(page, findComposer(page), findSendControl(page), findUploadControl(page), detectState(page))
    }

    suspend fun detect(windowId: String): BrowserResult<AIWebPage> = inspect(windowId)

    suspend fun typeMessage(windowId: String, ref: String, text: String): BrowserResult<String> = browser.type(windowId, ref, text)
    suspend fun sendMessage(windowId: String, ref: String): BrowserResult<String> = browser.click(windowId, ref)
    suspend fun upload(windowId: String, ref: String, path: String): BrowserResult<String> = browser.upload(windowId, ref, path)
    suspend fun download(windowId: String, ref: String): BrowserResult<String> = browser.click(windowId, ref)

    suspend fun waitForResponse(windowId: String, timeoutMs: Long = 60_000L): BrowserResult<AIWebPage> {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(1_000L, 180_000L)
        var previous = ""
        var stable = 0
        while (System.currentTimeMillis() < deadline) {
            when (val result = inspect(windowId)) {
                is BrowserResult.Failure -> return result
                is BrowserResult.Success -> {
                    val page = result.value
                    if (page.state != AIWebState.GENERATING && page.responseText.isNotBlank()) {
                        if (page.responseText == previous) stable++ else stable = 0
                        previous = page.responseText
                        if (stable >= 1) return result
                    } else stable = 0
                }
            }
            delay(500)
        }
        return BrowserResult.Failure("AI response wait timed out; the live page was not observed in a completed response state.")
    }

    private fun findComposer(page: BrowserPage): BrowserElement? = page.elements.asSequence()
        .filter { it.visible && it.enabled && it.interactable }
        .filter { it.role.equals("textbox", true) || it.role.equals("textarea", true) || it.role.equals("contenteditable", true) || it.inputType.equals("text", true) }
        .maxByOrNull { scoreComposer(it) }

    private fun findSendControl(page: BrowserPage): BrowserElement? = page.elements.asSequence()
        .filter { it.visible && it.enabled && it.interactable }
        .filter { it.role.equals("button", true) || it.role.equals("submit", true) }
        .map { it to scoreSend(it) }.maxByOrNull { it.second }?.first

    private fun findUploadControl(page: BrowserPage): BrowserElement? = page.elements.firstOrNull {
        it.visible && it.enabled && (it.inputType.equals("file", true) || it.role.equals("file", true))
    }

    private fun scoreComposer(e: BrowserElement): Int {
        val s = "${e.ariaLabel} ${e.text} ${e.inputType}".lowercase()
        return (if ("message" in s) 6 else 0) + (if ("prompt" in s) 6 else 0) + (if ("chat" in s) 4 else 0) + (if ("ask" in s) 3 else 0) + (if (e.role.equals("textarea", true)) 2 else 0)
    }
    private fun scoreSend(e: BrowserElement): Int {
        val s = "${e.ariaLabel} ${e.text}".lowercase()
        return (if ("send" in s) 8 else 0) + (if ("submit" in s) 4 else 0) + (if ("enter" in s) 2 else 0)
    }

    private fun detectState(page: BrowserPage): AIWebState {
        val all = (page.visibleText + " " + page.elements.joinToString(" ") { "${it.text} ${it.ariaLabel}" }).lowercase()
        return when {
            listOf("captcha", "verify you are human", "human verification").any { it in all } -> AIWebState.WAITING_FOR_USER
            listOf("two-factor", "2fa", "verification code", "one-time password", "otp").any { it in all } -> AIWebState.WAITING_FOR_USER
            listOf("sign in", "log in", "login", "continue with google").any { it in all } && page.elements.count { it.role.equals("textbox", true) } <= 2 -> AIWebState.LOGIN_REQUIRED
            listOf("stop generating", "generating", "thinking").any { it in all } -> AIWebState.GENERATING
            else -> AIWebState.READY
        }
    }

    private fun <T, R> BrowserResult<T>.map(f: (T) -> R): BrowserResult<R> = when (this) {
        is BrowserResult.Failure -> BrowserResult.Failure(message)
        is BrowserResult.Success -> BrowserResult.Success(f(value))
    }
}

enum class AIWebState { READY, GENERATING, LOGIN_REQUIRED, WAITING_FOR_USER }
data class AIWebPage(
    val page: BrowserPage,
    val composer: BrowserElement?,
    val sendControl: BrowserElement?,
    val uploadControl: BrowserElement?,
    val state: AIWebState,
) {
    val responseText: String get() = page.visibleText.takeLast(18_000)
}
