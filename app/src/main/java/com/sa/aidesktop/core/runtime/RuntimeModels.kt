package com.sa.aidesktop.core.runtime

data class ExecutionResult(val output:String, val exitCode:Int, val durationMs:Long)
enum class RuntimeStatus { AVAILABLE, UNAVAILABLE, FAILED }
interface CodeRunner { val language:String; fun prepare():Boolean; fun build(sourcePath:String):ExecutionResult; fun run(sourcePath:String):ExecutionResult; fun stop(); fun status():RuntimeStatus }
