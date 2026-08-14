package com.sa.aidesktop.core.git

sealed interface GitResult<out T> {
    data class Success<T>(val value: T): GitResult<T>
    data class Failure(val error: GitError): GitResult<Nothing>
}

data class GitChange(val path: String, val state: String)
data class GitStatus(val branch: String, val changes: List<GitChange>, val clean: Boolean = changes.isEmpty())
data class GitCommandResult(val exitCode: Int, val stdout: String, val stderr: String) { val ok get() = exitCode == 0 }
sealed interface GitError { data class Validation(val message:String):GitError; data class NotAvailable(val message:String):GitError; data class Command(val code:Int,val message:String):GitError; data class Permission(val message:String):GitError }

interface GitService {
    suspend fun init(): GitResult<Unit>
    suspend fun clone(url:String, destination:String): GitResult<Unit>
    suspend fun status(): GitResult<GitStatus>
    suspend fun add(paths:List<String>): GitResult<Unit>
    suspend fun commit(message:String): GitResult<String>
    suspend fun push(confirmed:Boolean = false): GitResult<String>
    suspend fun pull(): GitResult<String>
    suspend fun fetch(): GitResult<String>
    suspend fun branch(name:String? = null): GitResult<List<String>>
    suspend fun checkout(name:String): GitResult<Unit>
    suspend fun merge(name:String): GitResult<String>
    suspend fun diff(): GitResult<String>
    suspend fun log(limit:Int = 20): GitResult<String>
    suspend fun remote(): GitResult<String>
}

interface GitBackend { suspend fun run(args:List<String>): GitCommandResult }

class ProcessGitBackend(private val root: java.io.File): GitBackend {
    override suspend fun run(args:List<String>): GitCommandResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val p = ProcessBuilder(listOf("git") + args).directory(root).redirectErrorStream(false).start()
            val out = p.inputStream.bufferedReader().readText(); val err = p.errorStream.bufferedReader().readText(); val code = p.waitFor()
            GitCommandResult(code,out,err)
        } catch (e: Exception) { GitCommandResult(127,"",e.message ?: "git executable unavailable") }
    }
}

class CommandGitService(private val root:java.io.File, private val backend:GitBackend = ProcessGitBackend(root)):GitService {
    private fun validatePath(p:String)=p.isNotBlank() && !p.contains("..") && !p.startsWith("/")
    private fun map(r:GitCommandResult):GitResult<Nothing> = when { r.exitCode==127 -> GitResult.Failure(GitError.NotAvailable("Git runtime is not available on this device.")); r.exitCode==128 && (r.stderr.contains("permission",true)||r.stderr.contains("denied",true))->GitResult.Failure(GitError.Permission(r.stderr.trim())); r.ok -> GitResult.Failure(GitError.Command(0,"unexpected result")); else -> GitResult.Failure(GitError.Command(r.exitCode,(r.stderr.ifBlank{r.stdout}).trim())) }
    private fun unit(r:GitCommandResult):GitResult<Unit> = if(r.ok) GitResult.Success(Unit) else map(r)
    override suspend fun init()=unit(backend.run(listOf("init")))
    override suspend fun clone(url:String,destination:String):GitResult<Unit>{ if(url.length>2048 || (!url.startsWith("https://")&&!url.startsWith("ssh://")&&!url.startsWith("git@"))) return GitResult.Failure(GitError.Validation("Unsupported repository URL")); if(!validatePath(destination)) return GitResult.Failure(GitError.Validation("Invalid destination")); return unit(backend.run(listOf("clone",url,destination))) }
    override suspend fun status():GitResult<GitStatus>{ val r=backend.run(listOf("status","--porcelain","-b")); if(!r.ok)return map(r); val lines=r.stdout.lines().filter{it.isNotBlank()}; val branch=lines.firstOrNull()?.removePrefix("## ")?.substringBefore("...")?.trim().orEmpty(); val changes=lines.drop(1).mapNotNull{line->if(line.length<3)null else GitChange(line.substring(3).trim(), when(line.take(2).trim()){"M"->"Modified";"A"->"Added";"D"->"Deleted";"??"->"Untracked";else->line.take(2).trim()})}; return GitResult.Success(GitStatus(branch,changes)) }
    override suspend fun add(paths:List<String>):GitResult<Unit>{ if(paths.isEmpty())return GitResult.Failure(GitError.Validation("No files selected")); if(paths.any{!validatePath(it)})return GitResult.Failure(GitError.Validation("Invalid file path")); return unit(backend.run(listOf("add")+paths)) }
    override suspend fun commit(message:String):GitResult<String>{if(message.isBlank())return GitResult.Failure(GitError.Validation("Commit message required")); val r=backend.run(listOf("commit","-m",message)); return if(r.ok)GitResult.Success(r.stdout.trim()) else map(r)}
    override suspend fun push(confirmed:Boolean)=if(!confirmed)GitResult.Failure(GitError.Validation("Push requires explicit confirmation.")) else text(backend.run(listOf("push")))
    override suspend fun pull()=text(backend.run(listOf("pull")))
    override suspend fun fetch()=text(backend.run(listOf("fetch")))
    override suspend fun branch(name:String?):GitResult<List<String>>{val r=if(name==null)backend.run(listOf("branch","--list")) else backend.run(listOf("switch","-c",name)); return if(name!=null){ if(r.ok) GitResult.Success(emptyList()) else map(r) } else if(r.ok) GitResult.Success(r.stdout.lines().filter{it.isNotBlank()}.map{it.removePrefix("*").trim()}) else map(r)}
    override suspend fun checkout(name:String)=unit(backend.run(listOf("switch",name)))
    override suspend fun merge(name:String)=text(backend.run(listOf("merge",name)))
    override suspend fun diff()=text(backend.run(listOf("diff")))
    override suspend fun log(limit:Int)=text(backend.run(listOf("log","-n",limit.coerceIn(1,100).toString(),"--oneline")))
    override suspend fun remote()=text(backend.run(listOf("remote","-v")))
    private fun text(r:GitCommandResult):GitResult<String> = if(r.ok)GitResult.Success(r.stdout.trim()) else map(r)
}

class DemoGitService:GitService {
    private var committed=""
    override suspend fun init()=GitResult.Success(Unit)
    override suspend fun clone(url:String,destination:String)=GitResult.Success(Unit)
    override suspend fun status()=GitResult.Success(GitStatus("main",listOf(GitChange("src/main.py","Modified"),GitChange("README.md","Modified"))))
    override suspend fun add(paths:List<String>)=if(paths.isEmpty())GitResult.Failure(GitError.Validation("No files selected")) else GitResult.Success(Unit)
    override suspend fun commit(message:String)=if(message.isBlank())GitResult.Failure(GitError.Validation("Commit message required")) else {committed=message;GitResult.Success("Commit created locally: $message")}
    override suspend fun push(confirmed:Boolean)=if(confirmed)GitResult.Success("Push simulated") else GitResult.Failure(GitError.Validation("Push requires confirmation."))
    override suspend fun pull()=GitResult.Success("Pull ready")
    override suspend fun fetch()=GitResult.Success("Fetch ready")
    override suspend fun branch(name:String?)=GitResult.Success(listOf("main") + listOfNotNull(name))
    override suspend fun checkout(name:String)=GitResult.Success(Unit)
    override suspend fun merge(name:String)=GitResult.Success("Merge ready: $name")
    override suspend fun diff()=GitResult.Success("Demo diff")
    override suspend fun log(limit:Int)=GitResult.Success("HEAD demo-commit\nPrevious demo commit")
    override suspend fun remote()=GitResult.Success("origin\tconfigured")
}
