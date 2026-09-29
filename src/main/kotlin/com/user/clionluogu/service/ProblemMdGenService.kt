package com.user.clionluogu.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.user.clionluogu.api.LuoguProblemDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 根据 [LuoguProblemDto] 生成 Markdown 题面（`Pxxxx.md`），并提供写入封装。
 *
 * - 正文取 content 优先、contenu 兜底；两者皆空时显示「暂无题目描述」。
 * - 段落读取键：background/description/formatI/formatO/hint。
 * - 末尾附全部样例，每组以 ` ```input ` / ` ```output ` 代码块呈示。
 * - 只做轻量清洗：保留 Markdown 语义（`\n`、`**`、列表、代码块原样），
 *   并将 ` <br>`/`</br>` 替换为换行以避免乱码。
 */
object ProblemMdGenService {

    /** 洛谷 difficulty 数字 → 中文等级；不在表中的难度值回退为原始数字。 */
    internal val difficultyMap = mapOf(
        0 to "入门",
        1 to "普及-",
        2 to "普及/提高-",
        3 to "普及+/提高",
        4 to "提高+/省选-",
        5 to "省选/NOI-",
        6 to "NOI/NOI+/CTSC",
    )

    /** 生成 Markdown 题面字符串。 */
    fun buildMarkdown(problem: LuoguProblemDto): String {
        val sb = StringBuilder()

        // 标题
        val pid = problem.pid
        val name = problem.name.ifBlank { pid }
        sb.append("# $pid $name").append("\n\n")

        // 元信息
        val difficulty = difficultyMap[problem.difficulty] ?: problem.difficulty.toString()
        sb.append("**难度**: $difficulty").append("\n")
        sb.append("**标签**: ").append(problem.tags.joinToString(", ") { it.toString() }).append("\n")

        val time = problem.limits.time.firstOrNull()
        val memory = problem.limits.memory.firstOrNull()
        sb.append("**时间限制**: ").append(time?.let { "${it} ms" } ?: "暂无").append("\n")
        sb.append("**内存限制**: ").append(memory?.let { "${it} KB" } ?: "暂无").append("\n")
        sb.append("**分数**: ").append(problem.fullScore).append("\n\n")

        // 正文
        val body = problem.content ?: problem.contenu
        if (body == null) {
            sb.append("暂无题目描述").append("\n")
        } else {
            body.section("background")?.let { sb.append("## 题目背景\n\n$it\n\n") }
            body.section("description")?.let { sb.append("## 题目描述\n\n$it\n\n") }
            body.section("formatI")?.let { sb.append("## 输入格式\n\n$it\n\n") }
            body.section("formatO")?.let { sb.append("## 输出格式\n\n$it\n\n") }
            body.section("hint")?.let { sb.append("## 提示\n\n$it\n\n") }
        }

        // 样例
        val samples = problem.samples
        if (samples.isNotEmpty()) {
            sb.append("## 样例\n\n")
            samples.forEachIndexed { index, sample ->
                val n = index + 1
                val input = sample.getOrNull(0).orEmpty().removeSuffix("\n")
                val output = sample.getOrNull(1).orEmpty().removeSuffix("\n")
                sb.append("### 样例输入 $n\n\n").append("```input\n").append(input).append("\n```\n\n")
                sb.append("### 样例输出 $n\n\n").append("```output\n").append(output).append("\n```\n\n")
            }
        }

        return sb.toString()
    }

    /** 将题面写入项目根目录 `$pid.md`（已存在则覆盖），返回文件句柄；写失败时返回 null。 */
    fun write(project: Project, pid: String, markdown: String): VirtualFile? {
        val base = project.basePath ?: return null
        val baseDir = LocalFileSystem.getInstance().findFileByPath(base)
            ?: return null
        if (!baseDir.isDirectory) return null

        var file: VirtualFile? = null
        ApplicationManager.getApplication().runWriteAction {
            val target = baseDir.findChild("$pid.md")
                ?: baseDir.createChildData(project, "$pid.md")
            if (!target.isDirectory) {
                VfsUtil.saveText(target, markdown)
                file = target
            }
        }
        if (file != null) {
            VfsUtil.markDirtyAndRefresh(false, false, true, file!!)
        }
        return file
    }

    /** 从正文对象读取段落，转为规范 Markdown；键缺失或值为空时返回 null。 */
    private fun JsonObject.section(key: String): String? {
        val raw = this[key]?.jsonPrimitive?.contentOrNull ?: return null
        return htmlToMarkdown(raw).ifBlank { null }
    }

    private fun htmlToMarkdown(html: String): String {
        var text = html
        val ci = RegexOption.IGNORE_CASE
        text = Regex("<br\\s*/?>", ci).replace(text, "\n")
        text = Regex("</br>", ci).replace(text, "\n")
        text = Regex("<p[^>]*>", ci).replace(text, "")
        text = Regex("</p>", ci).replace(text, "\n\n")
        text = Regex("<div[^>]*>", ci).replace(text, "\n")
        text = Regex("</div>", ci).replace(text, "\n")
        text = Regex("<ul[^>]*>", ci).replace(text, "\n")
        text = Regex("</ul>", ci).replace(text, "\n")
        text = Regex("<ol[^>]*>", ci).replace(text, "\n")
        text = Regex("</ol>", ci).replace(text, "\n")
        text = Regex("<li[^>]*>", ci).replace(text, "- ")
        text = Regex("</li>", ci).replace(text, "\n")
        text = Regex("<strong[^>]*>", ci).replace(text, "**")
        text = Regex("<b[^>]*>", ci).replace(text, "**")
        text = Regex("</strong>", ci).replace(text, "**")
        text = Regex("</b>", ci).replace(text, "**")
        text = Regex("<em[^>]*>", ci).replace(text, "*")
        text = Regex("<i[^>]*>", ci).replace(text, "*")
        text = Regex("</em>", ci).replace(text, "*")
        text = Regex("</i>", ci).replace(text, "*")
        text = Regex("<code[^>]*>", ci).replace(text, "`")
        text = Regex("</code>", ci).replace(text, "`")
        text = Regex("<pre[^>]*>", ci).replace(text, "\n")
        text = Regex("</pre>", ci).replace(text, "\n")
        text = Regex("<sub[^>]*>", ci).replace(text, "")
        text = Regex("</sub>", ci).replace(text, "")
        text = Regex("<sup[^>]*>", ci).replace(text, "")
        text = Regex("</sup>", ci).replace(text, "")
        text = Regex("<span[^>]*>", ci).replace(text, "")
        text = Regex("</span>", ci).replace(text, "")
        text = Regex("<[^>]+>", ci).replace(text, "")

        text = text.replace("&lt;", "<")
        text = text.replace("&gt;", ">")
        text = text.replace("&quot;", "\"")
        text = text.replace("&#39;", "'")
        text = text.replace("&apos;", "'")
        text = text.replace("&nbsp;", " ")
        text = text.replace("&hellip;", "…")
        text = text.replace("&le;", "≤")
        text = text.replace("&ge;", "≥")
        text = text.replace("&times;", "×")
        text = text.replace("&amp;", "&")

        text = Regex("\n{3,}").replace(text, "\n\n")
        text = text.lines().joinToString("\n") { it.trimEnd() }
        return text.trim()
    }
}