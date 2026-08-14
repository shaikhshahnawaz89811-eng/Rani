package com.sa.aidesktop.core.test
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import com.sa.aidesktop.core.terminal.TerminalResult
class TestRunnerTest { @Test fun passingCommandProducesPassingResult()=runBlocking { val t=CommandTestRunner{TerminalResult("ok",0)}; assertTrue(t.runTests().getOrThrow().success) } }
