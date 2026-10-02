package com.user.clionluogu.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.user.clionluogu.api.LuoguPidValidator
import com.user.clionluogu.service.CaseExportService
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

    /**
     * 列表里的一行。[sampleIndex] 为 [COMPILE_ROW] 时代表编译本身。
     *
     * [result] 只挂在失败行上，「存反例」要用它里面留的完整 stdout；
     * 行对象同时存在 [allRows]（真数据）与 `listModel`（过滤后的视图）里，是同一实例。
     */
    private class Row(
        val sampleIndex: Int,
        val title: String,
        var verdict: Verdict?,
        var summary: String,
        var detail: String,
        var elapsedMs: Long? = null,
        var result: SampleCompareService.Result? = null,
    )

    private val pidField = JBTextField()
    private val reprobeButton = JButton("重新查找样例")
    private val pickCompilerButton = JButton("换编译器…")
    private val compilerLabel = JBLabel(" ")
    private val warnLabel = JBLabel(" ")
    private val startButton = JButton("编译并对拍")
    private val stopButton = JButton("停止")
    private val fetchButton = JButton("去拉取")
    private val exportButton = JButton("存反例")
    private val statusLabel = JBLabel(" ")

    private val listModel = DefaultListModel<Row>()
    private val resultList = JBList<Row>(listModel)
    private val detailArea = JTextArea()

    /** 过滤与计数：`listModel` 只是 `allRows` 的一个视图（只看失败时过滤掉通过的组）。 */
    private val allRows = mutableListOf<Row>()
    private val onlyFailedBox = JBCheckBox("只看失败")
    private val countLabel = JBLabel(" ")

    private var target: CompareTarget? = null
    private var targetPid: String? = null
    private var autoPid: String? = null
    private var task: SampleCompareService.CompareTask? = null
    private var probing = false
    private var running = false

    /** 本轮该跑几组：中途停止时用它区分「全过」与「已跑的都过」。 */
    private var expectedGroups = 0

    /** 上一轮跑完的汇总。重新探测（切页签、点列表）不算新一轮，得把它继续显示出来。 */
    private var lastSummary: String? = null

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
                // 耗时单列：定宽字段，数字在 UI 字体里等宽，所以能对齐
                value.elapsedMs?.let {
                    append(String.format("  %6d ms", it), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
        resultList.addListSelectionListener {
            val row = selectedRow()
            detailArea.text = row?.detail.orEmpty()
            exportButton.isEnabled = row?.result != null
        }
        onlyFailedBox.addActionListener { rebuildList() }

        detailArea.isEditable = false
        detailArea.lineWrap = false
        detailArea.font = Font(Font.MONOSPACED, Font.PLAIN, detailArea.font.size)
        detailArea.border = JBUI.Borders.empty(6)

        val listPane = JPanel(BorderLayout())
        val filterRow = JPanel(WrapLayout(FlowLayout.LEFT, 6, 2))
        filterRow.add(onlyFailedBox)
        filterRow.add(countLabel)
        listPane.add(filterRow, BorderLayout.NORTH)
        listPane.add(JBScrollPane(resultList), BorderLayout.CENTER)

        val split = AutoFlipSplitter(0.35f).apply {
            firstComponent = listPane
            secondComponent = JBScrollPane(detailArea)
            border = JBUI.Borders.empty(4)
        }
        add(split, BorderLayout.CENTER)

        stopButton.isEnabled = false
        startButton.isEnabled = false
        exportButton.isEnabled = false
        exportButton.toolTipText = "把选中那一组的输入、期望输出与实际输出写到项目根的 Pxxx_cases/ 里（只有失败的组能存）"
        fetchButton.isVisible = false
        val actionRow = JPanel(WrapLayout(FlowLayout.LEFT, 6, 4))
        actionRow.add(startButton)
        actionRow.add(stopButton)
        actionRow.add(exportButton)
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
        pidField.addActionListener { probeIfStale() }
        // 输完题号点个地方就开始分析。但**点列表也会让输入框失焦**，
        // 所以只在题号真的变了时才重探，否则一轮结果会被这次探测清光。
        pidField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) = probeIfStale()
        })
        pickCompilerButton.addActionListener { pickCompiler() }
        startButton.addActionListener { startCompare() }
        stopButton.addActionListener { stopCompare() }
        exportButton.addActionListener { exportCase() }
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

    /** 输入框失焦 / 回车：只有题号变了（或还没分析过）才重探，否则会把跑完的结果清掉。 */
    private fun probeIfStale() {
        syncPidFromEditor()
        if (target == null || !preservesResults(targetPid, currentPid())) probe()
    }

    private fun applyTarget(pid: String, t: CompareTarget) {
        // 同一题号下的重新探测（点列表、切页签、「重新查找样例」）不算新一轮：
        // 先把旧行快照下来，重建时把判定与详情原样搬回来。
        val previous = HashMap<Int, Row>()
        if (preservesResults(targetPid, pid)) {
            allRows.forEach { previous[it.sampleIndex] = it }
        }
        target = t
        targetPid = pid
        updateCompilerLabel(t)

        allRows.clear()
        previous[COMPILE_ROW]?.let { allRows.add(it) }
        fun addRow(index: Int, title: String, summary: String, detail: String) {
            val row = Row(index, title, null, summary, detail)
            previous[index]?.takeIf { it.verdict != null }?.let { kept ->
                row.verdict = kept.verdict
                row.summary = kept.summary
                row.detail = kept.detail
                row.elapsedMs = kept.elapsedMs
                row.result = kept.result
            }
            allRows.add(row)
        }
        t.samples?.let { found ->
            found.samples.forEach { sample ->
                addRow(sample.index, "样例 ${sample.index}", "待对拍", pendingDetail(sample))
            }
            found.orphanInputIndexes.forEach { n ->
                addRow(
                    n,
                    "样例 $n",
                    "缺少期望输出",
                    "样例 $n\n\n这一组只有输入没有期望输出，不会参与对拍。\n" +
                        "现有文件：${pid}_${n}.in\n补上 ${pid}_${n}.out 后点「重新查找样例」。",
                )
            }
        }

        rebuildList()

        val blocked = blockingReason(pid, t, project.basePath)
        fetchButton.isVisible = t.samples?.let { !it.dirExists } == true
        val hasResults = allRows.any { it.verdict != null }
        when {
            blocked != null -> {
                statusLabel.text = blocked.short
                detailArea.text = blocked.detail
            }

            hasResults && lastSummary != null -> {
                // 结果还在就别动详情：用户可能正盯着某个「第 N 行不同」
                statusLabel.text = "上次结果 · $lastSummary"
            }

            else -> {
                statusLabel.text = "共 ${t.samples?.samples?.size ?: 0} 组样例，可以开始编译对拍"
                detailArea.text = introText(pid, t)
            }
        }
        startButton.isEnabled = blocked == null && !running
    }

    /**
     * 让 `listModel` 追上 `allRows`（勾了「只看失败」就只留编译行与失败行），
     * 并尽量保住原本选中的那一组，免得每次刷新结果都跳回第一行。
     */
    private fun rebuildList() {
        val keep = selectedRow()?.sampleIndex
        val visible = visibleRows()
        listModel.clear()
        visible.forEach { listModel.addElement(it) }
        if (keep != null) {
            val idx = visible.indexOfFirst { it.sampleIndex == keep }
            if (idx >= 0) resultList.selectedIndex = idx
        }
        updateCounts()
    }

    private fun visibleRows(): List<Row> =
        if (!onlyFailedBox.isSelected) {
            allRows
        } else {
            allRows.filter { keepsInOnlyFailed(it.verdict, it.sampleIndex == COMPILE_ROW) }
        }

    private fun selectedRow(): Row? {
        val idx = resultList.selectedIndex
        return if (idx in 0 until listModel.size()) listModel.elementAt(idx) else null
    }

    private fun updateCounts() {
        val ran = allRows.count { it.sampleIndex != COMPILE_ROW && it.verdict != null }
        val failed = allRows.count {
            it.sampleIndex != COMPILE_ROW && it.verdict != null && it.verdict != Verdict.PASS
        }
        onlyFailedBox.isEnabled = ran > 0
        countLabel.text = when {
            ran == 0 -> "还没跑"
            failed == 0 -> "$ran 组全通过"
            else -> "已跑 $ran 组 · 失败 $failed 组"
        }
        exportButton.isEnabled = selectedRow()?.result != null
    }

    /** 把选中那一组的输入 / 期望 / 实际写到项目根的 `Pxxx_cases/`，直接喂调试器或本地重跑。 */
    private fun exportCase() {
        val result = selectedRow()?.result ?: run {
            statusLabel.text = "这一组没有可导出的实际输出（没跑、通过、或停在它之前）"
            return
        }
        val pid = currentPid()
        val exported = CaseExportService.export(project, pid, result.sample, result)
        if (exported == null) {
            statusLabel.text = "写不了：项目没有落盘目录，或 ${CaseExportService.casesDirName(pid)}/ 建不出来"
            return
        }
        CaseExportService.notifyExported(project, pid, exported)
        statusLabel.text = "反例已写入 ${exported.dir.name}/（${exported.files.size} 个文件）"
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
        lastSummary = null
        startButton.isEnabled = false
        stopButton.isEnabled = true
        fetchButton.isVisible = false
        statusLabel.text = listOfNotNull(savedNote, "编译中…").joinToString(" ")
        detailArea.text = ""
        // 新一轮开始：先把上一轮的判定清掉，否则跑的过程中新旧混着看不出是哪一轮
        allRows.forEach { row ->
            if (row.sampleIndex != COMPILE_ROW && row.verdict != null) {
                row.verdict = null
                row.summary = "待对拍"
                row.detail = "样例 ${row.sampleIndex}\n\n本轮还没跑到。"
                row.elapsedMs = null
                row.result = null
            }
        }
        rebuildList()

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

    /**
     * 编辑器里那份没保存就先写盘；真的写了才回一句给用户看。
     *
     * `getDocument` 要读权限、`saveDocument` 要写权限，所以整段放进**一个 write action**
     * （写意图自带读意图）。之前在 EDT 上裸调 `getDocument` 会被平台的线程断言拦下
     * （`Read access is allowed from inside read-action only`），2026.2 的 idea.log 里就是这样。
     */
    private fun saveDocumentIfModified(file: File): String? {
        val manager = FileDocumentManager.getInstance()
        var saved: String? = null
        ApplicationManager.getApplication().runWriteAction {
            val vf = runCatching { LocalFileSystem.getInstance().findFileByIoFile(file) }.getOrNull()
                ?: return@runWriteAction
            if (!manager.isFileModified(vf)) return@runWriteAction
            val document = manager.getDocument(vf) ?: return@runWriteAction
            manager.saveDocument(document)
            saved = vf.name
        }
        return saved?.let { "已保存 $it，" }
    }

    private fun insertCompileRow() {
        val existing = allRows.firstOrNull { it.sampleIndex == COMPILE_ROW }
        if (existing == null) {
            allRows.add(0, Row(COMPILE_ROW, "编译", null, "编译中…", "正在编译，稍后点这一行看诊断。"))
        } else {
            existing.verdict = null
            existing.summary = "编译中…"
            existing.detail = "正在编译，稍后点这一行看诊断。"
            existing.elapsedMs = null
            existing.result = null
        }
        rebuildList()
    }

    private fun stopCompare() {
        statusLabel.text = "正在停止…"
        stopButton.isEnabled = false
        task?.requestStop()
    }

    private fun updateRow(result: SampleCompareService.Result, request: SampleCompareService.Request) {
        val row = allRows.firstOrNull { it.sampleIndex == result.sample.index } ?: return
        row.verdict = result.verdict
        row.summary = summaryOf(result, request)
        row.detail = detailOf(result, request)
        row.elapsedMs = result.elapsedMs
        // 只有失败组留完整输出（反例要用）；通过的组不留
        row.result = result.takeIf { it.verdict != Verdict.PASS }
        rebuildList()
        if (selectedRow() === row) detailArea.text = row.detail
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
        val compileRow = allRows.firstOrNull { it.sampleIndex == COMPILE_ROW }
        compile?.let { c ->
            compileRow?.apply {
                verdict = if (c.ok) Verdict.PASS else Verdict.ERROR
                summary = if (c.ok) "通过" else "编译失败"
                detail = compileDetail(c)
                elapsedMs = c.elapsedMs
                // 编译行不给 result：它没有「实际输出」可存，诊断本来就在 detail 里全文放着
                result = null
            }
        }

        if (compile != null && !compile.ok) {
            // 编译没过，其余组统一说明原因，别让人以为是自己代码跑错了
            allRows.forEach { row ->
                if (row.sampleIndex != COMPILE_ROW) {
                    row.verdict = null
                    row.summary = "未执行（编译失败）"
                    row.detail = "样例 ${row.sampleIndex}\n\n没跑：编译那一行报了错，先看第一行的诊断。"
                    row.elapsedMs = null
                    row.result = null
                }
            }
            rebuildList()
            lastSummary = "编译失败，点第一行看诊断"
            statusLabel.text = lastSummary
            indexOfRow(COMPILE_ROW)?.let { resultList.selectedIndex = it }
            detailArea.text = compileDetail(compile)
            return
        }

        allRows.forEach { row ->
            if (row.verdict == null && row.sampleIndex != COMPILE_ROW) {
                row.summary = "未执行"
                row.detail = "样例 ${row.sampleIndex}\n\n这一组没跑（已停止，或停止时排在后面）。"
            }
        }
        rebuildList()

        val firstFailure = allRows.firstOrNull { it.verdict != null && it.verdict != Verdict.PASS }
        if (firstFailure != null) {
            indexOfRow(firstFailure.sampleIndex)?.let { resultList.selectedIndex = it }
        }

        val stopped = expectedGroups > report.results.size
        val summary = when {
            report.ranCount == 0 -> "已停止，没有跑完任何一组"
            report.allPassed && !stopped -> "${report.passedCount} 组全部通过，可以提交了"
            report.allPassed -> "已跑的 ${report.results.size}/$expectedGroups 组都通过（中途停止）"
            else -> "${report.passedCount}/${report.results.size} 组通过，已选中首个失败组"
        }
        lastSummary = summary
        statusLabel.text = summary
        if (firstFailure == null && report.results.isNotEmpty()) {
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
            if (compile.diagnostic.isNotBlank()) {
                append("\n—— 编译器输出 ——\n").append(compile.diagnostic)
            } else if (compile.ok) {
                append("\n没有任何诊断，可以直接看下面各组的比对结果。")
            }
        }

    // ---- 文案 ----

    /** 耗时不写在这里：它单独一列（见 cellRenderer 里的定宽字段）。 */
    private fun summaryOf(result: SampleCompareService.Result, request: SampleCompareService.Request): String =
        when (result.verdict) {
            Verdict.PASS -> "通过"
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

        @JvmStatic
        fun preservesResults(previousPid: String?, currentPid: String): Boolean =
            previousPid != null && previousPid == currentPid

        /**
         * 勾了「只看失败」时，哪些行还得显示。
         *
         * 编译行永远留着（它失败时是唯一线索，成功时也能看到耗时）；
         * 还没跑出结果的行（verdict 为 null，包括「未执行」）不留 —— 它们不是失败，只是没跑。
         */
        @JvmStatic
        fun keepsInOnlyFailed(verdict: Verdict?, isCompileRow: Boolean): Boolean =
            isCompileRow || (verdict != null && verdict != Verdict.PASS)

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
