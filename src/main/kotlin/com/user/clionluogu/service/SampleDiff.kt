package com.user.clionluogu.service

import java.io.File

/**
 * 样例输出的规范化与差异定位（纯函数，不依赖任何 IDE 类型，便于单独验证）。
 *
 * 比较口径按 OJ 惯例，只做两件事：**每行去掉行尾空白**（含 `\r`，挡 Windows 上 CRLF 造成的全红）
 * 与**砍掉文件末尾的连续空行**。不做行首缩进折叠、不做 token 化、不忽略中间空行——
 * 那些洛谷不忽略，跟着忽略就会把真错判成对。
 */
object SampleDiff {

    /** 单组样例最多读多少字节（本地死循环打印的防护，与进程侧的有界捕获同一量级）。 */
    const val MAX_READ_BYTES = 1_048_576

    /** 首个差异上下各展示多少行。 */
    const val CONTEXT_RADIUS = 20

    /** 单侧上下文最多展示多少行。 */
    const val MAX_DISPLAY_LINES = 200

    /** 单行超此长度就行内截断（一行 1MB 的浮点串会把 `lineWrap=false` 的面板撑爆）。 */
    const val MAX_LINE_DISPLAY = 200

    /** stderr 只截这么多字节。 */
    const val MAX_STDERR_BYTES = 2_048

    /** 首个差异。行号从 1 起；某一侧为 null 表示「该侧没有这一行」。 */
    data class Issue(
        val line: Int,
        val expectedLine: String?,
        val actualLine: String?,
        val expectedExtraLines: Int,
        val actualExtraLines: Int,
    )

    /** 规范化为「可比较的行列表」。 */
    fun normalize(text: String): List<String> {
        val lines = text.split('\n').map { it.trimEnd() }
        var end = lines.size
        while (end > 0 && lines[end - 1].isEmpty()) end--
        return lines.subList(0, end).toList()
    }

    /**
     * 读取文本；返回 null 表示**超过 [MAX_READ_BYTES]**（不读进内存）。
     * 统一按 UTF-8 解码（`.out` 就是插件用 UTF-8 写的），不用系统默认字符集——
     * Windows 中文环境默认是 GBK，跟着系统走会跨机器不确定。
     */
    fun readCapped(f: File, limitBytes: Int = MAX_READ_BYTES): String? {
        if (!f.isFile) return ""
        if (runCatching { f.length() }.getOrDefault(0L) > limitBytes) return null
        return runCatching { String(f.readBytes(), Charsets.UTF_8) }.getOrDefault("")
    }

    /** 首个差异；完全一致返回 null。 */
    fun firstDifference(expected: List<String>, actual: List<String>): Issue? {
        val common = minOf(expected.size, actual.size)
        for (i in 0 until common) {
            if (expected[i] != actual[i]) {
                return Issue(
                    line = i + 1,
                    expectedLine = expected[i],
                    actualLine = actual[i],
                    expectedExtraLines = expected.size - common,
                    actualExtraLines = actual.size - common,
                )
            }
        }
        return when {
            expected.size > actual.size -> Issue(common + 1, expected[common], null, expected.size - common, 0)
            actual.size > expected.size -> Issue(common + 1, null, actual[common], 0, actual.size - common)
            else -> null
        }
    }

    /** 列表行里那句短文案。 */
    fun summarize(issue: Issue): String = buildString {
        append("第 ").append(issue.line).append(" 行")
        when {
            issue.actualLine == null -> append(" 缺失（实际少 ").append(issue.expectedExtraLines.coerceAtLeast(1)).append(" 行）")
            issue.expectedLine == null -> append(" 多余（实际多 ").append(issue.actualExtraLines.coerceAtLeast(1)).append(" 行）")
            else -> append(" 不同")
        }
    }

    /**
     * 某一侧在 [at]（1 起）附近的上下文块：带行号、差异行标 `✗`。
     * 该侧没有第 [at] 行时明确写出来，而不是给个空块让人以为"没差异"。
     */
    fun contextBlock(title: String, lines: List<String>, at: Int, totalHint: String? = null): String {
        val sb = StringBuilder()
        sb.append("—— ").append(title)
        totalHint?.let { sb.append("（").append(it).append("）") }
        sb.append(" ——\n")
        if (lines.isEmpty()) {
            sb.append("  （该侧无输出）\n")
            return sb.toString()
        }
        if (at > lines.size) {
            sb.append("  （该侧只到第 ").append(lines.size).append(" 行，没有第 ").append(at).append(" 行）\n")
            return sb.toString()
        }
        val from = maxOf(1, at - CONTEXT_RADIUS)
        val to = minOf(lines.size, at + CONTEXT_RADIUS, from + MAX_DISPLAY_LINES - 1)
        for (i in from..to) {
            val marker = if (i == at) "✗" else " "
            // i 是 1 起的行号，取内容要 -1：写成 lines[i] 会把每行内容错位一行，
            // 且差异落在最后一行时直接越界
            sb.append(marker).append(' ').append(i.toString().padStart(4)).append(": ").append(clip(lines[i - 1])).append('\n')
        }
        if (to < lines.size) sb.append("     … 之后还有 ").append(lines.size - to).append(" 行\n")
        return sb.toString()
    }

    /** stderr 之类的一次性截断展示。 */
    fun clipBytes(text: String, limit: Int = MAX_STDERR_BYTES): String {
        val trimmed = text.trim()
        if (trimmed.length <= limit) return trimmed
        return trimmed.take(limit) + "\n… 已截断 " + (trimmed.length - limit) + " 字符"
    }

    private fun clip(line: String): String =
        if (line.length <= MAX_LINE_DISPLAY) line else line.take(MAX_LINE_DISPLAY) + "…"
}
