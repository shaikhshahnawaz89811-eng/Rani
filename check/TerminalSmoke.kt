import com.sa.aidesktop.core.terminal.*
import java.nio.file.Files
fun main(){
 val root=Files.createTempDirectory("term-smoke-").toFile(); root.resolve("src").mkdirs()
 try {
  val t=EmbeddedTerminalService(root)
  check(t.execute("pwd").exitCode==0)
  check(t.execute("cd src").exitCode==0)
  check(t.execute("echo hello").output=="hello")
  check(t.execute("echo a && echo b").exitCode==126)
  check(t.execute("rm -rf /").exitCode==126)
  println("terminal smoke PASS")
 } finally { root.deleteRecursively() }
}
