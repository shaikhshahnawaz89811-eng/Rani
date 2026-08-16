package com.sa.aidesktop.core.terminal

import java.io.File

/**
 * Rule 15 sub-helper (single job): builds the real argv needed to execute a downloaded ELF
 * binary living in this app's private storage, despite Android 10+'s W^X restriction that
 * refuses to execve() a file directly out of an app-private data directory.
 *
 * This is NOT a workaround invented for this app — it is the exact "system linker execution"
 * technique Termux's own `termux-exec` package uses in production: instead of running the target
 * binary directly, ask the OS's own dynamic linker (`/system/bin/linker64` on 64-bit devices,
 * `/system/bin/linker` on 32-bit) to load and run it. The linker lives under `/system`, which is
 * outside app-private storage and therefore exempt from W^X, so the OS allows it — and because
 * `/system/bin/linker64` is genuinely the same ELF interpreter every dynamically-linked binary
 * already points at (its PT_INTERP entry), it runs the target for real, not a simulation.
 *
 * Known, honest limitation (Rule 10 — stated up front, not hidden): statically-linked binaries
 * do not go through a PT_INTERP / dynamic linker at all, so this technique cannot run them. Real
 * Termux bootstraps are built dynamically-linked against Android's bionic libc specifically so
 * this works; that is true here too as long as the bootstrap used is Termux's own official one.
 */
object SystemLinkerExec {
    private val linker64 = File("/system/bin/linker64")
    private val linker32 = File("/system/bin/linker")

    fun isSupported(): Boolean = linker64.exists() || linker32.exists()

    /** Real argv: [system-linker, target-binary-path, ...target's own args] — the target binary
     *  is never exec'd directly. */
    fun buildCommand(binary: File, args: List<String>): List<String> {
        val linker = if (linker64.exists()) linker64 else linker32
        return listOf(linker.absolutePath, binary.absolutePath) + args
    }
}
