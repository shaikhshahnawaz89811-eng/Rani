package com.sa.aidesktop.core.build
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import com.sa.aidesktop.core.terminal.TerminalResult
class BuildServiceTest { @Test fun buildMapsExitCode()=runBlocking { val s=CommandBuildService{s->TerminalResult(if(s=="build")"ok" else "clean",0)}; assertTrue(s.build().getOrThrow().success) } }
