package com.user.clionluogu.ui

import com.intellij.openapi.Disposable
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
    /** 懒建：构造期那次探测失败不该把 JCEF 永久判死（下面 [browser] 会再问一次）。 */
    private var browserCache: JBCefBrowser? = null

    /** 正文占位：JCEF 可用时挂浏览器，不可用时挂纯文本兜底。两边切换靠 [present]。 */
    private val contentHost = JPanel(BorderLayout())

    private val fallbackArea = JEditorPane().apply {
        isEditable = false
        contentType = "text/html"
        border = JBUI.Borders.empty(6)
    }
    private val fallbackScroll = JBScrollPane(fallbackArea)

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
            add(contentHost, BorderLayout.CENTER)
        }
        add(center, BorderLayout.CENTER)

        problemButton.addActionListener { showProblemMode() }
        solutionButton.addActionListener { currentPid?.let { showSolutionsFor(it) } }
        moreButton.addActionListener { loadSolutionPage(page + 1, append = true) }
        applyModeToToolbar()
    }

    /**
     * 有没有 JCEF：**只问 [JBCefApp.isSupported]，而且问到能用为止**。
     *
     * 他这台 CLion 的 JCEF 是真有的 —— 原生件在 `Contents/plugins/jcef-plugin/jcef`
     * （2026.x 把它从 JBR 挪进了插件目录，所以在 JBR 里搜 jcef 搜不到，别据此判死），
     * 日志里 `JBCefApp` 打出过 `jcef version: remote_144.0.15.3416`。
     * 那为什么原来是纯文本兜底？看 `jcef-plugin.jar/META-INF/plugin.xml`：
     * `JBCefStartup`（真正把 JCEF 拉起的那个 applicationService）挂在**延迟加载的模块**
     * `intellij.platform.ui.jcef` 上，`preload="notHeadless"` —— 日志里它要到启动后 **4~5 秒**才初始化。
     * 而工具窗口面板是启动时就建的，旧写法 `private val browser = if (...) JBCefBrowser() else null`
     * 是**字段初始化**：那一句问得太早，答 false 之后整个面板生命周期都定死了。
     * 现在改成要用正文时才问、问到 true 为止（顺带删掉多余的 `isPluginInstalled` 那一句：
     * 模块真缺的话 `<depends>` 就让插件根本加载不了，不会走到这里）。
     */
    private fun browser(): JBCefBrowser? {
        browserCache?.let { return it }
        if (!runCatching { JBCefApp.isSupported() }.getOrDefault(false)) return null
        return runCatching { JBCefBrowser().also { browserCache = it } }.getOrNull()
    }

    override fun dispose() {
        browserCache?.dispose()
        browserCache = null
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

    /**
     * 挂载正文视图并送 HTML。
     *
     * 两个方向都要能切：JCEF 一旦建起来就常驻（反复装卸 CEF 组件比反复 loadHTML 贵得多），
     * 而兜底那条路**必须把提示插在 HTML 里**——否则纯文本渲染下 Markdown 原样显示，
     * 他会以为是我们没解析，而不是「这台机器没有 JCEF」。
     */
    private fun present(html: String) {
        val view = browser()
        if (view != null) {
            if (contentHost.components.firstOrNull() !== view.component) {
                contentHost.removeAll()
                contentHost.add(view.component, BorderLayout.CENTER)
                contentHost.revalidate()
                contentHost.repaint()
            }
            view.loadHTML(html)
            return
        }
        if (contentHost.components.firstOrNull() !== fallbackScroll) {
            contentHost.removeAll()
            contentHost.add(fallbackScroll, BorderLayout.CENTER)
            contentHost.revalidate()
        }
        fallbackArea.text = html.replace(
            "<body>",
            "<body><p class='fallback-note'>纯文本渲染：JCEF 此刻不可用，Markdown 与公式没有渲染。" +
                "刚打开 IDE 时 JCEF 要几秒才初始化完，稍等重新双击这道题即可。</p>",
        )
        fallbackArea.caretPosition = 0
    }

    /** 文档骨架：主题样式 + 内置 MathJax；调用方往返回值里追加正文，最后交给 [endHtml] 收尾。 */
    private fun startHtml(): StringBuilder {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"color-scheme\" content=\"")
        sb.append(if (JBColor.isBright()) "only light" else "dark")
        sb.append("\"><style>")
        sb.append(themeCss())
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
        val usesJcef = browser() != null
        sb.append("<h1>").append(escapeHtml(problem.pid)).append(" ")
            .append(escapeHtml(problem.name)).append("</h1>")

        val time = problem.limits.time.firstOrNull()
        val memory = problem.limits.memory.firstOrNull()
        // 元信息用「一行一枚 + 标记里带分隔符」，理由见 [metaChipsHtml]
        sb.append(
            metaChipsHtml(
                listOf(
                    "难度" to difficultyText(problem.difficulty),
                    "时间限制" to (time?.let { "$it ms" } ?: "暂无"),
                    "内存限制" to (memory?.let { "$it KB" } ?: "暂无"),
                    "分数" to problem.fullScore.toString(),
                ),
            ),
        )

        val body = problem.content ?: problem.contenu
        if (body == null) {
            sb.append("<p>暂无题目描述</p>")
        } else {
            sb.append(
                bodyBlocksHtml(
                    listOfNotNull(
                        body.section("background")?.let { "题目背景" to it },
                        body.section("description")?.let { "题目描述" to it },
                        body.section("formatI")?.let { "输入格式" to it },
                        body.section("formatO")?.let { "输出格式" to it },
                        body.section("hint")?.let { "提示" to it },
                    ),
                    usesJcef = usesJcef,
                    literal = { text -> jsonLiteral(text) },
                ),
            )
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

        rendererScripts(sb, usesJcef)
        return endHtml(sb)
    }

    /** 单篇题解：标题 + 元信息 + 正文。 */
    private fun buildSolutionHtml(solution: SolutionSummary): String {
        val sb = startHtml()
        val usesJcef = browser() != null
        sb.append("<h1>").append(escapeHtml(solution.title)).append("</h1>")
        sb.append("<div class=\"byline\">")
            .append("<p class=\"chip\"><b>作者</b>：").append(escapeHtml(solution.author ?: "佚名")).append("</p>")
        solution.upvote?.let { sb.append("<span class=\"votes\">↑ ").append(it).append("</span>") }
        sb.append("</div>")

        val markdown = solution.contentMarkdown
        if (markdown.isNullOrBlank()) {
            sb.append("<p>该题解没有正文内容</p>")
            return endHtml(sb)
        }
        if (!usesJcef) {
            // 没有 JCEF 就跑不了 marked，只能按原文展示（至少内容可读）
            sb.append("<pre>").append(escapeHtml(markdown)).append("</pre>")
            return endHtml(sb)
        }
        sb.append("<div id=\"luogu-solution\">").append(escapeHtml(markdown)).append("</div>")
        sb.append("<script id=\"luogu-md\" type=\"application/json\">")
            .append(jsonLiteral(markdown))
            .append("</script>")
        rendererScripts(sb, usesJcef)
        return endHtml(sb)
    }

    /**
     * marked 与渲染脚本：只在 JCEF 路径注入。
     *
     * 顺序有讲究 —— marked 先定义 `window.marked`，[SOLUTION_RENDER_JS] 的 boot() 是同步内联脚本，
     * 解析到就跑；MathJax 的 typeset 在 [endHtml] 里挂在 startup.promise 之后，所以公式一定在
     * Markdown 落好之后才排版。兜底那条路不能带这两个：JEditorPane 会把脚本内容当正文打印出来。
     */
    private fun rendererScripts(sb: StringBuilder, usesJcef: Boolean) {
        if (!usesJcef) return
        if (MARKED_JS.isNotEmpty()) sb.append("<script>").append(MARKED_JS).append("</script>")
        if (SOLUTION_RENDER_JS.isNotEmpty()) sb.append("<script>").append(SOLUTION_RENDER_JS).append("</script>")
    }

    /**
     * 主题化样式：规则在 <code>resources/css/preview.css</code> 里，这里只按当前 IDE 主题
     * 把 @TOKEN@ 换成实际色值与字体，所以浅色/深色自动跟随，改样式也不用动 Kotlin。
     *
     * 字体取 IDE 的 UI 字体与正文字号（以前写死 `sans-serif` + 13px，在放大字体的
     * 高分屏上比周围界面小一圈）。
     */
    private fun themeCss(): String {
        val background = UIUtil.getPanelBackground()
        val foreground = UIUtil.getLabelForeground()
        val muted = UIUtil.getLabelDisabledForeground()
        val codeBackground = JBColor(Color(0xF2, 0xF2, 0xF2), Color(0x2B, 0x2D, 0x30))
        val rowBackground = JBColor(Color(0xE4, 0xE4, 0xE4), Color(0x3C, 0x3F, 0x42))
        val borderColor = JBColor(Color(0xCC, 0xCC, 0xCC), Color(0x4E, 0x51, 0x55))
        val accent = JBColor.namedColor(
            "Link.activeForeground",
            JBColor(Color(0x2A, 0x6F, 0xB5), Color(0x6A, 0xB6, 0xF2)),
        )
        val uiFont = UIUtil.getLabelFont()
        return PREVIEW_CSS
            .replace("@SCHEME@", if (JBColor.isBright()) "only light" else "dark")
            .replace("@FONT@", cssFontList(uiFont.family))
            .replace("@MONO@", cssFontList(java.awt.Font.MONOSPACED))
            .replace("@SIZE@", uiFont.size.toString())
            .replace("@BG@", hex(background))
            .replace("@FG@", hex(foreground))
            .replace("@MUTED@", hex(muted))
            .replace("@CODE_BG@", hex(codeBackground))
            .replace("@ROW_BG@", hex(rowBackground))
            .replace("@BORDER@", hex(borderColor))
            .replace("@ACCENT@", hex(accent))
    }

    /** 字体栈：先把 IDE 实际用的字体放前面，再退回通用族（CSS 里不能出现引号外的空格族名）。 */
    private fun cssFontList(family: String): String =
        "\"${family.replace("\"", "")}\", sans-serif"

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

        /**
         * 元信息一行一枚：`<p>` 而不是 `<span>`，键与值之间带「：」。
         *
         * 两处都要活：JCEF 里 CSS 把 `<p>` 收成 inline-block，是一排徽章；
         * 没有 JCEF 时 JEditorPane 只认 CSS 2.1（丢 display、丢 margin），于是退回一行一枚。
         * 而**复制**走的是纯文本 —— 原来 `<span><b>难度</b>普及-</span>` 粘在一起
         * 就是他从 B2002 复制出「难度普及-时间限制1000 ms内存限制…」的原因，
         * 所以分隔符必须写进标记本身，不能指望 CSS。
         */
        @JvmStatic
        fun metaChipsHtml(cells: List<Pair<String, String>>): String =
            cells.joinToString(prefix = "<div class='meta'>", postfix = "</div>") { (key, value) ->
                "<p class='chip'><b>$key</b>：$value</p>"
            }

        /**
         * 正文分段：每段一个标题 + 一块 Markdown。
         *
         * JCEF 在时才嵌 JSON 载荷（脚本会把它渲染成 HTML，`[文字](链接)` 与 `- 列表` 都能变成真的排版）；
         * 兜底路径不嵌 —— JEditorPane 会把 `<script>` 里的 JSON 当文字打印出来，那比不渲染更糟。
         */
        @JvmStatic
        fun bodyBlocksHtml(
            blocks: List<Pair<String, String>>,
            usesJcef: Boolean,
            literal: (String) -> String,
        ): String = blocks.mapIndexed { index, (heading, raw) ->
            val id = "luogu-md-$index"
            buildString {
                append("<h2>").append(heading).append("</h2>")
                append("<div class='md' data-luogu-md='").append(id).append("'>")
                append(raw)
                append("</div>")
                if (usesJcef) {
                    append("<script id='").append(id).append("' type='application/json'>")
                        .append(literal(raw))
                        .append("</script>")
                }
            }
        }.joinToString("")

        /**
         * 字符串 → JSON 字面量（题面与题解正文都用它传入浏览器侧）。
         *
         * 额外把 `</` 写成 `<\/`：JSON/JS 里二者等价，但正文若含 `</script>` 会把载荷元素提前闭合，
         * 后面整页脚本随之报废 —— 所以这条规矩得有探针钉着（[jsonLiteral] 因此放在 companion 里）。
         */
        @JvmStatic
        fun jsonLiteral(text: String): String =
            Json.encodeToString(String.serializer(), text).replace("</", "<\\/")

        private fun resourceText(path: String): String =
            PreviewPanel::class.java.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() } ?: ""

        private val MATHJAX_JS: String by lazy { resourceText("/mathjax/tex-svg.js") }

        /** Markdown → HTML 解析器（marked v12.0.2，MIT，见 resources/marked/NOTICE.txt）。 */
        private val MARKED_JS: String by lazy { resourceText("/marked/marked.min.js") }

        /** 公式保护 + 清洗 + 挂载，见 resources/js/solution-render.js。 */
        private val SOLUTION_RENDER_JS: String by lazy { resourceText("/js/solution-render.js") }

        /** 预览页样式表（@TOKEN@ 由 [themeCss] 按主题替换），见 resources/css/preview.css。 */
        private val PREVIEW_CSS: String by lazy { resourceText("/css/preview.css") }
    }
}
