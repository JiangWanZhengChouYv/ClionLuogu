package com.user.clionluogu.ui

import com.intellij.icons.AllIcons
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
import com.user.clionluogu.service.CompileErrorLocator
import com.user.clionluogu.service.CompileHit
import com.user.clionluogu.service.CompilerService
import com.user.clionluogu.service.JumpMiss
import com.user.clionluogu.service.LocalRunSignature
import com.user.clionluogu.service.LuoguActions
import com.user.clionluogu.service.SampleCompareService
import com.user.clionluogu.service.SampleCompareService.Verdict
import com.user.clionluogu.service.ResourceMeter
import com.user.clionluogu.service.SampleDiff
import com.user.clionluogu.service.ProblemLimits
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
import javax.swing.Timer
import javax.swing.ScrollPaneConstants

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
    private val timeField = JBTextField()
    private val memoryField = JBTextField()
    private val pickCompilerButton = JButton("换编译器…")
    private val compilerLabel = JBLabel(" ")
    private val warnLabel = JBLabel(" ")
    private val startButton = JButton("编译并对拍")
    private val stopButton = JButton("停止")
    private val fetchButton = JButton("去拉取")
    private val exportButton = JButton("存反例")
    private val jumpButton = JButton(JUMP_LABEL)
    private val statusLabel = JBLabel(" ")

    private val listModel = DefaultListModel<Row>()
    private val resultList = JBList<Row>(listModel)
    private val detail = DetailPanel()

    /** 当前详情区的原文：跳行按钮解析的就是它（渲染成分节之后，文本仍留一份）。 */
    private var detailText = ""

    /** 过滤与计数：`listModel` 只是 `allRows` 的一个视图（只看失败时过滤掉通过的组）。 */
    private val allRows = mutableListOf<Row>()
    private val onlyFailedBox = JBCheckBox("只看失败")
    private val countLabel = JBLabel(" ")

    private var target: CompareTarget? = null
    private var targetPid: String? = null
    private var autoPid: String? = null

    /** 他自己动过时空限制之后，就不再用题面值覆盖他的输入。 */
    private var limitsTouched = false

    /** 上一次探测时磁盘的现况；每秒比对一次，变了就重探（拉完题不用再手点）。 */
    private var diskSignature: String? = null

    private val ticker = Timer(TICK_MS) { tickDisk() }
    private var task: SampleCompareService.CompareTask? = null
    private var probing = false
    private var running = false

    /** 本轮该跑几组：中途停止时用它区分「全过」与「已跑的都过」。 */
    private var expectedGroups = 0

    /** 上一轮跑完的汇总。重新探测（切页签、点列表）不算新一轮，得把它继续显示出来。 */
    private var lastSummary: String? = null

    /** 当前选中行的诊断里解析出的跳转目标；null 表示这一行没有可跳的位置。 */
    private var pendingJump: CompileHit? = null

    /** 编译产物目录：每个窗口一份，退出即删。不放项目根，避免污染仓库或被 AC 清理误删。 */
    private val buildDir: File by lazy {
        runCatching { Files.createTempDirectory("clionluogu-compare").toFile() }
            .getOrElse { File(System.getProperty("java.io.tmpdir"), "clionluogu-compare") }
            .also { it.mkdirs(); it.deleteOnExit() }
    }

    init {
        pidField.emptyText.appendText("空 = 跟当前文件")
        timeField.columns = 5
        timeField.emptyText.appendText("ms")
        memoryField.columns = 5
        memoryField.emptyText.appendText("MB")
        // 一敲字就算改过：只标失焦的话，中途那次「磁盘签名变了 → 自动重探」会把题面值盖回来，
        // 看起来就是「时限改了没用」（他就是这么报的）
        LimitFields.markWhenTyped(timeField) { limitsTouched = true }
        LimitFields.markWhenTyped(memoryField) { limitsTouched = true }
        // 回车 = 认了这个数字，不必再点到别处
        timeField.addActionListener { commitLimits() }
        memoryField.addActionListener { commitLimits() }
        // 底部窗口矮，头部绝不能一行一件：题号、时空上限、编译器、两个按钮全排一行。
        // 用 WrapLayout 而不是 FlowLayout —— 后者算 preferredSize 只算单行高度，
        // 窗口被拖窄时折出去的第二行会被整块裁掉（1.7.1/1.7.2 真踩过两次）。
        val north = JPanel(WrapLayout(FlowLayout.LEFT, 6, 2)).apply {
            border = JBUI.Borders.empty(4, 8, 0, 8)
        }
        JBLabel("题号").let { north.add(it) }
        north.add(pidField)
        north.add(JBLabel("时限"))
        north.add(timeField)
        north.add(JBLabel("内存"))
        north.add(memoryField)
        north.add(compilerLabel)
        north.add(warnLabel)
        north.add(pickCompilerButton)
        add(north, BorderLayout.NORTH)

        resultList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        resultList.emptyText.appendText("还没有结果：点下面「编译并对拍」")
        resultList.cellRenderer = object : ColoredListCellRenderer<Row>() {
            override fun customizeCellRenderer(
                list: JList<out Row>,
                value: Row,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                icon = VerdictSquareIcon(verdictColorOf(statusCodeOf(value.verdict)), hollow = value.verdict == null)
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
            showDetail(row?.detail.orEmpty())
            exportButton.isEnabled = row?.result != null
        }
        onlyFailedBox.addActionListener { rebuildList() }

        val listPane = JPanel(BorderLayout())
        val filterRow = JPanel(WrapLayout(FlowLayout.LEFT, 6, 2))
        filterRow.add(onlyFailedBox)
        filterRow.add(countLabel)
        listPane.add(filterRow, BorderLayout.NORTH)
        listPane.add(JBScrollPane(resultList), BorderLayout.CENTER)

        val split = AutoFlipSplitter(0.35f).apply {
            firstComponent = listPane
            secondComponent = JBScrollPane(
                detail,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
            )
            border = JBUI.Borders.empty(4)
        }
        add(split, BorderLayout.CENTER)

        stopButton.isEnabled = false
        startButton.isEnabled = false
        exportButton.isEnabled = false
        exportButton.toolTipText = "把选中那一组的输入、期望输出与实际输出写到项目根的 Pxxx_cases/ 里（只有失败的组能存）"
        jumpButton.isEnabled = false
        jumpButton.toolTipText = "打开项目根的 Pxxx.cpp 并定位到诊断里的第一条错误；本地文件不在或行号超出代码长度时不出现"
        // 图标只用来让动作一眼可辨；主 CTA（编译并对拍）保持纯文字按钮
        pickCompilerButton.icon = AllIcons.General.Settings
        stopButton.icon = AllIcons.Actions.Cancel
        exportButton.icon = AllIcons.General.Add
        jumpButton.icon = AllIcons.Actions.NextOccurence
        fetchButton.icon = AllIcons.General.ChevronRight
        fetchButton.isVisible = false
        val actionRow = JPanel(WrapLayout(FlowLayout.LEFT, 6, 4))
        actionRow.add(startButton)
        actionRow.add(stopButton)
        actionRow.add(exportButton)
        actionRow.add(jumpButton)
        actionRow.add(fetchButton)

        // 按钮与状态行同一条 WrapLayout：矮窗口里每一行都是钱
        actionRow.add(statusLabel)
        val bottom = JPanel(WrapLayout(FlowLayout.LEFT, 6, 2))
        bottom.border = JBUI.Borders.empty(2, 8, 4, 8)
        bottom.add(actionRow)
        add(bottom, BorderLayout.SOUTH)

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
        jumpButton.addActionListener { jumpToError() }
        fetchButton.addActionListener { LuoguTabs.openTab(project, LuoguTabs.TAB_FETCH) }
    }

    /** 切到本页签就重新探测：编译器与样例目录都可能刚变，不该要求手动点。 */
    override fun addNotify() {
        super.addNotify()
        if (!running) probe()
        ticker.start()
    }

    /** 面板离开容器就停表：不该在背后空转。 */
    override fun removeNotify() {
        ticker.stop()
        super.removeNotify()
    }

    /**
     * 磁盘变了就重探。
     *
     * 「刚拉完题」这件事和这里之间没有事件，而工具窗口 Content 是缓存的（`addNotify` 不一定再来一次），
     * 所以只能自己盯：源文件长度 / mtime 或样例目录有变 → 重探，界面就不再挂着「项目根没有 Pxxx.cpp」。
     */
    private fun tickDisk() {
        if (running || probing || project.isDisposed) return
        val pid = currentPid()
        if (!LuoguPidValidator.isValidPid(pid)) return
        val signature = LocalRunSignature.of(
            LocalRunSignature.sourceFile(project.basePath, pid),
            LocalRunSignature.samplesDir(project.basePath, pid),
        )
        if (signature != diskSignature || target == null) probe()
    }

    /** 上限改完（回车或失焦）：认这个数字，并把新值反映到提示与状态行。 */
    private fun commitLimits() {
        limitsTouched = true
        target?.let { syncLimitHints(it) }
        val ms = ProblemLimits.parseTimeMs(timeField.text, SampleCompareService.DEFAULT_TIMEOUT_MS)
        val mb = ProblemLimits.parseMemoryMb(memoryField.text)
        statusLabel.text = "时限 $ms ms（不含编译）· " +
            when {
                target?.meter == null -> "内存量不到，那一栏只作显示"
                mb == null -> "不比内存"
                else -> "内存上限 $mb MB"
            }
    }

    /** 把「这道题的限制」和「这台机器量不量得到内存」都写在字段自己的提示上。 */
    private fun syncLimitHints(t: CompareTarget) {
        val problemTime = t.problemLimits.timeMs
        timeField.toolTipText = "每组样例的时限（毫秒），从进程启动算、不含编译（编译另有 120 秒上限）。留空或写错 = 用插件默认 " +
            "${SampleCompareService.DEFAULT_TIMEOUT_MS} ms" +
            (problemTime?.let { "；这道题题面写的是 $it ms" }.orEmpty())
        val problemMemory = t.problemLimits.memoryMb
        memoryField.toolTipText = if (t.meter == null) {
            "这台机器量不到子进程峰值内存（没找到能解析峰值的 time）。" +
                "这里照样可以填（默认取题面），但它只作显示、不参与判定。" +
                (problemMemory?.let { "题面写的是 $it MB。" }.orEmpty())
        } else {
            "本地峰值内存上限（兆），超了判「超内存」。留空 = 不比内存" +
                (problemMemory?.let { "；这道题题面写的是 $it MB" }.orEmpty()) +
                "\n测量方式：${t.meter.tool.path} ${if (t.meter.flavor == ResourceMeter.Flavor.MAC_L) "-l" else "-v"}"
        }
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
                if (!project.isDisposed) {
                    diskSignature = LocalRunSignature.of(
                        LocalRunSignature.sourceFile(result.projectBasePath ?: project.basePath, pid),
                        LocalRunSignature.samplesDir(result.projectBasePath ?: project.basePath, pid),
                    )
                    applyTarget(pid, result)
                }
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
        if (LimitFields.shouldFillFromProblem(limitsTouched)) {
            timeField.text = t.problemLimits.timeMs?.toString().orEmpty()
            memoryField.text = t.problemLimits.memoryMb?.toString().orEmpty()
        }
        syncLimitHints(t)

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
                showDetail(blocked.detail)
            }

            hasResults && lastSummary != null -> {
                // 结果还在就别动详情：用户可能正盯着某个「第 N 行不同」
                statusLabel.text = "上次结果 · $lastSummary"
            }

            else -> {
                statusLabel.text = "共 ${t.samples?.samples?.size ?: 0} 组样例，可以开始编译对拍"
                showDetail(introText(pid, t))
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

    /** 换详情文本，顺带重算「跳到出错行」——所有改详情的地方都走这里，别直接写 [detailText]。 */
    private fun showDetail(text: String) {
        detailText = text
        detail.render(DetailPanel.sectionsFromText(text))
        updateJumpButton()
    }

    /**
     * 按当前详情里的诊断文本刷新「跳到出错行」。
     *
     * 行号上界取本地 `Pxxx.cpp` 的实际行数：诊断里指向模板实例化产物的行号在本地那份文件里
     * 根本不存在，跳过去只会落在文件末尾，不如不给这个按钮。
     */
    private fun updateJumpButton() {
        val pid = currentPid()
        val choice = CompileErrorLocator.choose(
            text = detailText,
            submittedLineCount = localSourceLineCount(pid),
            hasLocalSource = LuoguActions.hasLocalSource(project, pid),
            // 对拍页最清楚自己刚编的是哪个文件，直接把那个文件名交给第①层比对
            localFileName = target?.sourcePath?.let { File(it).name } ?: "$pid.cpp",
        )
        pendingJump = choice.hit
        jumpButton.text = CompileErrorLocator.jumpLabel(choice.hit).ifEmpty { JUMP_LABEL }
        jumpButton.isEnabled = choice.hit != null
        jumpButton.toolTipText = when {
            choice.hit != null ->
                "打开项目根的 $pid.cpp 并定位到第 ${choice.hit.line} 行" + CompileErrorLocator.hitNote(choice)
            // 通过的组 / 非编译行本来就没有诊断，别拿「解析不出位置」吓人
            choice.miss == JumpMiss.NO_DIAGNOSTIC || choice.miss == JumpMiss.NO_LOCATION ->
                "只有编译失败那一行有可跳转的位置（选中列表第一行看看）"
            else -> CompileErrorLocator.missText(choice, pid)
        }
    }

    /** 本地那份源文件的行数；文件读不到（没落盘、没权限）返回 null，表示不卡上界。 */
    private fun localSourceLineCount(pid: String): Int? {
        val base = project.basePath ?: return null
        return runCatching { File(base, "$pid.cpp").readLines().size }.getOrNull()
    }

    /** 打开项目根的 `Pxxx.cpp` 并定位到那条错误。 */
    private fun jumpToError() {
        val hit = pendingJump ?: run {
            statusLabel.text = "这一行没有可跳转的错误位置"
            return
        }
        val pid = currentPid()
        if (!LuoguActions.openProblemFile(project, pid, hit.line, hit.column)) {
            statusLabel.text = "项目根下找不到 $pid.cpp，跳不了"
        }
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
            StatusRow.warn(
                warnLabel,
                t.compilerNotice ?: "点「换编译器…」指定一个 clang++/g++",
            )
            return
        }
        compilerLabel.text = "编译器：${compiler.display()}"
        compilerLabel.foreground = UIUtil.getLabelForeground()
        compilerLabel.toolTipText = "${compiler.file.absolutePath}\n${compiler.versionLine.orEmpty()}"
        val notice = t.compilerNotice
        if (notice == null) StatusRow.clear(warnLabel) else StatusRow.warn(warnLabel, notice)
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

    private fun outputName(pid: String): String = CompilerService.executableName(pid)

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
        showDetail("")
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
            timeoutMs = ProblemLimits.parseTimeMs(timeField.text, SampleCompareService.DEFAULT_TIMEOUT_MS),
            pathEntries = CompilerService.runtimePathEntries(compiler.file),
            memoryLimitMb = ProblemLimits.parseMemoryMb(memoryField.text).takeIf { t.meter != null },
            meter = t.meter,
            compile = SampleCompareService.Compile(
                compiler = compiler.file,
                source = File(sourcePath),
                args = settings.compareCompilerArgList(),
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
    private fun saveDocumentIfModified(file: File): String? =
        LuoguActions.saveIfModified(file)?.let { "已保存 $it，" }

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
        if (selectedRow() === row) showDetail(row.detail)
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
            showDetail(compileDetail(compile))
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
            showDetail(
                report.results.joinToString("\n\n") { r ->
                    "样例 ${r.sample.index} · ${verdictText(r.verdict)} · ${r.elapsedMs} ms"
                } + "\n\n产物：${report.exePath}",
            )
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
            Verdict.MEMORY_LIMIT -> "超内存 ${result.peakMemoryMb ?: "?"} MB / 上限 ${request.memoryLimitMb ?: "?"} MB"
            Verdict.OUTPUT_TOO_MUCH -> "输出超限"
            Verdict.NOT_RUN -> "已取消"
            Verdict.ERROR -> "无法比对"
        }

    private fun verdictText(verdict: Verdict?): String = when (verdict) {
        Verdict.PASS -> "通过"
        Verdict.MISMATCH -> "输出不一致"
        Verdict.RUNTIME_ERROR -> "运行错误"
        Verdict.TIMEOUT -> "超时"
        Verdict.MEMORY_LIMIT -> "超内存"
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
            result.peakMemoryMb?.let { append("，峰值内存 ").append(it).append(" MB") }
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
        Verdict.MEMORY_LIMIT -> 4
        Verdict.OUTPUT_TOO_MUCH -> 3
        Verdict.ERROR -> 14
        else -> null
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)

    companion object {
        /** 列表里代表「编译本身」那一行的编号（样例编号从 1 起，不会撞）。 */
        private const val COMPILE_ROW = 0

        /** 没解析出可跳的行号时的按钮文案。 */
        private const val JUMP_LABEL = "跳到出错行"

        /** 盯磁盘的间隔：与提交页、自测页同一条节奏。 */
        private const val TICK_MS = 1000

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
         * 判反了不会编译报错、只会在界面上把存在的文件说成找不到（真发生过）。
         * 四条文案本身搬到了 [LocalRunGates]（自测页要用去掉样例那一关的同一条链），
         * 这里的**顺序与产出与原来逐字一致**。
         */
        @JvmStatic
        fun blockingReason(pid: String, t: CompareTarget, fallbackBase: String?): Block? =
            LocalRunGates.noProject(t)
                ?: LocalRunGates.samplesOf(pid, t)
                ?: LocalRunGates.noSource(pid, t, fallbackBase)
                ?: LocalRunGates.noCompiler(t)
    }

    /** 一条「还不能开始」的说明：短句进状态栏，长文进详情区。 */
    data class Block(val short: String, val detail: String)
}
