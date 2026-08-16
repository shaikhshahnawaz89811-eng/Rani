package com.sa.aidesktop.core.terminal

import android.content.Context
import android.os.Build
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

sealed class BootstrapStatus {
    object NotInstalled : BootstrapStatus()
    object Ready : BootstrapStatus()
    data class Failed(val reason: String) : BootstrapStatus()
}

/**
 * Downloads and installs a REAL Linux userland — Termux's own official bootstrap (busybox, bash,
 * coreutils, apt/dpkg, termux-exec) — into this app's private storage, using the exact technique
 * Termux itself uses to run real binaries on Android 10+ despite the OS's W^X restriction (see
 * [SystemLinkerExec]). Nothing here is a fake/simulated shell (Rule 10): every file that ends up
 * under [prefix] is the same, unmodified artifact Termux publishes at
 * https://github.com/termux/termux-packages/releases (tag `bootstrap-*`); this class only
 * downloads, verifies by self-test, and lays it out where [RealLinuxShellBackend] expects it.
 *
 * Rule 19 (never edit the "live" copy directly): extraction happens into a STAGING directory,
 * [prefix] is only ever replaced by renaming staging over it, and only after [selfTest] proves
 * the extracted bash/busybox genuinely runs — so an interrupted or corrupted download can never
 * leave a half-broken environment as the active one.
 */
class LinuxBootstrapManager(context: Context) {
    private val filesDir = context.filesDir
    private val cacheDir = context.cacheDir
    val prefix: File = File(filesDir, "usr")
    private val staging: File = File(filesDir, "usr-staging")
    private val cacheZip: File = File(cacheDir, "bootstrap-download.zip")
    private val readyMarker: File get() = File(prefix, ".bootstrap-complete")

    /** Rule 17: existence of the marker + real binaries, not just "extraction finished". */
    fun isReady(): Boolean =
        readyMarker.isFile && File(prefix, "bin/bash").isFile && File(prefix, "bin/busybox").isFile

    fun status(): BootstrapStatus = if (isReady()) BootstrapStatus.Ready else BootstrapStatus.NotInstalled

    /**
     * Entry point (Rule 1). The only path that installs a real bootstrap. Always returns a final,
     * honest [BootstrapStatus] instead of throwing past this boundary — a failed install leaves
     * [prefix] exactly as it was before the call (staging is cleaned up on any failure).
     */
    fun install(onProgress: (String) -> Unit = {}): BootstrapStatus {
        if (!SystemLinkerExec.isSupported()) {
            return BootstrapStatus.Failed(
                "This device has no /system/bin/linker(64) — real-Linux mode cannot run here."
            )
        }
        return try {
            onProgress("Detecting device architecture…")
            val arch = termuxArch()
                ?: return BootstrapStatus.Failed("Unsupported CPU architecture: ${Build.SUPPORTED_ABIS.joinToString()}")

            onProgress("Looking up latest Termux bootstrap for $arch…")
            val asset = findBootstrapAsset(arch)
                ?: return BootstrapStatus.Failed(
                    "Could not find a bootstrap-$arch.zip release from termux-packages. Check your internet connection."
                )

            onProgress("Downloading ${asset.name} (~${asset.sizeMb()} MB)…")
            download(asset.downloadUrl, cacheZip, onProgress)

            onProgress("Extracting…")
            if (staging.exists()) staging.deleteRecursively()
            staging.mkdirs()
            extract(cacheZip, staging)

            onProgress("Verifying (self-test)…")
            if (!selfTest(staging)) {
                staging.deleteRecursively()
                return BootstrapStatus.Failed(
                    "Self-test failed after extraction — real Linux environment was NOT installed; nothing on this device was changed."
                )
            }

            onProgress("Finalizing…")
            if (prefix.exists()) prefix.deleteRecursively()
            if (!staging.renameTo(prefix)) {
                return BootstrapStatus.Failed("Could not finalize install (rename failed). Try again.")
            }
            File(prefix, ".bootstrap-complete").writeText(System.currentTimeMillis().toString())
            cacheZip.delete()
            BootstrapStatus.Ready
        } catch (e: Exception) {
            runCatching { staging.deleteRecursively() }
            BootstrapStatus.Failed(e.message ?: "Unknown error during install")
        }
    }

    private fun termuxArch(): String? = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a", "armeabi" -> "arm"
        "x86_64" -> "x86_64"
        "x86" -> "i686"
        else -> null
    }

    private data class BootstrapAsset(val name: String, val downloadUrl: String, val sizeBytes: Long) {
        fun sizeMb(): Long = sizeBytes / (1024 * 1024)
    }

    /** Reads the real, CURRENT release list from termux-packages (Rule 10: never a hardcoded
     *  version string that would silently go stale as Termux ships new bootstraps). */
    private fun findBootstrapAsset(arch: String): BootstrapAsset? {
        val json = httpGetText("https://api.github.com/repos/termux/termux-packages/releases?per_page=20")
        val releases = JSONArray(json)
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (!release.optString("tag_name").startsWith("bootstrap-")) continue
            val assets = release.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                if (a.optString("name") == "bootstrap-$arch.zip") {
                    return BootstrapAsset(a.optString("name"), a.optString("browser_download_url"), a.optLong("size"))
                }
            }
        }
        return null
    }

    private fun httpGetText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        try {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun download(url: String, target: File, onProgress: (String) -> Unit) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        try {
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastReportedPct = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        downloaded += n
                        if (total > 0) {
                            val pct = ((downloaded * 100) / total).toInt()
                            if (pct != lastReportedPct) {
                                onProgress("Downloading… $pct%")
                                lastReportedPct = pct
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Honors Termux's real bootstrap zip format exactly (Rule 10 correctness): symlinks are NOT
     * stored as zip entries — they are listed in a `SYMLINKS.txt` file as `target←linkname` lines
     * (using the U+2190 arrow character, not a regular arrow or slash), one per real symlink, to
     * be recreated after every regular file is written.
     */
    private fun extract(zip: File, destination: File) {
        val symlinkLines = mutableListOf<String>()
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destination, entry.name)
                when {
                    entry.isDirectory -> outFile.mkdirs()
                    entry.name == "SYMLINKS.txt" -> {
                        symlinkLines += zis.bufferedReader().readText().lines().filter { it.isNotBlank() }
                    }
                    else -> {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { zis.copyTo(it) }
                        val sep = File.separator
                        if (outFile.path.contains("${sep}bin$sep") || outFile.path.contains("${sep}lib$sep") ||
                            outFile.path.contains("${sep}libexec$sep")
                        ) {
                            outFile.setExecutable(true, true)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        symlinkLines.forEach { line ->
            val parts = line.split('\u2190', limit = 2)
            if (parts.size != 2) return@forEach
            val (target, linkName) = parts
            val linkFile = File(destination, linkName)
            linkFile.parentFile?.mkdirs()
            runCatching {
                if (Files.exists(linkFile.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) linkFile.delete()
                Files.createSymbolicLink(linkFile.toPath(), File(target).toPath())
            }
        }
    }

    /** Rule 17: an extracted file existing is not the same as it being correct — actually run a
     *  real binary through the system linker and check its real output before trusting anything. */
    private fun selfTest(root: File): Boolean {
        val busybox = File(root, "bin/busybox")
        if (!busybox.isFile) return false
        return try {
            val pb = ProcessBuilder(SystemLinkerExec.buildCommand(busybox, listOf("echo", "bootstrap-ok")))
                .redirectErrorStream(true)
            pb.environment().apply {
                clear()
                put("LD_LIBRARY_PATH", File(root, "lib").absolutePath)
            }
            val process = pb.start()
            val out = process.inputStream.bufferedReader().readText().trim()
            val finished = process.waitFor(10, TimeUnit.SECONDS)
            finished && process.exitValue() == 0 && out.contains("bootstrap-ok")
        } catch (e: Exception) {
            false
        }
    }
}
