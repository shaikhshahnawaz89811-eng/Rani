package com.sa.aidesktop.core.runtime

import java.util.concurrent.TimeUnit

class CommandCodeRunner(override val language:String,private val executable:String,private val command:(String)->List<String>):CodeRunner{
    @Volatile private var process:Process?=null
    override fun prepare():Boolean=runCatching{ProcessBuilder("sh","-c","command -v $executable").redirectErrorStream(true).start().let{p->p.waitFor(2,TimeUnit.SECONDS)&&p.exitValue()==0}}.getOrDefault(false)
    override fun build(sourcePath:String)=runInternal(command(sourcePath))
    override fun run(sourcePath:String)=runInternal(command(sourcePath))
    override fun stop(){process?.destroyForcibly();process=null}
    override fun status()=if(prepare())RuntimeStatus.AVAILABLE else RuntimeStatus.UNAVAILABLE
    private fun runInternal(args:List<String>):ExecutionResult{val start=System.currentTimeMillis();return try{val p=ProcessBuilder(args).redirectErrorStream(true).start();process=p;val finished=p.waitFor(30,TimeUnit.SECONDS);if(!finished){p.destroyForcibly();ExecutionResult("Process timed out after 30 seconds",124,System.currentTimeMillis()-start)}else{val out=p.inputStream.bufferedReader().readText();ExecutionResult(out,p.exitValue(),System.currentTimeMillis()-start)}}catch(e:Exception){ExecutionResult(e.message.orEmpty(),1,System.currentTimeMillis()-start)}finally{process=null}}
}
