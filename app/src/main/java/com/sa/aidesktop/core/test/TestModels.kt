package com.sa.aidesktop.core.test

data class TestCaseResult(val name:String,val passed:Boolean,val file:String?=null,val line:Int?=null,val error:String?=null,val stackTrace:String?=null)
data class TestRunResult(val passed:Int,val failed:Int,val cases:List<TestCaseResult>){val success get()=failed==0}
interface TestRunner { suspend fun discoverTests():Result<List<String>>; suspend fun runTests(selection:List<String> = emptyList()):Result<TestRunResult>; fun stopTests() }
class CommandTestRunner(private val command:suspend(String)->com.sa.aidesktop.core.terminal.TerminalResult):TestRunner {
    @Volatile private var stopped=false
    override suspend fun discoverTests():Result<List<String>>{val r=command("test --list");return if(r.exitCode==0)Result.success(r.output.lines().filter{it.isNotBlank()}) else Result.failure(IllegalStateException(r.output))}
    override suspend fun runTests(selection:List<String>):Result<TestRunResult>{stopped=false;val r=command(if(selection.isEmpty())"test" else "test ${selection.joinToString(" ")}");val pass=if(r.exitCode==0)1 else 0;return Result.success(TestRunResult(pass,if(pass==1)0 else 1,listOf(TestCaseResult("command",pass==1,error=if(pass==1)null else r.output))))}
    override fun stopTests(){stopped=true}
}
