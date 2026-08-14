package com.sa.aidesktop.core.security

import com.sa.aidesktop.core.ai.ToolRisk
import java.io.File

interface SecurityPolicy {
    fun requiresApproval(risk: ToolRisk): Boolean
    fun isWorkspacePath(path: String): Boolean
    fun canExecuteCommand(command: String): Boolean
}

class DefaultSecurityPolicy(workspaceRoot: String) : SecurityPolicy {
    private val root = File(workspaceRoot).canonicalFile
    override fun requiresApproval(risk: ToolRisk) = risk != ToolRisk.READ_ONLY
    override fun isWorkspacePath(path: String): Boolean = runCatching {
        val raw = File(path)
        val candidate = (if (raw.isAbsolute) raw else File(root, path)).canonicalFile
        candidate.path == root.path || candidate.path.startsWith(root.path + File.separator)
    }.getOrDefault(false)
    override fun canExecuteCommand(command: String): Boolean {
        val c = command.trim()
        if (c.isEmpty() || c.length > 2000) return false
        val blockedFragments = listOf("rm -rf", "su ", "sudo ", "chmod 777", "mkfs", "dd if=", ":(){", "shutdown", "reboot", "fork bomb")
        val shellOperators = listOf("&&", "||", ";", "|", ">", "<", "`", "$(")
        if (blockedFragments.any { c.contains(it, ignoreCase = true) }) return false
        if (shellOperators.any(c::contains)) return false
        if (c.split(Regex("\\s+")).any { it == ".." || it.contains("../") || it.contains("..\\\\") }) return false
        return true
    }
}
