package com.user.clionluogu.ui

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.user.clionluogu.api.LuoguProblemDto
import com.user.clionluogu.service.ProblemMdGenService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.JEditorPane
import javax.swing.JPanel

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

private val MD_IMG_REGEX = Regex("!\\[([^\\]]*)\\]\\(\\s*([^)\\s]+)\\s*\\)")

private val DISPLAY_MATH_REGEX = Regex("\\$\\$(.*?)\\$\\$", RegexOption.DOT_MATCHES_ALL)

private val INLINE_MATH_REGEX = Regex("\\$([^$\\n]+?)\\$")

/** 预览页：展示题面元信息、正文与样例，数据由外部注入，本页不主动发起网络请求。 */
class PreviewPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val statusLabel = JBLabel("在搜索页双击题目以预览")
    private val browser: JBCefBrowser? = createBrowser()
    private val fallbackArea = JEditorPane().apply {
        isEditable = false
        contentType = "text/html"
        border = JBUI.Borders.empty(6)
    }

    init {
        add(statusLabel, BorderLayout.NORTH)
        val component = browser?.component
        if (component != null) {
            add(component, BorderLayout.CENTER)
        } else {
            add(JBScrollPane(fallbackArea), BorderLayout.CENTER)
        }
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
        val difficulty = difficultyText(problem.difficulty)
        statusLabel.text = "${problem.pid} ${problem.name}  难度：$difficulty"
        present(buildHtml(problem))
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

    private fun buildHtml(problem: LuoguProblemDto): String {
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

        sb.append("<script>if(window.MathJax&&MathJax.startup&&MathJax.startup.promise){")
        sb.append("MathJax.startup.promise.then(function(){return MathJax.typesetPromise()})")
        sb.append(".catch(function(){})}</script>")
        sb.append("</body></html>")
        return sb.toString()
    }

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
        private val MATHJAX_JS: String by lazy {
            PreviewPanel::class.java.getResourceAsStream("/mathjax/tex-svg.js")
                ?.bufferedReader()?.use { it.readText() } ?: ""
        }
    }
}
