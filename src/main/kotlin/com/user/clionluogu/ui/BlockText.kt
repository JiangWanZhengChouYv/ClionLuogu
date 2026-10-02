package com.user.clionluogu.ui

/**
 * 把「一整块拼出来的纯文本」拆成「标题 + 正文」。
 *
 * 历史原因：`SampleDiff.contextBlock`、对拍页的 `compileDetail` 这些字符串里都塞了一行
 * `—— 编译器输出 ——` 这样的 ASCII 分隔线（那还是把它们画在 `JTextArea` 里时唯一的分节手段）。
 * 现在分节交给 [DetailPanel] 的 `TitledSeparator`，所以渲染前先把这一行剥出来当标题。
 *
 * 这些字符串本身**不改**（离线探针在按字节断言它们），只在这里换个呈现方式。
 */
object BlockText {

    private val HEADER = Regex("^\\s*——\\s*(.+?)\\s*——\\s*$")

    /** 这一行是不是 `—— 标题 ——` 形式的分节头。 */
    @JvmStatic
    fun isHeaderLine(line: String): Boolean = HEADER.matches(line)

    /**
     * 拆出分节头。只有**第一个非空行**是头才算数（正文中间出现的 `—— x ——` 是内容，不是标题）。
     *
     * @return 标题（没有则 null）与剩下的正文（已去掉开头多余空行；没有标题时原样返回）
     */
    @JvmStatic
    fun splitHeader(raw: String?): Pair<String?, String> {
        if (raw.isNullOrEmpty()) return null to raw.orEmpty()
        val lines = raw.split('\n')
        val first = lines.indexOfFirst { it.isNotBlank() }
        if (first < 0) return null to raw
        val title = HEADER.matchEntire(lines[first])?.groupValues?.get(1)?.trim() ?: return null to raw
        val rest = lines.drop(first + 1).dropWhile { it.isBlank() }.joinToString("\n")
        return title to rest
    }
}
