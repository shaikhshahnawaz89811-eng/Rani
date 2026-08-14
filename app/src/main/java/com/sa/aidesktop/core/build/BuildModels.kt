package com.sa.aidesktop.core.build

data class BuildLog(val level:String,val message:String,val timestamp:Long=System.currentTimeMillis())
data class BuildResult(val success:Boolean,val exitCode:Int,val logs:List<BuildLog>,val error:String?=null)
interface BuildService{ suspend fun configure():Result<Unit>;suspend fun build():Result<BuildResult>;suspend fun clean():Result<BuildResult>;suspend fun rebuild():Result<BuildResult>;fun cancel() }
class CommandBuildService(private val runner:suspend(String)->com.sa.aidesktop.core.terminal.TerminalResult):BuildService{
    @Volatile private var cancelled=false
    override suspend fun configure()=Result.success(Unit)
    private suspend fun run(command:String):Result<BuildResult>{if(cancelled)return Result.success(BuildResult(false,130,listOf(BuildLog("WARN","Build cancelled")),"Cancelled"));val r=runner(command);return Result.success(BuildResult(r.exitCode==0,r.exitCode,listOf(BuildLog(if(r.exitCode==0)"INFO" else "ERROR",r.output)),if(r.exitCode==0)null else r.output))}
    override suspend fun build(): Result<BuildResult> { cancelled=false; return run("build") }
    override suspend fun clean(): Result<BuildResult> { cancelled=false; return run("clean") }
    override suspend fun rebuild(): Result<BuildResult> { cancelled=false; run("clean"); return run("build") }
    override fun cancel(){cancelled=true}
}
