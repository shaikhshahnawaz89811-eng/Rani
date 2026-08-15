package com.sa.aidesktop.core.browser

import com.sa.aidesktop.core.ai.AIResult
import com.sa.aidesktop.core.ai.AITool
import com.sa.aidesktop.core.ai.ToolResult
import com.sa.aidesktop.core.ai.ToolRisk
import com.sa.aidesktop.core.window.WindowManager
import com.sa.aidesktop.core.window.WindowType
import kotlinx.coroutines.delay
import java.io.File

private fun <T> BrowserResult<T>.toAi(action: String): AIResult<ToolResult> = when (this) {
    is BrowserResult.Success -> {
        val output = when (val result = value) {
            is String -> result.take(18_000)
            else -> "Operation completed successfully."
        }
        AIResult.Success(ToolResult("$action succeeded: $output"))
    }
    is BrowserResult.Failure ->
        AIResult.Failure(com.sa.aidesktop.core.ai.AIError.Execution("$action failed: $message"))
}

private fun BrowserResult<BrowserPage>.toInspectAi(): AIResult<ToolResult> = when (this) {
    is BrowserResult.Failure -> AIResult.Failure(com.sa.aidesktop.core.ai.AIError.Execution("browser.inspect failed: $message"))
    is BrowserResult.Success -> {
        val p = value
        val sb = StringBuilder()
        sb.append("PAGE\n- title: ").append(p.title).append("\n- URL: ").append(p.url).append("\n")
        sb.append("- visible text:\n").append(p.visibleText.take(12_000)).append("\n")
        sb.append("ELEMENTS\n")
        p.elements.forEach {
            sb.append("- ref=").append(it.ref).append(" role=").append(it.role)
                .append(" text=").append(it.text.take(180))
                .append(" label=").append(it.ariaLabel.take(120))
                .append(" type=").append(it.inputType)
                .append(" enabled=").append(it.enabled)
                .append(" visible=").append(it.visible)
                .append(" interactable=").append(it.interactable)
                .append(" value=").append(if (it.inputType.lowercase() in setOf("password")) "[REDACTED]" else it.value?.take(180))
                .append("\n")
        }
        if (p.links.isNotEmpty()) {
            sb.append("LINKS\n")
            p.links.forEach { sb.append("- ref=").append(it.ref).append(" ").append(it.text.take(180)).append(" label=").append(it.ariaLabel.take(100)).append("\n") }
        }
        if (p.headings.isNotEmpty()) {
            sb.append("HEADINGS\n")
            p.headings.forEach { sb.append("- ").append(it.text.take(180)).append("\n") }
        }
        sb.append("TRUNCATED=").append(p.truncated)
        AIResult.Success(ToolResult(sb.toString().take(18_000)))
    }
}

abstract class BrowserTool(
    protected val browser: AndroidBrowserService,
    protected val windowManager: WindowManager,
    private val defaultRisk: ToolRisk
) : AITool {
    override val risk: ToolRisk get() = defaultRisk

    // The browser tools can resolve the currently active browser window themselves, so the model
    // should not be forced to invent an internal window id.
    override val requiredParameters: Set<String>
        get() = parameterHints.keys.filterNot { it == "window_id" }.toSet()

    protected fun windowId(input: Map<String, String>): String? =
        input["window_id"]?.takeIf { it.isNotBlank() }
            ?: windowManager.windows.lastOrNull { it.type == WindowType.BROWSER && it.state.name != "MINIMIZED" }?.id
            ?: windowManager.windows.lastOrNull { it.type == WindowType.BROWSER }?.id

    protected suspend fun ensureWindow(input: Map<String, String>): String? {
        windowId(input)?.let { return it }
        val newWindowId = windowManager.openNew(WindowType.BROWSER)
        repeat(20) {
            if (browser.states.value.containsKey(newWindowId)) return newWindowId
            delay(50)
        }
        return newWindowId
    }
}

class BrowserOpenTool(browser: AndroidBrowserService, wm: WindowManager) : BrowserTool(browser, wm, ToolRisk.WRITE) {
    override val id = "browser.open"
    override val description = "Open a real HTTP(S) URL or web search in a real SA Desktop WebView browser window."
    override val parameterHints = mapOf("url" to "URL or search text", "window_id" to "Optional browser window id")
    override suspend fun execute(input: Map<String, String>): AIResult<ToolResult> {
        val id = ensureWindow(input) ?: return AIResult.Failure(com.sa.aidesktop.core.ai.AIError.Execution("No browser window is available."))
        return browser.open(id, input["url"].orEmpty()).let { when (it) {
            is BrowserResult.Success -> AIResult.Success(ToolResult("Navigation started in $id. The browser will report the real loading state."))
            is BrowserResult.Failure -> AIResult.Failure(com.sa.aidesktop.core.ai.AIError.Execution("browser.open failed: ${it.message}"))
        } }
    }
}

class BrowserNavigationTool(private val action: String, browser: AndroidBrowserService, wm: WindowManager) : BrowserTool(browser, wm, ToolRisk.WRITE) {
    override val id = "browser.$action"
    override val description = "Perform the real WebView $action operation."
    override val parameterHints = mapOf("window_id" to "Browser window id")
    override suspend fun execute(input: Map<String, String>): AIResult<ToolResult> {
        val id = windowId(input) ?: return AIResult.Failure(com.sa.aidesktop.core.ai.AIError.Execution("No browser window is open."))
        val r = when(action) {
            "back" -> browser.back(id)
            "forward" -> browser.forward(id)
            "reload" -> browser.reload(id)
            "stop" -> browser.stop(id)
            else -> BrowserResult.Failure("Unsupported browser navigation action.")
        }
        return r.toAi(id + " " + action)
    }
}

class BrowserInspectTool(browser: AndroidBrowserService, wm: WindowManager) : BrowserTool(browser, wm, ToolRisk.READ_ONLY) {
    override val id = "browser.inspect"
    override val description = "Inspect the actual current webpage DOM/accessibility-relevant controls and visible text using the real WebView."
    override val parameterHints = mapOf("window_id" to "Browser window id")
    override suspend fun execute(input: Map<String, String>) =
        (windowId(input)?.let { browser.inspect(it) }
            ?: BrowserResult.Failure("No browser window is open.")).toInspectAi()
}

class BrowserSearchTool(browser: AndroidBrowserService, wm: WindowManager) : BrowserTool(browser, wm, ToolRisk.READ_ONLY) {
    override val id = "browser.search"
    override val description = "Search the actual loaded webpage text for a query."
    override val parameterHints = mapOf("query" to "Exact text to find", "window_id" to "Browser window id")
    override suspend fun execute(input: Map<String, String>): AIResult<ToolResult> =
        (windowId(input)?.let { browser.search(it, input["query"].orEmpty()) }
            ?: BrowserResult.Failure("No browser window is open.")).toAi("browser.search")
}

class BrowserElementTool(
    private val action: String,
    browser: AndroidBrowserService,
    wm: WindowManager
) : BrowserTool(browser, wm, ToolRisk.WRITE) {
    override val id = "browser.$action"
    override val description = when(action) {
        "click" -> "Click an actual inspected webpage element by its current reference."
        "type" -> "Type into an actual inspected editable webpage control by reference."
        "clear" -> "Clear an actual inspected editable webpage control by reference."
        "select" -> "Select an actual option in an HTML select control by reference."
        "check" -> "Set the checked state of an actual checkbox/radio control by reference."
        "focus" -> "Focus an actual webpage element by reference."
        "scroll" -> "Scroll the real webpage."
        "upload" -> "Upload a real local file through an actual HTML file input."
        "download" -> "Start and monitor a real browser download."
        else -> "Perform a real browser element operation."
    }
    override val parameterHints = when(action) {
        "type" -> mapOf("ref" to "Current element ref from browser.inspect", "text" to "Text to enter", "window_id" to "Browser window id")
        "clear" -> mapOf("ref" to "Current element ref from browser.inspect", "window_id" to "Browser window id")
        "select" -> mapOf("ref" to "Current select ref", "value" to "Exact option value or visible option text", "window_id" to "Browser window id")
        "check" -> mapOf("ref" to "Current checkbox/radio ref", "checked" to "true or false", "window_id" to "Browser window id")
        "scroll" -> mapOf("direction" to "up or down", "amount" to "Pixels, 50-4000", "window_id" to "Browser window id")
        "upload" -> mapOf("ref" to "Current file-input ref", "file_path" to "Readable local file path", "window_id" to "Browser window id")
        "download" -> mapOf("url" to "Optional direct HTTP(S) URL; defaults to current page URL", "window_id" to "Browser window id")
        else -> mapOf("ref" to "Current element ref from browser.inspect", "window_id" to "Browser window id")
    }
    override val requiredParameters: Set<String>
        get() = when (action) {
            "type" -> setOf("ref", "text")
            "clear" -> setOf("ref")
            "select" -> setOf("ref", "value")
            "check" -> setOf("ref", "checked")
            "scroll" -> setOf("direction")
            "upload" -> setOf("ref", "file_path")
            "download" -> emptySet()
            else -> setOf("ref")
        }
    override suspend fun execute(input: Map<String, String>): AIResult<ToolResult> {
        val id = windowId(input) ?: return AIResult.Failure(com.sa.aidesktop.core.ai.AIError.Execution("No browser window is open."))
        val ref = input["ref"].orEmpty()
        val checked = input["checked"]?.toBooleanStrictOrNull()
        val r = when(action) {
            "click" -> browser.click(id, ref)
            "type" -> browser.type(id, ref, input["text"].orEmpty())
            "clear" -> browser.clear(id, ref)
            "select" -> browser.select(id, ref, input["value"].orEmpty())
            "check" -> browser.check(id, ref, checked ?: return AIResult.Failure(com.sa.aidesktop.core.ai.AIError.InvalidRequest("checked must be true or false")))
            "focus" -> browser.focus(id, ref)
            "scroll" -> browser.scroll(id, input["direction"].orEmpty(), input["amount"]?.toIntOrNull() ?: 800)
            "upload" -> browser.upload(id, ref, input["file_path"].orEmpty())
            "download" -> browser.download(id, input["url"])
            else -> BrowserResult.Failure("Unsupported browser action.")
        }
        return r.toAi("browser.$action")
    }
}
