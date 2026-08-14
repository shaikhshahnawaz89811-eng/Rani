package com.sa.aidesktop.core.coding

enum class CodingTaskState { PLANNING, INSPECTING, EDITING, BUILDING, TESTING, FIXING, WAITING_FOR_USER, COMPLETED, FAILED }
data class FileChangeSet(val created:List<String> = emptyList(), val modified:List<String> = emptyList(), val deleted:List<String> = emptyList(), val renamed:List<String> = emptyList())
data class CodingTaskStateRecord(
    val taskId:String,
    val request:String,
    val workspaceRoot:String,
    val state:CodingTaskState,
    val plan:List<String> = emptyList(),
    val inspectedFiles:List<String> = emptyList(),
    val changes:FileChangeSet = FileChangeSet(),
    val commands:List<String> = emptyList(),
    val buildOutput:String = "",
    val testOutput:String = "",
    val unresolvedErrors:List<String> = emptyList(),
    val waitingReason:String? = null,
    val iteration:Int = 0
)
