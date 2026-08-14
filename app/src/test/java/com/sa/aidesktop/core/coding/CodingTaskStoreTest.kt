package com.sa.aidesktop.core.coding

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CodingTaskStoreTest {
    @Test
    fun phase4AndPhase6RecordsSurviveSavingEitherRecord() {
        val file = Files.createTempFile("sa-task-store", ".properties").toFile()
        try {
            val store = CodingTaskStore(file)
            store.save(CodingTaskStateRecord(
                taskId = "coding-1", request = "build", workspaceRoot = "/workspace",
                state = CodingTaskState.BUILDING
            ))
            store.saveAgent(AgentTaskRecord(
                taskId = "agent-1", request = "fix", state = AgentTaskState.INSPECTING
            ))

            val raw = store.load()
            assertEquals("coding-1", raw?.get("phase4.taskId"))
            assertEquals("agent-1", store.loadAgent()?.taskId)
            assertEquals(AgentTaskState.INSPECTING, store.loadAgent()?.state)
        } finally {
            file.delete()
        }
    }

    @Test
    fun phase4StateIsNotMistakenForAgentState() {
        val file = Files.createTempFile("sa-task-store", ".properties").toFile()
        try {
            val store = CodingTaskStore(file)
            store.save(CodingTaskStateRecord(
                taskId = "coding-2", request = "build", workspaceRoot = "/workspace",
                state = CodingTaskState.PLANNING
            ))
            assertEquals(null, store.loadAgent())
        } finally {
            file.delete()
        }
    }
}
