package com.sa.aidesktop.core.browser

data class BrowserLimits(
    val maxPageText: Int = 12_000,
    val maxElements: Int = 180,
    val maxLinks: Int = 80,
    val maxToolOutput: Int = 18_000
)

data class BrowserState(
    val windowId: String,
    val url: String = "",
    val title: String = "",
    val loading: Boolean = false,
    val progress: Int = 0,
    val lastError: String? = null,
    val inspectionVersion: Long = 0L
)

data class BrowserElement(
    val ref: String,
    val index: Int,
    val role: String,
    val text: String = "",
    val ariaLabel: String = "",
    val inputType: String = "",
    val value: String? = null,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val interactable: Boolean = false,
    val required: Boolean? = null
)

data class BrowserPage(
    val url: String,
    val title: String,
    val visibleText: String,
    val headings: List<BrowserElement>,
    val elements: List<BrowserElement>,
    val links: List<BrowserElement>,
    val forms: List<BrowserElement>,
    val truncated: Boolean
)

sealed interface BrowserResult<out T> {
    data class Success<T>(val value: T) : BrowserResult<T>
    data class Failure(val message: String) : BrowserResult<Nothing>
}
