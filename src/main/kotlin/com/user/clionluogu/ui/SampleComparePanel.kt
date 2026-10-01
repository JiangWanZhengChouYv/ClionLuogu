package com.user.clionluogu.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.user.clionluogu.api.LuoguPidValidator
import com.user.clionluogu.service.CompareTarget
import com.user.clionluogu.service.CompilerService
import com.user.clionluogu.service.LuoguActions
import com.user.clionluogu.service.SampleCompareService
import com.user.clionluogu.service.SampleCompareService.Verdict
import com.user.clionluogu.service.SampleDiff
import com.user.clionluogu.service.SampleSetService
import com.user.clionluogu.settings.LuoguSettings
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.io.File
import java.nio.file.Files
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ListSelectionModel

/**
 * 「对拍」页签：**现场编译** `Pxxx.cpp`，再把拉题落盘的样例一组组喂进去比对。
 *
 * 不做的事都有理由：不找 `cmake-build` 里的产物（产物名取决于 target，还要求先手动 Build，
 * 实测经常找不到）、不复用 CLion 的 CMake 工具链（那是 bundled module，依赖它就把插件绑死在 CLion）、
 * 不弹任何模态窗（跑的是刚编出来的东西，没有「产物比源码旧」这种情况要问）。
 *
 * 编辑器里那份没保存的话，点「编译并对拍」会**先保存那一个文件**再编，状态栏如实写出来。
 */
class SampleComparePanel(private val project: Project) : JPanel(BorderLayout()) {

    /** 列表里的一行。[sampleIndex] 为 [COMPILE_ROW] 时代表编译本身。 */
    private class Row(
        val sampleIndex: Int,
        val title: String,
        var verdict: Verdict?,
        var summary: String,
        var detail: String,
    )

    private val pidField = JBTextField()
    private val reprobeButton = JButton("重新查找样例")
    private val pickCompilerButton = JButton("换编译器…")
    private val compilerLabel = JBLabel(" ")
    private val warnLabel = JBLabel(" ")
    private val startButton = JButton("编译并对拍")
    private val stopButton = JButton("停止")
    private val fetchButton = JButton("去拉取")
    private val statusLabel = JBLabel(" ")

    private val listModel = DefaultListModel<Row>()
    private val resultList = JBList<Row>(listModel)
    private val detailArea = JTextArea()

    private var target: CompareTarget? = null
    private var targetPid: String? = null
    private var autoPid: String? = null
    private var task: SampleCompareService.CompareTask? = null
    private var probing = false
    private var running = false

    /** 本轮该跑几组：中途停止时用它区分「全过」与「已跑的都过」。 */
    private var expectedGroups = 0

    /** 编译产物目录：每个窗口一份，退出即删。不放项目根，避免污染仓库或被 AC 清理误删。 */
    private val buildDir: File by lazy {
        runCatching { Files.createTempDirectory("clionluogu-compare").toFile() }
            .getOrElse { File(System.getProperty("java.io.tmpdir"), "clionluogu-compare") }
            .also { it.mkdirs(); it.deleteOnExit() }
    }

    init {
        // 侧边栏很窄：每行只放一个组件、整行铺满；必须并排的按钮用 WrapLayout
        // （FlowLayout 的 preferredSize 只算单行高度，折出去的第二行会被整块裁掉）
        val buttonRow = JPanel(WrapLayout(FlowLayout.LEFT, 6, 4))
        buttonRow.add(reprobeButton)
        buttonRow.add(pickCompilerButton)

        val north = JPanel(GridBagLayout())
        north.border = JBUI.Borders.empty(8)
        var row = -1
        fun addNorth(component: JComponent) {
            row++
            val gbc = GridBagConstraints().apply {
                gridx = 0
                gridy = row
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                insets = JBUI.insets(2)
            }
            north.add(component, gbc)
        }
        addNorth(JBLabel("题号（空=跟当前文件）"))
        addNorth(pidField)
        addNorth(buttonRow)
        addNorth(compilerLabel)
        addNorth(warnLabel)
        add(north, BorderLayout.NORTH)

        resultList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        resultList.cellRenderer = object : ColoredListCellRenderer<Row>() {
            override fun customizeCellRenderer(
                list: JList<out Row>,
                value: Row,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                icon = VerdictSquareIcon(verdictColorOf(statusCodeOf(value.verdict)))
                append(value.title)
                if (value.summary.isNotBlank()) {
                    append("  ${value.summary}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
        resultList.addListSelectionListener {
            val idx = resultList.selectedIndex
            if (idx in 0 until listModel.size()) detailArea.text = listModel.elementAt(idx).detail
        }

        detailArea.isEditable = false
        detailArea.lineWrap = false
        detailArea.font = Font(Font.MONOSPACED, Font.PLAIN, detailArea.font.size)
        detailArea.border = JBUI.Borders.empty(6)

        val split = JBSplitter(false, 0.35f).apply {
            firstComponent = JBScrollPane(resultList)
            secondComponent = JBScrollPane(detailArea)
            border = JBUI.Borders.empty(4)
        }
        add(split, BorderLayout.CENTER)

        stopButton.isEnabled = false
        startButton.isEnabled = false
        fetchButton.isVisible = false
        val actionRow = JPanel(WrapLayout(FlowLayout.LEFT, 6, 4))
        actionRow.add(startButton)
        actionRow.add(stopButton)
        actionRow.add(fetchButton)

        val bottom = JPanel(GridBagLayout())
        bottom.border = JBUI.Borders.empty(4, 8)
        listOf<JComponent>(actionRow, statusLabel).forEachIndexed { i, comp ->
            bottom.add(
                comp,
                GridBagConstraints().apply {
                    gridx = 0
                    gridy = i
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    insets = JBUI.insets(2)
                },
            )
        }
        add(bottom, BorderLayout.SOUTH)

        reprobeButton.addActionListener { probe() }
        pidField.addActionListener { probe() }
        // 输完题号点个地方就开始分析，不必再回去按「重新查找」
        pidField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) = probe()
        })
        pickCompilerButton.addActionListener { pickCompiler() }
        startButton.addActionListener { startCompare() }
        stopButton.addActionListener { stopCompare() }
        fetchButton.addActionListener { LuoguTabs.openTab(project, LuoguTabs.TAB_FETCH) }
    }

    /** 切到本页签就重新探测：编译器与样例目录都可能刚变，不该要求手动点。 */
    override fun addNotify() {
        super.addNotify()
        if (!running) probe()
    }

    // ---- 探测 ----

    private fun currentPid(): String = pidField.text.trim()

    /**
     * 题号跟着当前编辑器走：只在输入框为空、或里面仍是上次自动填的那个值时覆盖——
     * 手输的题号不会被切页签冲掉。
     */
    private fun syncPidFromEditor() {
        val guess = CurrentFilePid.guess(project) ?: return
        val typed = currentPid()
        if (typed.isEmpty() || typed == autoPid) {
            pidField.text = guess
            autoPid = guess
        }
    }

    private fun probe() {
        if (probing || running) return
        syncPidFromEditor()
        val pid = currentPid()
        if (!LuoguPidValidator.isValidPid(pid)) {
            startButton.isEnabled = false
            statusLabel.text = if (pid.isEmpty()) {
                "先在编辑器里打开 Pxxx.cpp，或在上面输入题号（形如 P1001）"
            } else {
                "题号格式无效（形如 P1001 / B2001）"
            }
            return
        }
        probing = true
        statusLabel.text = "查找样例与编译器中…"
        LuoguActions.probeCompareTarget(
            project = project,
            pid = pid,
            onResult = { result ->
                probing = false
                if (!project.isDisposed) applyTarget(pid, result)
            },
        )
    }

    private fun applyTarget(pid: String, t: CompareTarget) {
        target = t
        targetPid = pid
        updateCompilerLabel(t)

        listModel.clear()
        t.samples?.let { found ->
            found.samples.forEach { sample ->
                listModel.addElement(Row(sample.index, "样例 ${sample.index}", null, "待对拍", pendingDetail(sample)))
            }
            found.orphanInputIndexes.forEach { n ->
                listModel.addElement(
                    Row(
                        n,
                        "样例 $n",
                        null,
                        "缺少期望输出",
                        "样例 $n\n\n这一组只有输入没有期望输出，不会参与对拍。\n" +
                            "现有文件：${pid}_${n}.in\n补上 ${pid}_${n}.out 后点「重新查找样例」。",
                    ),
                )
            }
        }

        val blocked = blockingReason(pid, t, project.basePath)
        fetchButton.isVisible = t.samples?.let { !it.dirExists } == true
        if (blocked != null) {
            statusLabel.text = blocked.short
            detailArea.text = blocked.detail
        } else {
            statusLabel.text = "共 ${t.samples?.samples?.size ?: 0} 组样例，可以开始编译对拍"
            detailArea.text = introText(pid, t)
        }
        startButton.isEnabled = blocked == null && !running
    }

    private fun introText(pid: String, t: CompareTarget): String = buildString {
        append("题目：").append(pid).append('\n')
        append("源文件：").append(t.sourcePath).append('\n')
        append("编译器：").append(t.compiler?.file?.absolutePath).append('\n')
        t.compiler?.versionLine?.let { append("版本：").append(it).append('\n') }
        append("编译参数：").append(LuoguSettings.getInstance().compareCompilerArgs).append('\n')
        append("样例目录：").append(t.samples?.dirPath).append('\n')
        append("产物：").append(outputFile(pid)).append('\n')
        append("每组运行时限：").append(SampleCompareService.DEFAULT_TIMEOUT_MS).append(" ms\n\n")
        append("点「编译并对拍」会先编译再逐组比对；左侧点某一行看该组的差异上下文。\n")
        append("注意：这里只用命令行编译器，不复用你项目里的 CMake 配置（自定义宏、多文件、第三方库不认）。")
    }

    private fun pendingDetail(sample: SampleSetService.Sample): String =
        "样例 ${sample.index}\n输入：${sample.inFile.path}\n期望：${sample.expectedFile.path}\n\n还没跑。"

    private fun updateCompilerLabel(t: CompareTarget) {
        val compiler = t.compiler
        if (compiler == null) {
            compilerLabel.foreground = UIUtil.getLabelForeground()
            compilerLabel.toolTipText = null
            compilerLabel.text = "编译器：未找到"
            warnLabel.text = t.compilerNotice ?: "点「换编译器…」指定一个 clang++/g++"
            warnLabel.foreground = Color(0xE0, 0x80, 0x00)
            return
        }
        compilerLabel.text = "编译器：${compiler.display()}"
        compilerLabel.foreground = UIUtil.getLabelForeground()
        compilerLabel.toolTipText = "${compiler.file.absolutePath}\n${compiler.versionLine.orEmpty()}"
        val notice = t.compilerNotice
        warnLabel.text = notice.orEmpty()
        warnLabel.foreground = if (notice == null) UIUtil.getLabelForeground() else Color(0xE0, 0x80, 0x00)
    }

    // ---- 换编译器 ----

    private fun pickCompiler() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileOrExecutableAppDescriptor()
            .withTitle("选择 C++ 编译器（clang++ / g++）")
        val toSelect = runCatching {
            target?.compiler?.file?.parentFile?.let { LocalFileSystem.getInstance().findFileByIoFile(it) }
        }.getOrNull()
        val chosen = FileChooser.chooseFile(descriptor, project, toSelect) ?: return
        val file = File(chosen.path)
        if (!file.isFile || !file.canExecute()) {
            statusLabel.text = "选中的文件不可执行：${file.path}"
            return
        }
        // 编译器是机器级的事，存应用级设置；填了绝对路径就不再自动找
        LuoguSettings.getInstance().compareCompilerPath = file.absolutePath
        probe()
    }

    // ---- 编译 + 对拍 ----

    private fun outputName(pid: String): String = if (isWindows()) "$pid.exe" else pid

    private fun outputFile(pid: String): String = File(buildDir, outputName(pid)).path

    private fun startCompare() {
        val pid = currentPid()
        if (!LuoguPidValidator.isValidPid(pid)) {
            statusLabel.text = "题号格式无效"
            return
        }
        // 分析在后台线程：题号改过或还没出结果时不能拿上一轮的东西去编去跑
        val t = target
        if (t == null || targetPid != pid) {
            statusLabel.text = if (t == null) "正在查找样例与编译器，稍等再点" else "题号刚改过，正在按新题号重新分析…"
            probe()
            return
        }
        val samples = t.samples?.samples.orEmpty()
        if (samples.isEmpty()) {
            statusLabel.text = "没有成对的 .in/.out 样例"
            return
        }
        val sourcePath = t.sourcePath ?: run {
            statusLabel.text = "项目根没有 $pid.cpp"
            return
        }
        val compiler = t.compiler ?: run {
            statusLabel.text = "没找到编译器，点「换编译器…」指定一个"
            return
        }
        val settings = LuoguSettings.getInstance()

        // 编译读的是磁盘内容：先把编辑器里那份落盘（只保存这一个文件）
        val savedNote = saveDocumentIfModified(File(sourcePath))

        running = true
        expectedGroups = samples.size
        startButton.isEnabled = false
        stopButton.isEnabled = true
        fetchButton.isVisible = false
        statusLabel.text = listOfNotNull(savedNote, "编译中…").joinToString(" ")
        detailArea.text = ""

        val output = File(buildDir, outputName(pid))
        val request = SampleCompareService.Request(
            pid = pid,
            exe = output,
            workDir = File(t.projectBasePath ?: project.basePath ?: buildDir.path),
            samples = samples,
            pathEntries = CompilerService.runtimePathEntries(compiler.file),
            compile = SampleCompareService.Compile(
                compiler = compiler.file,
                source = File(sourcePath),
                args = settings.compareCompilerArgs.split(' ').filter { it.isNotBlank() },
            ),
        )
        insertCompileRow()
        task = SampleCompareService.run(
            project = project,
            request = request,
            onProgress = { result -> if (!project.isDisposed) updateRow(result, request) },
            onFinished = { report -> if (!project.isDisposed) finishCompare(report) },
        )
    }

    /** 编辑器里那份没保存就先写盘；真的写了才回一句给用户看。 */
    private fun saveDocumentIfModified(file: File): String? {
        val vf = runCatching { LocalFileSystem.getInstance().findFileByIoFile(file) }.getOrNull() ?: return null
        val manager = FileDocumentManager.getInstance()
        if (!manager.isFileModified(vf)) return null
        val document = manager.getDocument(vf) ?: return null
        ApplicationManager.getApplication().runWriteAction { manager.saveDocument(document) }
        return "已保存 ${vf.name}，"
    }

    private fun insertCompileRow() {
        val row = Row(COMPILE_ROW, "编译", null, "编译中…", "正在编译，稍后点这一行看诊断。")
        val existing = indexOfRow(COMPILE_ROW)
        if (existing == null) listModel.insertElementAt(row, 0) else listModel.set(existing, row)
    }

    private fun stopCompare() {
        statusLabel.text = "正在停止…"
        stopButton.isEnabled = false
        task?.requestStop()
    }

    private fun updateRow(result: SampleCompareService.Result, request: SampleCompareService.Request) {
        val index = indexOfRow(result.sample.index) ?: return
        val row = listModel.elementAt(index)
        row.verdict = result.verdict
        row.summary = summaryOf(result, request)
        row.detail = detailOf(result, request)
        listModel.set(index, row)
        if (resultList.selectedIndex == index) detailArea.text = row.detail
    }

    private fun indexOfRow(sampleIndex: Int): Int? {
        for (i in 0 until listModel.size()) {
            if (listModel.elementAt(i).sampleIndex == sampleIndex) return i
        }
        return null
    }

    private fun finishCompare(report: SampleCompareService.Report) {
        running = false
        task = null
        startButton.isEnabled = true
        stopButton.isEnabled = false

        val compile = report.compile
        val compileIndex = indexOfRow(COMPILE_ROW)
        if (compile != null && compileIndex != null) {
            val row = listModel.elementAt(compileIndex)
            row.verdict = if (compile.ok) Verdict.PASS else Verdict.ERROR
            row.summary = if (compile.ok) "通过 ${compile.elapsedMs} ms" else "编译失败"
            row.detail = compileDetail(compile)
            listModel.set(compileIndex, row)
        }

        if (compile != null && !compile.ok) {
            // 编译没过，其余组统一说明原因，别让人以为是自己代码跑错了
            for (i in 0 until listModel.size()) {
                val row = listModel.elementAt(i)
                if (row.sampleIndex == COMPILE_ROW) continue
                row.verdict = null
                row.summary = "未执行（编译失败）"
                row.detail = "样例 ${row.sampleIndex}\n\n没跑：编译那一行报了错，先看第一行的诊断。"
                listModel.set(i, row)
            }
            statusLabel.text = "编译失败，点第一行看诊断"
            resultList.selectedIndex = compileIndex ?: 0
            detailArea.text = compileDetail(compile)
            return
        }

        for (i in 0 until listModel.size()) {
            val row = listModel.elementAt(i)
            if (row.verdict == null) {
                row.summary = "未执行"
                row.detail = "样例 ${row.sampleIndex}\n\n这一组没跑（已停止，或停止时排在后面）。"
                listModel.set(i, row)
            }
        }

        val failedIndex = (0 until listModel.size()).firstOrNull { i ->
            listModel.elementAt(i).verdict?.let { it != Verdict.PASS } == true
        }
        if (failedIndex != null) resultList.selectedIndex = failedIndex

        val stopped = expectedGroups > report.results.size
        statusLabel.text = when {
            report.ranCount == 0 -> "已停止，没有跑完任何一组"
            report.allPassed && !stopped -> "${report.passedCount} 组全部通过，可以提交了"
            report.allPassed -> "已跑的 ${report.results.size}/$expectedGroups 组都通过（中途停止）"
            else -> "${report.passedCount}/${report.results.size} 组通过，已选中首个失败组"
        }
        if (failedIndex == null && report.results.isNotEmpty()) {
            detailArea.text = report.results.joinToString("\n\n") { r ->
                "样例 ${r.sample.index} · ${verdictText(r.verdict)} · ${r.elapsedMs} ms"
            } + "\n\n产物：${report.exePath}"
        }
    }

    private fun compileDetail(compile: CompilerService.Outcome): String =
        buildString {
            append("编译 ").append(if (compile.ok) "成功" else "失败").append("  用时 ")
                .append(compile.elapsedMs).append(" ms")
            compile.exitCode?.let { append("  退出码 ").append(it) }
            append('\n')
            append("编译器：").append(target?.compiler?.file?.absolutePath ?: "?").append('\n')
            append("命令：").append(compile.commandLine).append('\n')
            append(
                if (compile.shimInjected) {
                    "兼容头：已注入 bits/stdc++.h（该编译器原生没有；洛谷评测机是 g++，自带）\n"
                } else {
                    "兼容头：未注入（该编译器原生支持 bits/stdc++.h）\n"
                },
            )
            if (compile.diagnostic.isNotBlank()) {
                append("\n—— 编译器输出 ——\n").append(compile.diagnostic)
            } else if (compile.ok) {
                append("\n没有任何诊断，可以直接看下面各组的比对结果。")
            }
        }

    // ---- 文案 ----

    private fun summaryOf(result: SampleCompareService.Result, request: SampleCompareService.Request): String =
        when (result.verdict) {
            Verdict.PASS -> "通过 ${result.elapsedMs} ms"
            Verdict.MISMATCH -> result.issue?.let { SampleDiff.summarize(it) } ?: "输出不一致"
            Verdict.RUNTIME_ERROR -> "运行错误（${result.exitCode}）"
            Verdict.TIMEOUT -> "超时 ${request.timeoutMs} ms"
            Verdict.OUTPUT_TOO_MUCH -> "输出超限"
            Verdict.NOT_RUN -> "已取消"
            Verdict.ERROR -> "无法比对"
        }

    private fun verdictText(verdict: Verdict?): String = when (verdict) {
        Verdict.PASS -> "通过"
        Verdict.MISMATCH -> "输出不一致"
        Verdict.RUNTIME_ERROR -> "运行错误"
        Verdict.TIMEOUT -> "超时"
        Verdict.OUTPUT_TOO_MUCH -> "输出过多"
        Verdict.NOT_RUN -> "未执行"
        Verdict.ERROR -> "错误"
        else -> "待对拍"
    }

    private fun detailOf(result: SampleCompareService.Result, request: SampleCompareService.Request): String =
        buildString {
            append("样例 ").append(result.sample.index).append("  ").append(verdictText(result.verdict)).append('\n')
            result.issue?.let { append(SampleDiff.summarize(it)).append('\n') }
            append("用时 ").append(result.elapsedMs).append(" ms")
            result.exitCode?.let { append("，退出码 ").append(it) }
            append('\n')
            result.note?.let { append(it).append('\n') }
            result.expectedPreview?.let { append('\n').append(it) }
            result.actualPreview?.let { append('\n').append(it) }
            result.stderr?.let { append("\n—— stderr ——\n").append(it).append('\n') }
            append("\n输入：").append(result.sample.inFile.path).append('\n')
            append("期望：").append(result.sample.expectedFile.path).append('\n')
            append("产物：").append(request.exe.absolutePath).append('\n')
            append("工作目录：").append(request.workDir.absolutePath)
        }

    /**
     * 判定 → 评测页已有的状态码，颜色就与「评测」页签完全一致（不改 `VerdictColors`）。
     * `ERROR` 借编译错误（14）的红：这一行永远是真的出了错。
     */
    private fun statusCodeOf(verdict: Verdict?): Int? = when (verdict) {
        Verdict.PASS -> 12
        Verdict.MISMATCH -> 6
        Verdict.RUNTIME_ERROR -> 7
        Verdict.TIMEOUT -> 5
        Verdict.OUTPUT_TOO_MUCH -> 3
        Verdict.ERROR -> 14
        else -> null
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)

    companion object {
        /** 列表里代表「编译本身」那一行的编号（样例编号从 1 起，不会撞）。 */
        private const val COMPILE_ROW = 0

        /**
         * 「还不能开始」的原因；返回 null 表示可以开始。
         *
         * 单独抽成不碰 Swing / `Project` 的纯函数，是因为这些分支全是「可空字段在不在」的判断，
         * 判反了不会编译报错、只会在界面上把存在的文件说成找不到（真发生过）。抽出来才能离线断言。
         */
        @JvmStatic
        fun blockingReason(pid: String, t: CompareTarget, fallbackBase: String?): Block? {
            val found = t.samples
            if (found == null) {
                return Block(
                    "当前项目没有落盘目录",
                    "新建但还没保存到磁盘的项目没有样例，也没有源文件。\n" +
                        "先把项目存到磁盘上，再回来点「重新查找样例」。",
                )
            }
            if (!found.dirExists) {
                return Block(
                    "没有样例目录",
                    "还没拉过 $pid 的样例。\n\n插件认的目录是：\n    ${found.dirPath}\n\n" +
                        "里面需要形如 ${pid}_1.in / ${pid}_1.out 的成对文件（拉题时会自动生成）。\n" +
                        "点下方「去拉取」到「拉取」页生成，回来再点「重新查找样例」。",
                )
            }
            if (found.samples.isEmpty()) {
                return Block(
                    "样例目录里没有成对的 .in/.out",
                    "目录存在但跑不了：\n    ${found.dirPath}\n\n" +
                        "需要同名成对的 ${pid}_N.in 与 ${pid}_N.out 才算一组（N 是编号）。" +
                        if (found.orphanInputIndexes.isNotEmpty()) {
                            "\n只有 .in 的编号：${found.orphanInputIndexes.joinToString()}"
                        } else {
                            ""
                        },
                )
            }
            if (t.sourcePath == null) {
                val looked = File(t.projectBasePath ?: fallbackBase ?: "", "$pid.cpp").path
                return Block(
                    "项目根没有 $pid.cpp",
                    "没东西可编。\n\n我找的是项目根下的：\n    $looked\n\n" +
                        "改过名或放进子目录的，插件不会去猜——编译哪个文件必须明确（猜错就是在编别的代码）。\n" +
                        "把它放回项目根并命名为 $pid.cpp，或者在题号框里填那个文件名对应的题号。",
                )
            }
            if (t.compiler == null) {
                return Block(
                    "没找到编译器",
                    "这台机器上没找到 C++ 编译器。\n\n插件按这些顺序找：\n" +
                        "1. 设置里填的绝对路径\n2. PATH 上的 clang++ / g++ / c++\n" +
                        "3. IDE 自带的 MinGW（Windows：bin/mingw/bin/g++.exe）\n\n" +
                        "在 `Settings | Tools | 洛谷拉题` 里填一个编译器绝对路径，或点「换编译器…」选一次。",
                )
            }
            return null
        }
    }

    /** 一条「还不能开始」的说明：短句进状态栏，长文进详情区。 */
    data class Block(val short: String, val detail: String)
}
