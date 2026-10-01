package com.user.clionluogu.ui

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.user.clionluogu.api.LuoguProblemDto
import com.user.clionluogu.api.SolutionSummary
import com.user.clionluogu.service.LuoguActions
import com.user.clionluogu.service.ProblemMdGenService
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

private val SCRIPT_REGEX = Regex(
    "<script[^>]*>.*?</script>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

private val STYLE_REGEX = Regex(
    "<style[^>]*>.*?</style>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

private val A_HREF_REGEX = Regex(
    "(<a\\b[^>]*?)\\s+href\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)",
    RegexOption.IGNORE_CASE,
)

private val IFRAME_REGEX = Regex(
    "<iframe[^>]*>.*?</iframe>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

private val OBJECT_REGEX = Regex(
    "<object[^>]*>.*?</object>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

private val EMBED_REGEX = Regex("<embed[^>]*/?>", RegexOption.IGNORE_CASE)

private val LINK_REGEX = Regex("<link[^>]*/?>", RegexOption.IGNORE_CASE)

/**
 * 内联事件属性（`onclick="…"` 等）。题解正文是任意用户提交的 HTML，
 * 光靠 [SCRIPT_REGEX] 剥掉 `<script>` 挡不住 `on*=`，故一并清除。
 */
private val EVENT_ATTR_REGEX = Regex(
    """\s+on[a-z]+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)""",
    RegexOption.IGNORE_CASE,
)

private val MD_IMG_REGEX = Regex("!\\[([^\\]]*)\\]\\(\\s*([^)\\s]+)\\s*\\)")

private val DISPLAY_MATH_REGEX = Regex("\\$\\$(.*?)\\$\\$", RegexOption.DOT_MATCHES_ALL)

private val INLINE_MATH_REGEX = Regex("\\$([^$\\n]+?)\\$")

/**
 * 预览页：「题面 / 题解」双模式，两种正文共用同一套主题样式与内置 MathJax 渲染管线。
 *
 * 题面数据由外部注入（搜索页双击、拉取页均可）；题解模式按需向洛谷**分页**拉取，
 * 已加载的题解以**可见列表**平铺在正文上方（侧边栏里下拉框看不出还有几条），
 * 每次只把选中的**那一篇**正文交给 JCEF，避免整页题解同时 typeset 卡顿。
 */
class PreviewPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val statusLabel = JBLabel("在搜索页双击题目以预览")
    private val problemButton = JButton("题面")
    private val solutionButton = JButton("题解")
    private val moreButton = JButton("加载更多")
    private val solutionModel = DefaultComboBoxModel<SolutionSummary>()
    private val browser: JBCefBrowser? = createBrowser()
    private val fallbackArea = JEditorPane().apply {
        isEditable = false
        contentType = "text/html"
        border = JBUI.Borders.empty(6)
    }

    /** 题面模式的渲染依据；为空时「题面」按钮只提示不渲染。 */
    private var currentProblem: LuoguProblemDto? = null

    /** 最近一次操作过的题号，题解模式据此决定向哪道题拉题解。 */
    private var currentPid: String? = null

    /** [solutionModel] 里的题解属于哪道题，避免切换题号后渲染到上一篇题的题解。 */
    private var solutionsPid: String? = null

    private var showingSolutions = false
    private var page = 0
    private var hasMore = false
    private var loadingPage = false

    private val solutionList = JBList(solutionModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : ColoredListCellRenderer<SolutionSummary>() {
            override fun customizeCellRenderer(
                list: JList<out SolutionSummary>,
                value: SolutionSummary?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                icon = null
                if (value == null) return
                val votes = value.upvote?.let { " ▲$it" }.orEmpty()
                append("${index + 1}. ${value.title}")
                append(" — ${value.author ?: "佚名"}$votes", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        addListSelectionListener { event -> if (!event.valueIsAdjusting) renderCurrent() }
    }

    /** 题解列表：只在题解模式可见（BorderLayout 不给不可见组件留空间）。 */
    private val solutionListPanel = JBScrollPane(solutionList).apply {
        preferredSize = Dimension(10, LIST_HEIGHT)
        border = JBUI.Borders.empty()
        isVisible = false
    }

    init {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(problemButton)
            add(solutionButton)
            add(moreButton)
        }
        val north = JPanel(BorderLayout()).apply {
            add(statusLabel, BorderLayout.NORTH)
            add(toolbar, BorderLayout.SOUTH)
        }
        add(north, BorderLayout.NORTH)

        val center = JPanel(BorderLayout()).apply {
            add(solutionListPanel, BorderLayout.NORTH)
            val component = browser?.component
            if (component != null) {
                add(component, BorderLayout.CENTER)
            } else {
                add(JBScrollPane(fallbackArea), BorderLayout.CENTER)
            }
        }
        add(center, BorderLayout.CENTER)

        problemButton.addActionListener { showProblemMode() }
        solutionButton.addActionListener { currentPid?.let { showSolutionsFor(it) } }
        moreButton.addActionListener { loadSolutionPage(page + 1, append = true) }
        applyModeToToolbar()
    }

    private fun createBrowser(): JBCefBrowser? = try {
        if (PluginManagerCore.isPluginInstalled(PluginId.getId("com.intellij.modules.jcef")) &&
            JBCefApp.isSupported()
        ) JBCefBrowser() else null
    } catch (_: Throwable) {
        null
    }

    override fun dispose() {
        browser?.dispose()
    }

    fun showProblem(problem: LuoguProblemDto) {
        currentProblem = problem
        currentPid = problem.pid
        renderProblem(problem)
    }

    /** 切到题解模式：题号已有缓存的题解列表则直接复用，否则拉第 1 页。 */
    fun showSolutionsFor(pid: String) {
        currentPid = pid
        showingSolutions = true
        if (solutionsPid == pid && solutionModel.size > 0) {
            applyModeToToolbar()
            renderCurrent()
            return
        }
        loadSolutionPage(1, append = false)
    }

    private fun showProblemMode() {
        val problem = currentProblem
        if (problem == null) {
            statusLabel.text = "还没有题面：到搜索页双击题目，或在拉取页输入题号"
            return
        }
        renderProblem(problem)
    }

    private fun renderProblem(problem: LuoguProblemDto) {
        showingSolutions = false
        statusLabel.text = "${problem.pid} ${problem.name}  难度：${difficultyText(problem.difficulty)}"
        applyModeToToolbar()
        present(buildProblemHtml(problem))
    }

    /** 渲染当前模式的正文：题面，或列表里选中的那一篇题解。 */
    private fun renderCurrent() {
        if (!showingSolutions) {
            currentProblem?.let(::renderProblem)
            return
        }
        val solution = solutionList.selectedValue ?: return
        applyModeToToolbar()
        present(buildSolutionHtml(solution))
    }

    /**
     * 拉取一页题解。[append] 为真时追加到列表末尾并保留当前选中项，
     * 否则替换整份列表并选中首篇。
     */
    private fun loadSolutionPage(targetPage: Int, append: Boolean) {
        val pid = currentPid
        if (pid == null) {
            statusLabel.text = "还没有题号：先预览一道题"
            return
        }
        if (loadingPage) return
        loadingPage = true
        applyModeToToolbar()
        statusLabel.text = if (append) "加载更多题解…" else "加载 $pid 题解…"

        LuoguActions.loadSolutions(
            project = project,
            pid = pid,
            page = targetPage,
            onResult = { result ->
                loadingPage = false
                if (!append) {
                    solutionModel.removeAllElements()
                    solutionsPid = pid
                }
                result.solutions.forEach { solutionModel.addElement(it) }
                page = result.page
                hasMore = result.hasMore
                applyModeToToolbar()
                val loaded = solutionModel.size
                val total = if (result.total > 0) result.total else loaded
                statusLabel.text = "$pid 题解 共 $total 篇 · 已加载 $loaded 篇"
                when {
                    loaded == 0 -> present("<html><body>该题暂无题解</body></html>")
                    append -> Unit // 保留当前阅读位置
                    else -> {
                        // 先清空再选首篇：保证必发一次选中事件，由监听器渲染（避免重复 loadHTML）
                        solutionList.selectedIndex = -1
                        solutionList.selectedIndex = 0
                    }
                }
            },
            onError = { msg ->
                loadingPage = false
                applyModeToToolbar()
                showMessage(msg)
            },
        )
    }

    /**
     * 模式切换：题解列表与「加载更多」只在题解模式出现，
     * 「加载更多」还要满足有下一页且不在请求中。
     */
    private fun applyModeToToolbar() {
        solutionListPanel.isVisible = showingSolutions
        moreButton.isEnabled = showingSolutions && hasMore && !loadingPage
        solutionButton.isEnabled = currentPid != null
        revalidate()
    }

    fun showMessage(text: String) {
        statusLabel.text = text
        present("<html><body>${escapeHtml(text)}</body></html>")
    }

    private fun present(html: String) {
        browser?.loadHTML(html) ?: run {
            fallbackArea.text = html
            fallbackArea.caretPosition = 0
        }
    }

    /** 文档骨架：主题样式 + 内置 MathJax；调用方往返回值里追加正文，最后交给 [endHtml] 收尾。 */
    private fun startHtml(): StringBuilder {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"color-scheme\" content=\"")
        sb.append(if (JBColor.isBright()) "only light" else "dark")
        sb.append("\"><style>")
        sb.append(themeCss())
        sb.append("img{max-width:100%;}")
        sb.append("h1{font-size:18px;}h2{font-size:15px;}h3{font-size:14px;}")
        sb.append("</style>")
        sb.append("<script>window.MathJax={tex:{inlineMath:[['\$','\$'],['\\\\(','\\\\)']],")
        sb.append("displayMath:[['\$\$','\$\$'],['\\\\[','\\\\]']]},options:{enableMenu:false}};</script>")
        val mathjax = MATHJAX_JS
        if (mathjax.isNotEmpty()) {
            sb.append("<script>").append(mathjax).append("</script>")
        }
        sb.append("</head><body>")
        return sb
    }

    /** 收尾：触发 MathJax typeset 并闭合文档。 */
    private fun endHtml(sb: StringBuilder): String {
        sb.append("<script>if(window.MathJax&&MathJax.startup&&MathJax.startup.promise){")
        sb.append("MathJax.startup.promise.then(function(){return MathJax.typesetPromise()})")
        sb.append(".catch(function(){})}</script>")
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun buildProblemHtml(problem: LuoguProblemDto): String {
        val sb = startHtml()
        sb.append("<h1>").append(escapeHtml(problem.pid)).append(" ")
            .append(escapeHtml(problem.name)).append("</h1>")

        val time = problem.limits.time.firstOrNull()
        val memory = problem.limits.memory.firstOrNull()
        sb.append("<p>")
        sb.append("<b>难度</b>：").append(escapeHtml(difficultyText(problem.difficulty))).append("<br/>")
        sb.append("<b>时间限制</b>：").append(time?.let { "$it ms" } ?: "暂无").append("<br/>")
        sb.append("<b>内存限制</b>：").append(memory?.let { "$it KB" } ?: "暂无").append("<br/>")
        sb.append("<b>分数</b>：").append(problem.fullScore)
        sb.append("</p>")

        val body = problem.content ?: problem.contenu
        if (body == null) {
            sb.append("<p>暂无题目描述</p>")
        } else {
            body.section("background")?.let { sb.append("<h2>题目背景</h2>").append(it) }
            body.section("description")?.let { sb.append("<h2>题目描述</h2>").append(it) }
            body.section("formatI")?.let { sb.append("<h2>输入格式</h2>").append(it) }
            body.section("formatO")?.let { sb.append("<h2>输出格式</h2>").append(it) }
            body.section("hint")?.let { sb.append("<h2>提示</h2>").append(it) }
        }

        problem.samples.forEachIndexed { index, sample ->
            val n = index + 1
            val input = sample.getOrNull(0).orEmpty().removeSuffix("\n")
            val output = sample.getOrNull(1).orEmpty().removeSuffix("\n")
            sb.append("<h3>样例输入 ").append(n).append("</h3><pre>")
                .append(escapeHtml(input)).append("</pre>")
            sb.append("<h3>样例输出 ").append(n).append("</h3><pre>")
                .append(escapeHtml(output)).append("</pre>")
        }

        return endHtml(sb)
    }

    /** 单篇题解：标题 + 元信息 + 正文。 */
    private fun buildSolutionHtml(solution: SolutionSummary): String {
        val sb = startHtml()
        sb.append("<h1>").append(escapeHtml(solution.title)).append("</h1>")
        sb.append("<p><b>作者</b>：").append(escapeHtml(solution.author ?: "佚名"))
        solution.upvote?.let { sb.append("　<b>投票</b>：").append(it) }
        sb.append("</p>")

        val markdown = solution.contentMarkdown
        if (markdown.isNullOrBlank()) {
            sb.append("<p>该题解没有正文内容</p>")
            return endHtml(sb)
        }
        if (browser == null) {
            // 没有 JCEF 就跑不了 marked，只能按原文展示（至少内容可读）
            sb.append("<pre>").append(escapeHtml(markdown)).append("</pre>")
            return endHtml(sb)
        }
        sb.append("<div id=\"luogu-solution\"></div>")
        sb.append("<script id=\"luogu-md\" type=\"application/json\">")
            .append(jsonLiteral(markdown))
            .append("</script>")
        if (MARKED_JS.isNotEmpty()) sb.append("<script>").append(MARKED_JS).append("</script>")
        if (SOLUTION_RENDER_JS.isNotEmpty()) {
            sb.append("<script>").append(SOLUTION_RENDER_JS).append("</script>")
        }
        return endHtml(sb)
    }

    /**
     * 字符串 → JSON 字面量（题解正文用它传入浏览器侧）。
     *
     * 额外把 `</` 写成 `<\/`：JSON/JS 里二者等价，但正文若含 `</script>` 会把元素提前闭合。
     */
    private fun jsonLiteral(text: String): String =
        Json.encodeToString(String.serializer(), text).replace("</", "<\\/")

    private fun themeCss(): String {
        val background = UIUtil.getPanelBackground()
        val foreground = UIUtil.getLabelForeground()
        val codeBackground = JBColor(Color(0xF2, 0xF2, 0xF2), Color(0x2B, 0x2D, 0x30))
        val borderColor = JBColor(Color(0xCC, 0xCC, 0xCC), Color(0x4E, 0x51, 0x55))
        return buildString {
            append(":root{color-scheme:").append(if (JBColor.isBright()) "only light" else "dark").append(";}")
            append("body{font-family:sans-serif;font-size:13px;line-height:1.6;padding:8px;margin:0;")
            append("background:").append(hex(background)).append(";color:").append(hex(foreground)).append(";}")
            append("pre{font-family:monospace;padding:6px;white-space:pre-wrap;background:")
            append(hex(codeBackground)).append(";color:").append(hex(foreground))
            append(";border:1px solid ").append(hex(borderColor)).append(";}")
            append("table{border-collapse:collapse;}td,th{border:1px solid ").append(hex(borderColor))
            append(";padding:2px 4px;}")
            append("h1,h2,h3,p,li,td,th{color:").append(hex(foreground)).append(";}")
        }
    }

    private fun hex(color: Color): String =
        String.format("#%02x%02x%02x", color.red, color.green, color.blue)

    private fun difficultyText(difficulty: Int): String =
        ProblemMdGenService.difficultyMap[difficulty] ?: difficulty.toString()

    private fun JsonObject.section(key: String): String? {
        val raw = this[key]?.jsonPrimitive?.contentOrNull ?: return null
        return renderBody(raw).ifBlank { null }
    }

    private fun renderBody(raw: String): String {
        var text = sanitize(raw)
        text = MD_IMG_REGEX.replace(text) { "<img src=\"${it.groupValues[2]}\" alt=\"${it.groupValues[1]}\">" }
        text = escapeMath(text)
        return text
    }

    private fun escapeMath(html: String): String {
        var text = DISPLAY_MATH_REGEX.replace(html) {
            "\$\$" + it.groupValues[1].replace("<", "&lt;").replace(">", "&gt;") + "\$\$"
        }
        text = INLINE_MATH_REGEX.replace(text) {
            "\$" + it.groupValues[1].replace("<", "&lt;").replace(">", "&gt;") + "\$"
        }
        return text
    }

    private fun sanitize(html: String): String {
        var text = SCRIPT_REGEX.replace(html, "")
        text = STYLE_REGEX.replace(text, "")
        text = A_HREF_REGEX.replace(text) { it.groupValues[1] }
        text = IFRAME_REGEX.replace(text, "")
        text = OBJECT_REGEX.replace(text, "")
        text = EMBED_REGEX.replace(text, "")
        text = LINK_REGEX.replace(text, "")
        text = EVENT_ATTR_REGEX.replace(text, "")
        text = text.replace("</br>", "<br/>", ignoreCase = true)
            .replace("<br>", "<br/>", ignoreCase = true)
        return text
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    companion object {
        /** 题解列表条的高度：够露出几篇标题可点，又不挤掉正文。 */
        private const val LIST_HEIGHT = 120

        private fun resourceText(path: String): String =
            PreviewPanel::class.java.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() } ?: ""

        private val MATHJAX_JS: String by lazy { resourceText("/mathjax/tex-svg.js") }

        /** Markdown → HTML 解析器（marked v12.0.2，MIT，见 resources/marked/NOTICE.txt）。 */
        private val MARKED_JS: String by lazy { resourceText("/marked/marked.min.js") }

        /** 公式保护 + 清洗 + 挂载，见 resources/js/solution-render.js。 */
        private val SOLUTION_RENDER_JS: String by lazy { resourceText("/js/solution-render.js") }
    }
}
