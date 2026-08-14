import com.sa.aidesktop.core.git.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
fun main() = runBlocking {
 val root=Files.createTempDirectory("git-smoke-").toFile()
 try {
   val g=CommandGitService(root)
   check(g.init() is GitResult.Success)
   check((g.status() as GitResult.Success).value.branch.isNotBlank())
   root.resolve("a.txt").writeText("hello")
   check(g.add(listOf("a.txt")) is GitResult.Success)
   val diff=(g.diff(true) as GitResult.Success).value
   check(diff.contains("hello"))
   println("git smoke PASS")
 } finally { root.deleteRecursively() }
}
