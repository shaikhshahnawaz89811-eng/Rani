package com.sa.aidesktop.core.runtime

object RuntimeRegistry {
    fun runners(): List<CodeRunner> = listOf(
        EmbeddedPythonCodeRunner(),
        CommandCodeRunner("Java","java"){ listOf("java",it) },
        CommandCodeRunner("JavaScript","node"){ listOf("node",it) },
        CommandCodeRunner("C","clang"){ listOf("clang",it,"-o",it.removeSuffix(".c")) },
        CommandCodeRunner("C++","clang++"){ listOf("clang++",it,"-o",it.removeSuffix(".cpp")) }
    )
}
