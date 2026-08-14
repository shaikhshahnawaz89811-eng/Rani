package com.sa.aidesktop.core.workspace

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

sealed interface WorkspaceError { val message: String
data class Missing(override val message: String): WorkspaceError
data class InvalidZip(override val message: String): WorkspaceError
data class UnsafeEntry(override val message: String): WorkspaceError
data class ExtractionLimit(override val message: String): WorkspaceError
data class Access(override val message: String): WorkspaceError }
sealed interface WorkspaceResult<out T> { data class Success<T>(val value:T): WorkspaceResult<T>; data class Failure(val error:WorkspaceError): WorkspaceResult<Nothing> }

data class WorkspaceLimits(val maxEntries:Int=20_000, val maxUncompressedBytes:Long=512L*1024L*1024L, val maxSingleFileBytes:Long=64L*1024L*1024L)
data class WorkspaceInfo(val root:File,val sourceZip:File?,val extracted:Boolean)

data class ProjectDiscovery(
    val type:String,
    val buildSystem:String?,
    val entryPoints:List<String>,
    val sourceDirectories:List<String>,
    val testDirectories:List<String>,
    val dependencyFiles:List<String>,
    val configurationFiles:List<String>,
    val importantFiles:List<String>
)

class ProjectWorkspaceManager(private val base: File, private val limits: WorkspaceLimits = WorkspaceLimits()) {
    fun createEmpty(name:String): WorkspaceResult<WorkspaceInfo> {
        if (!safeName(name)) return WorkspaceResult.Failure(WorkspaceError.Access("Invalid workspace name"))
        val root=File(base,name).canonicalFile
        return try {
            if (root.exists()) return WorkspaceResult.Failure(WorkspaceError.Access("Workspace already exists: ${root.path}"))
            if (!root.mkdirs() && !root.isDirectory) return WorkspaceResult.Failure(WorkspaceError.Access("Workspace creation failed: ${root.path}"))
            WorkspaceResult.Success(WorkspaceInfo(root,null,false))
        } catch(e:Exception){ WorkspaceResult.Failure(WorkspaceError.Access(e.message ?: "Workspace creation failed")) }
    }

    fun extractZip(zip:File, name:String = "task-${System.currentTimeMillis()}"): WorkspaceResult<WorkspaceInfo> {
        if (!zip.isFile) return WorkspaceResult.Failure(WorkspaceError.Missing("ZIP does not exist: ${zip.path}"))
        if (!zip.canRead()) return WorkspaceResult.Failure(WorkspaceError.Access("ZIP is not readable"))
        val root=File(base,name).canonicalFile
        if (root.exists()) return WorkspaceResult.Failure(WorkspaceError.Access("Workspace already exists: ${root.path}"))
        if (!root.mkdirs() && !root.isDirectory) return WorkspaceResult.Failure(WorkspaceError.Access("Workspace creation failed: ${root.path}"))
        var entries=0; var bytes=0L
        val seen=HashSet<String>()
        return try {
            ZipFile(zip).use { z ->
                val all=z.entries()
                while(all.hasMoreElements()) {
                    val e=all.nextElement(); entries++
                    if(!seen.add(e.name)) throw UnsafeException("Duplicate ZIP entry: ${e.name}")
                    if(entries>limits.maxEntries) throw LimitException("ZIP entry limit exceeded")
                    val target=File(root,e.name).canonicalFile
                    if(target.path!=root.path && !target.path.startsWith(root.path+File.separator)) throw UnsafeException("Unsafe ZIP entry: ${e.name}")
                    if(e.isDirectory){ target.mkdirs(); continue }
                    if(e.size>limits.maxSingleFileBytes) throw LimitException("ZIP file too large: ${e.name}")
                    val parent=target.parentFile ?: throw UnsafeException("Invalid ZIP path: ${e.name}")
                    parent.mkdirs()
                    z.getInputStream(e).use { input -> target.outputStream().use { output ->
                        val buffer=ByteArray(8192); var n:Int
                        while(input.read(buffer).also { n=it }!=-1){ bytes += n; if(bytes>limits.maxUncompressedBytes) throw LimitException("ZIP extraction size limit exceeded"); output.write(buffer,0,n) }
                    }}
                }
            }
            WorkspaceResult.Success(WorkspaceInfo(root,zip.canonicalFile,true))
        } catch(e:UnsafeException){ root.deleteRecursively(); WorkspaceResult.Failure(WorkspaceError.UnsafeEntry(e.message ?: "Unsafe ZIP entry")) }
          catch(e:LimitException){ root.deleteRecursively(); WorkspaceResult.Failure(WorkspaceError.ExtractionLimit(e.message ?: "ZIP extraction limit exceeded")) }
          catch(e:Exception){ root.deleteRecursively(); WorkspaceResult.Failure(WorkspaceError.InvalidZip(e.message ?: "Could not extract ZIP")) }
    }

    fun discover(root:File): WorkspaceResult<ProjectDiscovery> {
        if(!root.isDirectory) return WorkspaceResult.Failure(WorkspaceError.Missing("Workspace root does not exist"))
        val files=root.walkTopDown().filter{it.isFile}.map{root.toPath().relativize(it.toPath()).toString().replace(File.separatorChar,'/')}.take(3000).toList()
        val type=when {
            files.any{it.endsWith("build.gradle")||it.endsWith("build.gradle.kts")||it=="settings.gradle"||it=="settings.gradle.kts"} -> "Gradle/Android-or-JVM"
            files.any{it=="package.json"} -> if(files.any{it.endsWith(".tsx")||it.endsWith(".jsx")}) "JavaScript/React" else "Node.js"
            files.any{it=="pyproject.toml"||it=="setup.py"||it=="requirements.txt"||it.endsWith(".py")} -> "Python"
            files.any{it.endsWith(".html") && files.any{p->p.endsWith(".js")||p.endsWith(".ts")}} -> "Web"
            files.any{it.endsWith(".kt")} -> "Kotlin"
            files.any{it.endsWith(".java")} -> "Java"
            else -> "Unknown"
        }
        val build=when { files.any{it.endsWith("build.gradle")||it.endsWith("build.gradle.kts")} -> "Gradle"; files.contains("package.json") -> "npm/package scripts"; files.contains("pyproject.toml") -> "Python project"; files.contains("setup.py") -> "Python setup.py"; else -> null }
        val sources=files.filter{it.startsWith("src/")||it.startsWith("app/src/")||it.startsWith("lib/")||it.startsWith("source/")}.map{it.substringBeforeLast('/', "")}.distinct().take(30)
        val tests=files.filter{it.contains("test",true)||it.contains("spec",true)}.map{it.substringBeforeLast('/', "")}.distinct().take(30)
        val deps=files.filter{it.substringAfterLast('/') in setOf("package.json","requirements.txt","pyproject.toml","pom.xml","build.gradle","build.gradle.kts","gradle.properties")}
        val configs=files.filter{it.substringAfterLast('/') in setOf("AndroidManifest.xml","settings.gradle","settings.gradle.kts","settings.json","tsconfig.json","vite.config.js","vite.config.ts","gradle.properties")}
        val entries=files.filter{p-> listOf("MainActivity.kt","main.py","index.js","index.ts","index.tsx","App.kt","App.tsx","Main.java").contains(p.substringAfterLast('/'))}.take(20)
        return WorkspaceResult.Success(ProjectDiscovery(type,build,entries,sources,tests,deps,configs,files.filter{it.substringAfterLast('/') in setOf("README.md","README","LICENSE")}.take(20)))
    }

    private fun safeName(s:String)=s.isNotBlank() && s.length<80 && s.none{it=='/'||it=='\\'} && s!="." && s!=".."
    private class UnsafeException(message:String):IOException(message)
    private class LimitException(message:String):IOException(message)
}
