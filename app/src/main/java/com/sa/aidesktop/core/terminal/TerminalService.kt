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
        // Rule 1 (endpoint discoverability): the restricted-sandbox allowlist below still applies
        // until 'bootstrap install' runs; after that, RoutingShellBackend switches every other
        // command to the real bash/busybox/apt environment, so those two commands are mentioned
        // here unconditionally rather than only once real mode is already active.
        if(c=="help"){history.add(c);lastExit=0;return TerminalResult("Supported (restricted sandbox): pwd, ls, cd <dir>, echo <text>, cat <file>, head, tail, grep, find, mkdir, touch, cp, mv, rm, git, python/python3, java, javac, kotlinc, node, clang, clang++\nReal Linux mode: 'bootstrap status' to check, 'bootstrap install' for a one-time real bash/busybox/apt environment (needs internet once, then works offline) — after install, ANY real Linux command works, not just this list.",0)}
        if(c.startsWith("cd ")){val target=c.removePrefix("cd ").trim().trim('"','\'');val candidate=File(target).let{if(it.isAbsolute)it else File(cwd,it.path)}.canonicalFile;val inside=candidate.path==root.path||candidate.path.startsWith(root.path+File.separator);return if(inside&&candidate.isDirectory){cwd=candidate.path;history.add(c);lastExit=0;TerminalResult("",0)}else{lastExit=1;TerminalResult("cd: directory not found or outside workspace",1)}}
        // BUG FIX (Rule 14 self-review after adding RealLinuxShellBackend): the old 10_000L
        // default (from ShellBackend.execute's own default parameter) was fine for the old
        // restricted allowlist, but real commands like `apt install` or `git clone` genuinely
        // need minutes, not seconds — they would have been killed as "timed out" every time.
        // Raised for interactive terminal use specifically (a human is watching and can cancel);
        // ShellBackend's own 10_000L default is untouched for any other caller.
        history.add(c);running=true;return try{backend.execute(c,cwd,INTERACTIVE_TIMEOUT_MS).also{lastExit=it.exitCode}}finally{running=false}
    }
    override fun cancel(){backend.cancel();running=false;lastExit=130}
    override fun state()=TerminalSessionState(cwd,history.toList(),running,lastExit)
    private companion object { const val INTERACTIVE_TIMEOUT_MS = 300_000L }
}
