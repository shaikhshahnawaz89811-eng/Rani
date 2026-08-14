package com.sa.aidesktop.core.terminal

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

interface TerminalService { fun execute(command:String):TerminalResult; fun cancel(); fun state():TerminalSessionState }
class EmbeddedTerminalService(workspace:File,private val backend:ShellBackend=EmbeddedShellBackend(workspace)):TerminalService{
    private val root=workspace.canonicalFile
    private var cwd=root.path
    private val history=CopyOnWriteArrayList<String>()
    @Volatile private var running=false
    @Volatile private var lastExit:Int?=null
    override fun execute(command:String):TerminalResult{
        val c=command.trim();if(c.isBlank())return TerminalResult("",0)
        if(c=="clear"){history.add(c);lastExit=0;return TerminalResult("__CLEAR__",0)}
        if(c=="help"){history.add(c);lastExit=0;return TerminalResult("Supported: pwd, ls, cd <dir>, echo <text>, cat <file>, head, tail, grep, find, mkdir, touch, cp, mv, rm, git, python/python3, java, javac, kotlinc, node, clang, clang++",0)}
        if(c.startsWith("cd ")){val target=c.removePrefix("cd ").trim().trim('"','\'');val candidate=File(target).let{if(it.isAbsolute)it else File(cwd,it.path)}.canonicalFile;val inside=candidate.path==root.path||candidate.path.startsWith(root.path+File.separator);return if(inside&&candidate.isDirectory){cwd=candidate.path;history.add(c);lastExit=0;TerminalResult("",0)}else{lastExit=1;TerminalResult("cd: directory not found or outside workspace",1)}}
        history.add(c);running=true;return try{backend.execute(c,cwd).also{lastExit=it.exitCode}}finally{running=false}
    }
    override fun cancel(){backend.cancel();running=false;lastExit=130}
    override fun state()=TerminalSessionState(cwd,history.toList(),running,lastExit)
}
