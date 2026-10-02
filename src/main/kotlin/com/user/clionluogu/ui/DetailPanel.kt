package com.user.clionluogu.ui

import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.user.clionluogu.service.AcCleanupService
import com.user.clionluogu.service.ProblemEntry
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

/**
 * 详情区的一节：可选标题、若干「键 值」行、一个等宽文本块。
 *
 * 纯数据 —— 构造它的函数都是纯的，所以能在没起 IDE 的探针里直接断言内容。
 */
data class DetailSection(
    val title: String?,
    val rows: List<Pair<String, String>>,
    val block: String?,
)

/**
 * 结构化详情区，替掉「一个大字符串塞进 JTextArea」。
 *
 * 为什么要换：原来评测页把元信息、`—— 编译错误 ——`、原始诊断、`—— 提交的代码 ——`、整份源码
 * 拼成一段文本丢给一个开了自动折行的 `JTextArea`，于是诊断被折成 `In funct ion` 那种断词，
 * 分节全靠手搓 ASCII，浅色主题下也没有任何层级。
 *
 * 这里只做三件事：分节用平台的 [TitledSeparator]；键值行用 [SimpleColoredComponent]
 * （键灰小、值正常，不靠全角空格对齐）；正文块用**独立**的等宽只读文本域，
 * 关掉自动折行、给横向滚动，所以长行是横向滚而不是被折断。
 */
class DetailPanel : JPanel(GridBagLayout()) {

    private val borderColor: Color = JBColor.namedColor("Component.borderColor", Gray._120)
    private var blockCount = 0

    init {
        border = JBUI.Borders.empty(6, 8)
        isOpaque = false
    }

    /**
     * 重画。[sections] 为空时留白（各页的空态由调用方的状态行/emptyText 负责，
     * 不在这里塞提示文案，免得提示跟着数据一起被复制走）。
     */
    fun render(sections: List<DetailSection>) {
        removeAll()
        blockCount = 0
        var y = 0
        fun next() = GridBagConstraints().apply {
            gridx = 0
            gridy = y++
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTHWEST
        }
        for (section in sections) {
            section.title?.takeIf { it.isNotBlank() }?.let {
                add(TitledSeparator(it), next())
            }
            for ((key, value) in section.rows) {
                if (value.isBlank()) continue
                add(rowComponent(key, value), next())
            }
            section.block?.takeIf { it.isNotBlank() }?.let { add(blockComponent(it), next()) }
        }
        // 尾巴撑满，内容才始终顶对齐而不是被均分到整个面板高度
        add(
            JPanel().apply { isOpaque = false; preferredSize = java.awt.Dimension(1, 1) },
            GridBagConstraints().apply {
                gridx = 0
                gridy = y
                weightx = 1.0
                weighty = 1.0
                fill = GridBagConstraints.BOTH
            },
        )
        revalidate()
        repaint()
    }

    private fun rowComponent(key: String, value: String): SimpleColoredComponent {
        val line = SimpleColoredComponent()
        line.isOpaque = false
        line.append(key, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        line.appendTextPadding(JBUI.scale(6))
        line.append(value, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        return line
    }

    private fun blockComponent(text: String): JPanel {
        val area = JBTextArea(text).apply {
            isEditable = false
            // 不折行：诊断与代码被折断词比横向滚动难读得多（他截图里就是 In funct ion）
            lineWrap = false
            font = monoFont()
            background = UIUtil.getEditorPaneBackground()
            border = JBUI.Borders.empty(4, 6)
        }
        val rows = text.count { it == '\n' } + 1
        val lineHeight = area.getFontMetrics(area.font).height
        val height = (rows * lineHeight + JBUI.scale(12))
            .coerceIn(JBUI.scale(48), JBUI.scale(300 + blockCount * 120))
        blockCount++
        val scroll = JBScrollPane(area, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED)
        scroll.border = JBUI.Borders.customLine(borderColor, 1, 1, 1, 1)
        scroll.preferredSize = java.awt.Dimension(10, height)
        scroll.background = UIUtil.getEditorPaneBackground()
        val wrap = JPanel(BorderLayout())
        wrap.isOpaque = false
        wrap.add(scroll, BorderLayout.CENTER)
        return wrap
    }

    companion object {

        /** 等宽字体：跟 IDE 的 UI 字号走，别写死 12。 */
        @JvmStatic
        fun monoFont(): Font = JBFont.create(Font(Font.MONOSPACED, Font.PLAIN, JBFont.label().size))

        /**
         * 把「拼好的纯文本」转成节：
         * - 每个 `—— 标题 ——` 行开一节（`SampleDiff.contextBlock` 里的「期望 / 实际」就是这样分开的）；
         * - 第一个标题之前的部分：开头连续的 `键：值` 行变成键值行，剩下的作为正文块。
         *
         * 这样对拍页那几个字符串生产者（`introText` / `compileDetail` / `detailOf`，
         * 还有被探针按字节断言的 `SampleDiff.contextBlock`）**一个字都不用改**，只是换个方式呈现。
         */
        @JvmStatic
        fun sectionsFromText(raw: String?): List<DetailSection> {
            if (raw.isNullOrBlank()) return emptyList()
            val out = ArrayList<DetailSection>()
            val head = ArrayList<String>()
            val currentTitle = ArrayList<String>()   // 至多一条：当前块的标题
            val body = ArrayList<String>()

            fun flushBody() {
                val text = body.joinToString("\n").trim()
                if (text.isNotEmpty()) {
                    out.add(DetailSection(currentTitle.firstOrNull(), emptyList(), text))
                }
                body.clear()
                currentTitle.clear()
            }

            fun flushHead() {
                if (head.isEmpty()) return
                val (rows, rest) = splitLeadingRows(head)
                if (rows.isNotEmpty()) out.add(DetailSection(null, rows, null))
                if (rest.isNotBlank()) {
                    // 第一个标题之前的残留：没有标题就直说，别去蹭后面那节的标题
                    val (title, text) = BlockText.splitHeader(rest)
                    out.add(DetailSection(title, emptyList(), text.trim()))
                }
                head.clear()
            }

            for (line in raw.split('\n')) {
                val title = if (BlockText.isHeaderLine(line)) {
                    BlockText.splitHeader(line).first
                } else {
                    null
                }
                if (title != null) {
                    flushHead()
                    flushBody()
                    currentTitle.add(title)
                } else if (currentTitle.isEmpty()) {
                    head.add(line)
                } else {
                    body.add(line)
                }
            }
            flushHead()
            flushBody()
            return out.filter { it.title != null || it.rows.isNotEmpty() || !it.block.isNullOrBlank() }
        }

        /** 从一堆行的开头取出连续的 `键：值` 行，返回（这些行，剩下的原文）。 */
        private fun splitLeadingRows(lines: List<String>): Pair<List<Pair<String, String>>, String> {
            val rows = ArrayList<Pair<String, String>>()
            var i = 0
            while (i < lines.size) {
                val pair = keyValue(lines[i])
                if (pair != null) {
                    rows.add(pair)
                    i++
                    continue
                }
                // 空行后面还跟着键值行就继续（原文里节与节之间会空一行）
                if (lines[i].isBlank() && lines.getOrNull(i + 1).let { keyValue(it) != null }) {
                    i++
                    continue
                }
                break
            }
            return rows to lines.drop(i).joinToString("\n")
        }

        /**
         * 一行是不是 `键：值`。两道闸：键要短（长的是句子），值也不能像散文 ——
         * 「注意：这里只用命令行编译器，不复用你项目里的 CMake 配置」得留在正文块里，
         * 而「源文件：/Users/…/P1001.cpp」这种长路径确实是值，所以按中文标点判。
         */
        private fun keyValue(line: String?): Pair<String, String>? {
            val trimmed = line?.trim().orEmpty()
            if (trimmed.isEmpty() || trimmed.startsWith("——")) return null
            val cut = listOf('：', ':').let { seps ->
                seps.map { trimmed.indexOf(it) }.filter { it > 0 }.minOrNull()
            } ?: return null
            val key = trimmed.substring(0, cut).trim()
            val value = trimmed.substring(cut + 1).trim()
            if (key.isEmpty() || value.isEmpty()) return null
            if (key.length > 12) return null
            if (value.any { it in "，。；！？、" }) return null
            return key to value
        }

        /**
         * 评测页的节。参数全是原语，所以探针能直接构造断言（不依赖 `SubmissionEntry`）。
         */
        @JvmStatic
        fun evalSections(
            pid: String,
            rid: String,
            lang: String?,
            statusText: String,
            totalScore: Int?,
            timeMs: Long?,
            memoryKb: Long?,
            subtaskInfo: List<String>,
            compileError: String?,
            code: String?,
        ): List<DetailSection> {
            val head = DetailSection(
                null,
                listOf(
                    "题目" to pid,
                    "提交记录" to "#$rid",
                    "语言" to lang.orEmpty(),
                    "状态" to statusText,
                    "得分" to (totalScore?.let { "$it 分（各子任务合计）" } ?: ""),
                    "耗时" to (timeMs?.let { "$it ms" } ?: ""),
                    "内存" to (memoryKb?.let { "$it KB" } ?: ""),
                ).filter { it.second.isNotBlank() },
                null,
            )
            val out = ArrayList<DetailSection>()
            out.add(head)
            if (subtaskInfo.isNotEmpty()) {
                out.add(DetailSection("子任务", emptyList(), subtaskInfo.joinToString("\n")))
            }
            compileError?.takeIf { it.isNotBlank() }?.let {
                out.add(DetailSection("编译错误", emptyList(), it.trimEnd()))
            }
            code?.takeIf { it.isNotBlank() }?.let {
                out.add(DetailSection("提交的代码", emptyList(), it.trimEnd()))
            }
            return out
        }
        /**
         * 题目页的节：磁盘上有哪几类产物、最近一次评测是什么、删除会碰哪些名字。
         * 产物名直接取 [ProblemEntry]（与 `AcCleanupService.targetNames` 同一套口径），
         * 不再手搓 `· 名字` 那种列表。
         */
        @JvmStatic
        fun indexSections(
            entry: ProblemEntry,
            latestRid: String?,
            latestStatus: String?,
            latestScore: Int?,
            latestLang: String?,
            latestTimeMs: Long?,
            latestMemoryKb: Long?,
        ): List<DetailSection> {
            val files = DetailSection(
                "磁盘上的产物",
                listOf(
                    "源码" to (entry.cpp?.name ?: "（无）"),
                    "题面" to (entry.md?.name ?: "（无）"),
                    "样例" to (entry.samples?.let { "${it.name}/（${entry.sampleCount} 个文件）" } ?: "（无）"),
                    "反例" to (entry.cases?.let { "${it.name}/（${entry.caseCount} 个文件）" } ?: "（无）"),
                ),
                null,
            )
            val out = ArrayList<DetailSection>()
            out.add(files)
            out.add(
                if (latestRid == null) {
                    DetailSection("最近一次提交", listOf("提交记录" to "这个工程里没有提交过"), null)
                } else {
                    DetailSection(
                        "最近一次提交",
                        listOf(
                            "记录" to "#$latestRid",
                            "状态" to latestStatus.orEmpty(),
                            "得分" to (latestScore?.let { "$it 分（各子任务合计）" } ?: ""),
                            "语言" to latestLang.orEmpty(),
                            "耗时" to (latestTimeMs?.let { "$it ms" } ?: ""),
                            "内存" to (latestMemoryKb?.let { "$it KB" } ?: ""),
                        ),
                        null,
                    )
                },
            )
            out.add(
                DetailSection(
                    "删除范围",
                    listOf(
                        "只删这四个名字" to AcCleanupService.targetNames(entry.pid).joinToString("  "),
                        "提交记录" to "不删（评测历史还在「评测」页）",
                    ),
                    null,
                ),
            )
            return out
        }
    }
}
