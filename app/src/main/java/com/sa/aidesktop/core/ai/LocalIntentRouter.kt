package com.sa.aidesktop.core.ai

/**
 * Explicit command bridge for the compact offline model. It deliberately handles only commands
 * whose intent and parameters can be extracted safely without asking the tiny model to invent a
 * function call. Ambiguous requests continue to the real local model.
 */
object LocalIntentRouter {
    private val expressionOnly = Regex("""^[\s\d()+\-*/.]+$""")

    fun resolve(prompt: String, registry: ToolRegistry): ToolRequest? {
        val p = prompt.trim()
        val lower = p.lowercase()

        fun request(id: String, input: Map<String, String> = emptyMap()): ToolRequest? {
            val tool = registry.find(id) ?: return null
            return ToolRequest(tool.id, input, tool.risk)
        }

        if (expressionOnly.matches(p) && p.any(Char::isDigit)) {
            return request("calculator.calculate", mapOf("expression" to p))
        }

        if (Regex("""(?i)^(what is|calculate|calculator|calc)\s+""").containsMatchIn(p)) {
            val expression = p.replaceFirst(
                Regex("""(?i)^(what is|calculate|calculator|calc)\s+"""),
                ""
            ).trim().removeSuffix("?")
            if (expressionOnly.matches(expression) && expression.any(Char::isDigit)) {
                return request("calculator.calculate", mapOf("expression" to expression))
            }
        }

        if (Regex("""(?i)\b(time|current time|what time is it|time batao|samay batao)\b""").containsMatchIn(p)) {
            return request("device.time")
        }

        if (Regex("""(?i)\b(date|today's date|today date|aaj ki date|date batao)\b""").containsMatchIn(p)) {
            return request("device.date")
        }

        if (Regex("""(?i)\b(battery|battery percentage|battery kitni|battery batao)\b""").containsMatchIn(p)) {
            return request("device.battery")
        }

        if (Regex("""(?i)^(list|show|display)\s+(all\s+)?files\b""").containsMatchIn(p) ||
            lower == "list files" || lower == "show files") {
            val path = Regex("""(?i)^(?:list|show|display)\s+(?:all\s+)?files(?:\s+in\s+(.+))?$""")
                .find(p)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            return request("list_files", mapOf("path" to path))
        }

        Regex("""(?i)^(?:read|open)\s+file\s+(.+)$""").find(p)?.let {
            return request("read_file", mapOf("path" to it.groupValues[1].trim()))
        }

        Regex("""(?i)^(?:search|find)\s+files?\s+(?:for\s+)?(.+)$""").find(p)?.let {
            return request("search_files", mapOf("query" to it.groupValues[1].trim()))
        }

        if (Regex("""(?i)^(git\s+status|show\s+git\s+status)$""").matches(p)) {
            return request("git.status")
        }
        if (Regex("""(?i)^(git\s+diff|show\s+git\s+diff)$""").matches(p)) {
            return request("git.diff", mapOf("cached" to "false"))
        }
        if (Regex("""(?i)^(git\s+log|show\s+git\s+log)$""").matches(p)) {
            return request("git.log", mapOf("limit" to "10"))
        }
        if (Regex("""(?i)^(git\s+remote|show\s+git\s+remote)$""").matches(p)) {
            return request("git.remote")
        }

        if (Regex("""(?i)^(project\s+tree|inspect\s+project|show\s+project\s+tree)$""").matches(p)) {
            return request("project.inspect_tree", mapOf("max_entries" to "300"))
        }
        if (Regex("""(?i)^(project\s+info|discover\s+project)$""").matches(p)) {
            return request("project.discover")
        }
        if (Regex("""(?i)^(task\s+status|agent\s+status)$""").matches(p)) {
            return request("task.status")
        }

        return null
    }
}
