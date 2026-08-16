package com.sa.aidesktop.core.ai.tools

/**
 * Rule 15 sub-helper: computes a real line-level diff between two versions of file text using a
 * classic LCS (longest common subsequence) backtrack — no fake/simulated diff data, every line
 * emitted here genuinely came from the real "before" or "after" file content passed in by the
 * caller (see [com.sa.aidesktop.core.ai.tools.WriteFileTool]).
 *
 * Kept as its own single-purpose file/object rather than folded into WriteFileTool (Rule 2/15:
 * WriteFileTool's own job — read/write a file — stays untouched; this only formats a diff).
 */
object ChatDiffUtil {
    /** ' ' = unchanged (present in both), '+' = added (only in the new text), '-' = removed (only
     *  in the old text). */
    data class DiffLine(val kind: Char, val text: String)

    // Rule 10 correctness/safety: an O(n*m) DP table is not safe to run unbounded on a phone.
    // Above this cell budget (~630 lines x 630 lines) lineDiff() returns null and the caller must
    // fall back to a plain summary instead of hanging or partially computing a wrong diff.
    private const val MAX_CELLS = 400_000L

    /** Returns null when the input is too large to diff safely — caller falls back to a summary. */
    fun lineDiff(oldLines: List<String>, newLines: List<String>): List<DiffLine>? {
        val n = oldLines.size
        val m = newLines.size
        if (n.toLong() * m.toLong() > MAX_CELLS) return null
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (oldLines[i] == newLines[j]) dp[i + 1][j + 1] + 1
                else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val result = ArrayList<DiffLine>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                oldLines[i] == newLines[j] -> { result.add(DiffLine(' ', oldLines[i])); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { result.add(DiffLine('-', oldLines[i])); i++ }
                else -> { result.add(DiffLine('+', newLines[j])); j++ }
            }
        }
        while (i < n) { result.add(DiffLine('-', oldLines[i])); i++ }
        while (j < m) { result.add(DiffLine('+', newLines[j])); j++ }
        return result
    }

    /** Rule 13 cleaner-viewer: collapses long unchanged runs down to [contextLines] on each side
     *  of a change, with a "(N unchanged lines)" marker instead of dumping the whole file — matters
     *  most for a one-line fix inside a large file. Every emitted string is 2-char prefixed with
     *  its real kind ("  " unchanged, "+ " added, "- " removed) so the UI can parse it back out.
     *  Also caps total emitted lines so the payload (which is also echoed back into the AI's own
     *  context, Rule 20 minimal-necessary-payload) stays bounded. */
    fun collapseContext(diff: List<DiffLine>, contextLines: Int = 1, maxOutputLines: Int = 160): List<String> {
        val out = ArrayList<String>()
        var idx = 0
        while (idx < diff.size && out.size <= maxOutputLines) {
            val line = diff[idx]
            if (line.kind != ' ') {
                out.add("${line.kind} ${line.text}")
                idx++
                continue
            }
            var runEnd = idx
            while (runEnd < diff.size && diff[runEnd].kind == ' ') runEnd++
            val runLen = runEnd - idx
            if (runLen <= contextLines * 2) {
                for (k in idx until runEnd) out.add("  ${diff[k].text}")
            } else {
                for (k in idx until idx + contextLines) out.add("  ${diff[k].text}")
                out.add("  … (${runLen - contextLines * 2} unchanged lines) …")
                for (k in runEnd - contextLines until runEnd) out.add("  ${diff[k].text}")
            }
            idx = runEnd
        }
        // Same 2-char "kind + space" prefix as every other emitted line (here: neutral/context)
        // so the UI parser's fixed-offset split stays correct even for this marker line.
        return if (out.size > maxOutputLines) out.take(maxOutputLines) + "  … (diff truncated)" else out
    }
}
