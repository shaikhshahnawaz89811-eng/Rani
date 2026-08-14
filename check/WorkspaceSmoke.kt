import com.sa.aidesktop.core.workspace.*
import java.io.File
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

fun main() {
  val base = createTempDir(prefix="ws-smoke-")
  try {
    val m = ProjectWorkspaceManager(base)
    val a = m.createEmpty("a")
    check(a is WorkspaceResult.Success)
    check(m.createEmpty("a") is WorkspaceResult.Failure)
    val zip = File(base,"p.zip")
    ZipOutputStream(zip.outputStream()).use { z ->
      z.putNextEntry(ZipEntry("src/main.py")); z.write("print(1)".toByteArray()); z.closeEntry()
    }
    val x = m.extractZip(zip,"x")
    check(x is WorkspaceResult.Success)
    val d = m.discover((x as WorkspaceResult.Success).value.root)
    check(d is WorkspaceResult.Success)
    check(d.value.type == "Python")
    println("workspace smoke PASS")
  } finally { base.deleteRecursively() }
}
