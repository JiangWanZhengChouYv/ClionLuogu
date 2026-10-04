package com.user.clionluogu.ui

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.user.clionluogu.api.LuoguApiService
import com.user.clionluogu.api.LuoguPidValidator
import com.user.clionluogu.service.LuoguActions
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Point
import java.io.File
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.Timer

/**
 * 提交页：确认题号与语言版本，预览将要提交的代码并提交。
 *
 * 要交的那份按**题号**找：编辑器里打开的 `Pxxx.cpp` 优先（含未保存的修改），
 * 其次项目根那份磁盘文件；并且每 [REFRESH_MS] 毫秒重看一次。
 * 为什么要定时器：工具窗口的 Content 是缓存的，切页签、甚至把侧边栏关掉再打开
 * 都不一定再触发 `addNotify()`，只靠「进来刷一次」会一直停在旧内容上。
 */
class SubmitPanel(
    private val project: Project,
    private val onSubmitted: (pid: String, rid: String, lang: String, code: String) -> Unit,
) : JPanel(BorderLayout()) {

    private val pidField = JBTextField()
    private val langCombo = ComboBox<String>()
    private val previewArea = JBTextArea()
    private val previewScroll = JBScrollPane(previewArea)
    private val submitButton = JButton("提交")
    private val statusLabel = JBLabel(" ")

    /** 上一次由当前编辑器自动填进去的题号；只有它还在框里时才允许被新值覆盖。 */
    private var autoPid: String? = null

    /** 上一次呈现的内容签名；签名没变就不重读、不重画（重画会抢滚动位置）。 */
    private var shownSignature: String? = null

    /** 当前预览的那份代码——提交时交的就是它，保证「看到的 = 交出去的」。 */
    private var currentCode: String = ""

    private val ticker = Timer(REFRESH_MS) { refreshPreview(force = false) }

    init {
        guessedPid()?.let { autoPid = it }
        pidField.text = autoPid ?: "P1001"
        pidField.emptyText.appendText("题号，形如 P1001")

        val langs = LuoguApiService.LANGUAGES
        langCombo.model = DefaultComboBoxModel(langs.keys.toTypedArray())
        val defaultLang = "C++17 (O2)"
        langCombo.selectedItem = if (langs.containsKey(defaultLang)) defaultLang else langs.keys.firstOrNull()

        previewArea.isEditable = false
        previewArea.lineWrap = false
        previewArea.border = JBUI.Borders.empty(6)
        // 预览的是 C++ 源码：等宽、不折行，长行横向滚
        previewArea.font = DetailPanel.monoFont()
        previewArea.emptyText.appendText("这里显示将要提交的代码（按题号找：编辑器里那份优先，其次项目根那份）")

        // 提交页搬到底部窗口之后，纵向高度是稀缺资源：题号与语言并成一行，
        // 预览区吃满剩下的全部高度。「刷新预览」这个按钮删了 —— 每秒自动重看已经取代它，
        // 留着只是白占一行还多一个可能点错的东西（要立刻看就回车或点一下别处）。
        val form = JPanel(WrapLayout(FlowLayout.LEFT, 6, 2))
        form.border = JBUI.Borders.empty(4, 8, 0, 8)
        form.add(JBLabel("题号"))
        form.add(pidField)
        form.add(JBLabel("语言版本"))
        form.add(langCombo)

        // 不用 BorderLayout 的 WEST + CENTER：窄侧边栏下状态文字会把提交按钮挤出去
        val bottom = JPanel(WrapLayout(FlowLayout.LEFT, 6, 2))
        bottom.border = JBUI.Borders.empty(4, 8)
        bottom.add(submitButton)
        bottom.add(statusLabel)

        add(form, BorderLayout.NORTH)
        add(previewScroll, BorderLayout.CENTER)
        add(bottom, BorderLayout.SOUTH)
        submitButton.addActionListener { doSubmit() }
        // 改完题号立刻重看，不用等下一个 tick
        pidField.addActionListener { refreshPreview(force = true) }
        // 语言下拉与提交按钮**故意不响应回车**：提交是写操作，必须一下实点（他定的规矩）。
        // 回车只用于「看一眼要交的是哪一份」这类读操作。
    }

    override fun addNotify() {
        super.addNotify()
        refreshPreview(force = true)
        ticker.start()
    }

    /** 面板离开容器（切走页签、关掉侧边栏）就停表：不该在背后空转。 */
    override fun removeNotify() {
        ticker.stop()
        super.removeNotify()
    }

    /**
     * 题号跟着当前编辑器走：只在输入框为空、或里面仍是上次自动填的那个值时覆盖，
     * 手输的题号不会被冲掉（与对拍页同一条规则）。
     */
    private fun syncPidFromEditor() {
        val guess = CurrentFilePid.guess(project) ?: return
        val typed = pidField.text.trim()
        if (typed.isEmpty() || typed == autoPid) {
            pidField.text = guess
            autoPid = guess
        }
    }

    /** 一次「要不要重读」的探测结果：来源 + 变化凭据。编辑器那份的文档留着，签名变了才取全文。 */
    private class Probe(
        val origin: CodeOrigin,
        val signature: String,
        val document: Document?,
        val fileName: String?,
    )

    /** 将要提交的代码与它的来源（来源写在界面上，别让人猜插件拿的是哪一份）。 */
    private class SubmissionCode(val text: String, val source: String)

    /**
     * 便宜地看一眼「现在要交的是哪份、变了没有」，**不读磁盘内容**。
     *
     * 这条每秒都要跑，所以磁盘那一路只 `stat`（长度 + mtime），签名变了才去 [readCode] 真读；
     * 编辑器那一路看文档的 `modificationStamp` + 长度，也不复制全文。
     *
     * 找文件是**按题号在所有已打开文件里找**，不是只看当前选中的那个编辑器：
     * 只看选中项会让「`P1001.cpp` 明明开着、当前标签却是 `main.cpp`」判成没打开（这就是 1.7.4 修的 bug）。
     */
    private fun probe(pid: String): Probe {
        val wanted = "$pid.cpp"
        val manager = FileEditorManager.getInstance(project)
        val open = manager.openFiles.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
        val diskFile = project.basePath?.let { File(it, wanted) }
        val origin = pickCodeOrigin(
            openFileNames = manager.openFiles.map { it.name },
            wantedName = wanted,
            diskFileExists = diskFile?.isFile == true,
        )
        val document = if (origin == CodeOrigin.EDITOR) {
            open?.let { vf -> manager.getEditors(vf).firstNotNullOfOrNull { (it as? TextEditor)?.editor } }?.document
        } else {
            null
        }
        return when {
            document != null -> Probe(
                origin = CodeOrigin.EDITOR,
                signature = "editor:${open?.path}:${document.modificationStamp}:${document.textLength}",
                document = document,
                fileName = open?.name,
            )

            // 编辑器里那份可能压根不是文本编辑器（diff / 二进制）：那就退回磁盘那份
            origin == CodeOrigin.EDITOR || origin == CodeOrigin.DISK -> Probe(
                origin = CodeOrigin.DISK,
                signature = "disk:${diskFile?.path}:${diskFile?.length()}:${diskFile?.lastModified()}",
                document = null,
                fileName = wanted,
            )

            else -> Probe(CodeOrigin.MISSING, "missing:$pid", null, null)
        }
    }

    /**
     * 真去读那份代码。编辑器那份现取文档文本；
     * 磁盘那份用 `java.io` 读 —— 走到这一步说明它没在编辑器里打开，磁盘就是唯一真相，
     * 也省掉 EDT 上取 `Document` 需要的读动作（1.7.0 在别处踩过那次断言）。
     */
    private fun readCode(probe: Probe): SubmissionCode? = when (probe.origin) {
        CodeOrigin.EDITOR -> probe.document?.text?.let {
            SubmissionCode(it, "编辑器里打开的 ${probe.fileName}（含未保存的修改）")
        }

        CodeOrigin.DISK -> {
            val file = probe.fileName?.let { name -> project.basePath?.let { File(it, name) } } ?: return null
            val text = runCatching { file.readText() }.getOrNull() ?: return null
            SubmissionCode(text, "项目根的 ${file.name}（编辑器里没打开它）")
        }

        CodeOrigin.MISSING -> null
    }

    /** 出错染成错误色 —— 规则在 [StatusRow]，五个面板共用一份。 */
    private fun setStatus(text: String, error: Boolean = false) = StatusRow.apply(statusLabel, text, error)

    /**
     * 重看一次。[force] = false（定时器那条路）时签名没变就直接返回，
     * 免得每秒把滚动位置抢回顶部。
     */
    private fun refreshPreview(force: Boolean) {
        if (project.isDisposed) return
        syncPidFromEditor()
        val pid = pidField.text.trim()
        if (!LuoguPidValidator.isValidPid(pid)) {
            currentCode = ""
            shownSignature = null
            showPreview("")
            setStatus("题号格式无效（形如 P1001）", error = true)
            return
        }
        val probe = probe(pid)
        if (!force && probe.signature == shownSignature) return
        shownSignature = probe.signature
        val code = readCode(probe)
        if (code == null) {
            currentCode = ""
            showPreview("")
            setStatus(
                if (probe.origin == CodeOrigin.MISSING) {
                    "找不到 $pid.cpp：编辑器里没打开，项目根也没有这个文件"
                } else {
                    "读不到 $pid.cpp（刚被删掉，或者没有读权限）"
                },
                error = true,
            )
            return
        }
        currentCode = code.text
        showPreview(code.text)
        setStatus(
            "将提交 $pid · ${countLines(code.text)} 行 · 来源：${code.source}",
            error = code.text.isBlank(),
        )
    }

    /** 换预览内容，同时保住视口滚动位置（每秒刷一次，老跳回顶部会烦死人）。 */
    private fun showPreview(text: String) {
        if (previewArea.text == text) return
        val viewport: JViewport = previewScroll.viewport
        val previous = viewport.viewPosition
        previewArea.text = text
        val maxY = (previewArea.height - viewport.height).coerceAtLeast(0)
        val maxX = (previewArea.width - viewport.width).coerceAtLeast(0)
        viewport.viewPosition = Point(previous.x.coerceIn(0, maxX), previous.y.coerceIn(0, maxY))
    }

    private fun doSubmit() {
        val pid = pidField.text.trim()
        if (!LuoguPidValidator.isValidPid(pid)) {
            setStatus("题号格式无效", error = true)
            return
        }
        val langName = (langCombo.selectedItem as? String) ?: run {
            setStatus("未知语言版本", error = true)
            return
        }
        val lang = LuoguApiService.LANGUAGES[langName] ?: run {
            setStatus("未知语言版本", error = true)
            return
        }
        // 强制重看一次：交的一定是刚刚那一份，不是 1 秒前的缓存
        refreshPreview(force = true)
        if (currentCode.isBlank()) {
            // 原因 refreshPreview 已经写进状态行了，不再重复一遍
            return
        }
        val code = currentCode

        setStatus("提交中…")
        LuoguActions.submit(
            project = project,
            pid = pid,
            lang = lang,
            code = code,
            captchaPrompter = { CaptchaPrompt.promptCaptcha(project, it) },
            onResult = { rid ->
                setStatus("已提交到 $pid，记录 id=$rid")
                onSubmitted(pid, rid, langName, code)
            },
            onError = { msg -> setStatus(msg, error = true) },
        )
    }

    private fun guessedPid(): String? = CurrentFilePid.guess(project)

    companion object {

        /** 预览自动重看的间隔。 */
        private const val REFRESH_MS = 1000

        /** 行数（空串算 0）。 */
        @JvmStatic
        fun countLines(text: String): Int = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1

        /**
         * 提交页取哪一份代码的判定（纯函数，探针直接断言）。
         *
         * 顺序是**按题号在所有已打开文件里找**，不是只看当前选中的那个编辑器——
         * 只看选中项会让「编辑器明明开着 `P1001.cpp`、但当前标签是 `main.cpp`」判成没打开。
         * 都没有再看项目根的 `Pxxx.cpp`。文件名比较忽略大小写（大小写不敏感的卷上会差）。
         */
        @JvmStatic
        fun pickCodeOrigin(
            openFileNames: List<String>,
            wantedName: String,
            diskFileExists: Boolean,
        ): CodeOrigin = when {
            openFileNames.any { it.equals(wantedName, ignoreCase = true) } -> CodeOrigin.EDITOR
            diskFileExists -> CodeOrigin.DISK
            else -> CodeOrigin.MISSING
        }
    }
}

/** [SubmitPanel.pickCodeOrigin] 的结果。 */
enum class CodeOrigin { EDITOR, DISK, MISSING }
