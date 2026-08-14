package com.sa.aidesktop.core

import com.sa.aidesktop.core.ai.*
import com.sa.aidesktop.core.ai.tools.*
import com.sa.aidesktop.core.app.*
import com.sa.aidesktop.core.files.*
import com.sa.aidesktop.core.memory.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ArchitectureEndpointTest {
    @Test fun registryRejectsDuplicateIds() {
        val r=ApplicationRegistry(); assertTrue(r.register(ApplicationDescriptor("dev","Developer","1","DEVELOPER"))); assertFalse(r.register(ApplicationDescriptor("dev","Duplicate","1","DEVELOPER")))
    }
    @Test fun memoryCanBeClearedByType() {
        val m=InMemoryMemoryStore();m.add(MemoryEntry("1",MemoryType.PROJECT,"p"));m.add(MemoryEntry("2",MemoryType.CONVERSATION,"c"));m.clear(MemoryType.PROJECT);assertEquals(0,m.list(MemoryType.PROJECT).size);assertEquals(1,m.list(MemoryType.CONVERSATION).size)
    }
    @Test fun toolGatewayRequiresApprovalForWrite()=runBlocking {
        val gateway=ToolExecutionGateway(ToolRegistry(listOf(WriteFileTool(InMemoryProjectFileService()))));val request=ToolRequest("write_file",mapOf("path" to "src/a.txt","content" to "x"),ToolRisk.WRITE);assertTrue(gateway.execute(request) is AIResult.Failure);assertTrue(gateway.execute(request,true) is AIResult.Success)
    }
}
