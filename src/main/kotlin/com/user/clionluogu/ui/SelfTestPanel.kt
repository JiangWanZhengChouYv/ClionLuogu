package com.user.clionluogu.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.user.clionluogu.api.LuoguPidValidator
import com.user.clionluogu.service.CompileErrorLocator
import com.user.clionluogu.service.CompileHit
import com.user.clionluogu.service.CompilerService
import com.user.clionluogu.service.CompareTarget
import com.user.clionluogu.service.LocalRunSignature
import com.user.clionluogu.service.LuoguActions
import com.user.clionluogu.service.ProblemLimits
import com.user.clionluogu.service.ResourceMeter
import com.user.clionluogu.service.SelfTestService
import com.user.clionluogu.settings.LuoguSettings
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.io.File
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.Timer

/**
 * 底部「自测」页签：编现在这份 `Pxxx.cpp`，喂一段自己打的输入，把输出摊开。
 *
 * 它存在的理由是对拍页那句「没有成对的 .in/.out」——那时候连跑一下看看输出都不行，
 * 而调 WA 的第一步恰恰是「我给个输入，看它到底打印什么」。这里**不比期望输出**，只看结果。
 *
 * 排版按「底部窗口宽而矮」来定：**左输入 | 中 stdout | 右概览+stderr+编译诊断**三栏
 * （被拖窄到 460 像素以下时每层各自塌成上下排），头行只有一行，次级动作是链接不是按钮
 * —— 他说过底部太臃肿，又说概览不该和输出挤在同一块里滚动。
 *
 * 三处取舍：
 * - **每次先重编**：不给「用上次产物」（那要靠 mtime 猜产物新旧）。
 * - **输入不落盘**：按题号存进项目级 XML，不新增目录名。
 * - **内存只在量得到时才报**：测量器是实测出来的（见 [ResourceMeter]），量不到就明说不判 MLE。
 */
class SelfTestPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val pidField = JBTextField()
    private val timeField = JBTextField()
    private val memoryField = JBTextField()
    private val inputArea = JBTextArea()
    /** 中间那一栏：只有 stdout（跑完就看它）。 */
    private val stdoutPanel = DetailPanel()

    /** 右边那一栏：概览（题目 / 时限 / 用时 / 退出码 / 峰值内存）+ stderr + 编译诊断。 */
    private val sidePanel = DetailPanel()
    private val runButton = JButton("运行")
    private val stopLink = LinkLabel.create("停止") { stopRun() }
    private val jumpLink = LinkLabel.create(JUMP_LABEL) { jumpToError() }
    private val fetchLink = LinkLabel.create("去拉取这题") { LuoguTabs.openTab(project, LuoguTabs.TAB_FETCH) }
    private val compilerLink = LinkLabel.create("换编译器…") { pickCompiler() }
    private val compilerLabel = JBLabel("编译器：未探测")
    private val statusLabel = JBLabel(" ")

    private var target: CompareTarget? = null
    private var targetPid: String? = null
    private var diskSignature: String? = null
    private var task: SelfTestService.RunTask? = null
    private var running = false

    /** 输入框现在装的是哪道题的内容：换题时按这个键把旧那份存回去。 */
    private var loadedPid: String? = null

    private var shownInput: String = ""

    /** 他自己动过时空限制之后，就不再用题面值覆盖他的输入。 */
    private var limitsTouched = false

    private var pendingJump: CompileHit? = null
    private var lastCompileText: String? = null

    /** 那份诊断属于哪道题：题号切走了就不给跳（详见 [jumpBlockReason]）。 */
    private var lastRunPid: String? = null

    private var autoPid: String? = null

    /**
     * 每秒：跟着当前编辑器换题号 + 比对磁盘签名。
     *
     * 为什么不靠 `addNotify()`：工具窗口 Content 是缓存的（1.7.4 栽过一次）；
     * 而「刚拉完题」这件事和这里之间没有事件 —— 拉完还挂着「项目根没有 Pxxx.cpp」就是这个 bug。
     * 所以盯磁盘签名：源文件长度 / mtime 或样例目录一变就重探。
     */
    private val ticker = Timer(TICK_MS) { tick() }

    private val buildDir: File by lazy {
        runCatching { Files.createTempDirectory("clionluogu-selftest").toFile() }
            .getOrNull() ?: File(System.getProperty("java.io.tmpdir"))
    }

    init {
        guessedPid()?.let { autoPid = it }
        pidField.text = autoPid ?: "P1001"
        pidField.emptyText.appendText("题号，形如 P1001")
        timeField.columns = 5
        timeField.emptyText.appendText("ms")
        memoryField.columns = 5
        memoryField.emptyText.appendText("MB")
        applyPid(pidField.text.trim())

        inputArea.font = DetailPanel.monoFont()
        inputArea.lineWrap = false
        inputArea.border = JBUI.Borders.empty(6)
        inputArea.emptyText.appendText("这里打 stdin（可留空）。整段原样喂进去，程序读完就拿到 EOF")

        val head = JPanel(GridBagLayout())
        head.border = JBUI.Borders.empty(4, 8, 0, 8)
        val gbc = GridBagConstraints().apply {
            gridy = 0
            gridx = 0
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(2, 0, 2, 8)
        }
        head.add(JBLabel("题号"), gbc)
        gbc.gridx++
        gbc.ipadx = 110
        gbc.fill = GridBagConstraints.HORIZONTAL
        head.add(pidField, gbc)
        gbc.ipadx = 0
        gbc.gridx++
        head.add(JBLabel("时间"), gbc)
        gbc.gridx++
        gbc.ipadx = 30
        head.add(timeField, gbc)
        gbc.ipadx = 0
        gbc.gridx++
        head.add(JBLabel("内存"), gbc)
        gbc.gridx++
        gbc.ipadx = 30
        head.add(memoryField, gbc)
        gbc.ipadx = 0
        gbc.gridx++
        gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.NONE
        head.add(compilerLabel, gbc)
        gbc.gridx++
        gbc.weightx = 0.0
        head.add(compilerLink, gbc)

        // 输入 | 输出：底部窗口宽，所以左右排（AutoFlipSplitter 在被拖窄时才换成上下）
        // 三栏：左输入 | 中 stdout | 右概览+stderr+诊断。
        // 每一层都用 AutoFlipSplitter：窗口被拖窄到 460 以下时该层自动改成上下排，
        // 所以三栏在窄栏里会自然塌成堆叠，而不是互相挤到看不见。
        val rightHalf = AutoFlipSplitter(0.5f).apply {
            firstComponent = scroll(stdoutPanel)
            secondComponent = scroll(sidePanel)
        }
        val split = AutoFlipSplitter(0.3f).apply {
            firstComponent = captioned("输入（stdin）", JBScrollPane(inputArea))
            secondComponent = rightHalf
        }
        val actions = JPanel(WrapLayout(FlowLayout.LEFT, 6, 2))
        actions.border = JBUI.Borders.empty(2, 8, 4, 8)
        runButton.icon = AllIcons.Actions.Execute
        stopLink.isVisible = false
        jumpLink.isVisible = false
        fetchLink.isVisible = false
        actions.add(runButton)
        actions.add(stopLink)
        actions.add(jumpLink)
        actions.add(fetchLink)
        actions.add(statusLabel)

        add(head, BorderLayout.NORTH)
        add(split, BorderLayout.CENTER)
        add(actions, BorderLayout.SOUTH)

        pidField.addActionListener { applyPid(pidField.text.trim()) }
        pidField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) = applyPid(pidField.text.trim())
        })
        inputArea.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) = persistInput()
        })
        // **一敲字就算他改过**。原来只在失焦时标记：他在字段里打字的同时，编辑器那边存一次盘
        // 就改了磁盘签名 → 每秒那次自动重探回来用题面值覆盖他的输入，看起来就是「改了没用」。
        LimitFields.markWhenTyped(timeField) { limitsTouched = true }
        LimitFields.markWhenTyped(memoryField) { limitsTouched = true }
        // 回车 = 认了这个数字（和点别处失焦等价），不用非得再去别处点一下
        timeField.addActionListener { commitLimits() }
        memoryField.addActionListener { commitLimits() }
        runButton.addActionListener { startRun() }
    }

    override fun addNotify() {
        super.addNotify()
        syncPidFromEditor()
        ticker.start()
    }

    /** 面板离开容器就停表：不该在背后空转。 */
    override fun removeNotify() {
        ticker.stop()
        persistInput()
        super.removeNotify()
    }

    private fun tick() {
        if (project.isDisposed) return
        syncPidFromEditor()
        // 磁盘签名变了（刚拉完题、文件被删、样例目录动过）就重探 —— 运行中不打扰
        if (!running) {
            val pid = pidField.text.trim()
            if (LuoguPidValidator.isValidPid(pid)) {
                val signature = LocalRunSignature.ofProject(project.basePath, pid)
                if (signature != diskSignature || target == null) probe()
            }
        }
    }

    /** 题号跟着当前编辑器走：手输的不会被切标签冲掉（同提交页那条规则）。 */
    private fun syncPidFromEditor() {
        val guess = CurrentFilePid.guess(project) ?: return
        val typed = pidField.text.trim()
        if (followsEditor(typed, autoPid) && guess != typed) {
            pidField.text = guess
            autoPid = guess
            applyPid(guess)
        }
    }

    /** 换题号：先把当前输入按**旧**题号存回去，再载入新题号那份。题号没变就什么都不做。 */
    private fun applyPid(newPid: String) {
        if (!LuoguPidValidator.isValidPid(newPid)) return
        val previous = loadedPid
        if (previous == newPid) return
        if (previous != null) {
            SelfTestService.saveInput(project, previous, inputArea.text)
        }
        loadedPid = newPid
        inputArea.text = SelfTestService.loadInput(project, newPid)
        shownInput = inputArea.text
        pendingJump = null
        lastCompileText = null
        limitsTouched = false
        diskSignature = null
        updateJumpButton()
        probe()
    }

    private fun persistInput() {
        val pid = loadedPid ?: return
        if (project.isDisposed) return
        val text = inputArea.text
        if (text == shownInput) return
        shownInput = text
        SelfTestService.saveInput(project, pid, text)
    }

    /** 后台探测：编译器、源文件、样例、题面限制、内存测量器。 */
    private fun probe() {
        val pid = pidField.text.trim()
        if (!LuoguPidValidator.isValidPid(pid)) return
        LuoguActions.probeCompareTarget(project, pid) { t ->
            if (project.isDisposed) return@probeCompareTarget
            target = t
            targetPid = pid
            diskSignature = LocalRunSignature.ofProject(t.projectBasePath ?: project.basePath, pid)
            applyTarget(t)
        }
    }

    private fun applyTarget(t: CompareTarget) {
        compilerLabel.text = CompilerLine.text(t.compiler, t.origin, t.gccAdvice)
        compilerLabel.toolTipText = CompilerLine.tooltip(t.compiler, t.origin, t.gccAdvice)
        if (LimitFields.shouldFillFromProblem(limitsTouched)) fillLimitsFromProblem(t)
        syncLimitHints()
        val block = blockingReason(pidField.text.trim(), t, project.basePath)
        runButton.isEnabled = block == null && !running
        runButton.toolTipText = block?.short ?: "先编译项目根的 Pxxx.cpp，再用上面的输入跑一次"
        fetchLink.isVisible = t.sourcePath == null && LuoguPidValidator.isValidPid(pidField.text.trim())
        if (block != null && !running) setStatus(block.short, error = true)
    }

    /** 题面有就主题面的；没有就退回插件默认（时间 [SelfTestService.DEFAULT_TIMEOUT_MS]、内存不限）。 */
    private fun fillLimitsFromProblem(t: CompareTarget) {
        timeField.text = t.problemLimits.timeMs?.toString().orEmpty()
        memoryField.text = t.problemLimits.memoryMb?.toString().orEmpty()
    }

    /** 把「这道题的限制」与「这台机器量不量得到内存」都写在字段本身的提示上。 */
    private fun syncLimitHints() {
        val t = target
        val fromProblem = t?.problemLimits
        timeField.toolTipText = buildString {
            append("本地跑一组的时限（毫秒），**从进程启动算，不含编译**（编译另有 120 秒上限）。留空或写错 = 用插件默认 ")
            append(SelfTestService.DEFAULT_TIMEOUT_MS)
            append(" ms")
            fromProblem?.timeMs?.let { append("；这道题题面写的是 $it ms") }
        }
        val meter = t?.meter
        memoryField.toolTipText = when {
            meter == null -> "这台机器量不到子进程峰值内存（没找到能解析峰值的 time）。" +
                "这里照样可以填（默认取题面），但它只作显示、不参与判定。" +
                (fromProblem?.memoryMb?.let { "题面写的是 $it MB。" } ?: "")
            else -> buildString {
                append("本地峰值内存上限（兆）。留空 = 不比内存")
                fromProblem?.memoryMb?.let { append("；这道题题面写的是 $it MB") }
                append("\n测量方式：${meter.tool.path} ${if (meter.flavor == ResourceMeter.Flavor.MAC_L) "-l" else "-v"}")
            }
        }
    }

    private fun startRun() {
        val pid = pidField.text.trim()
        if (!LuoguPidValidator.isValidPid(pid)) {
            setStatus("题号格式无效（形如 P1001）", error = true)
            return
        }
        applyPid(pid)
        persistInput()
        val t = target
        if (t == null || targetPid != pid) {
            setStatus("正在探测编译器与源文件，稍等再点")
            probe()
            return
        }
        val sourcePath = t.sourcePath
        val compiler = t.compiler
        if (sourcePath == null || compiler == null) {
            setStatus(blockingReason(pid, t, project.basePath)?.short ?: "还不能开始", error = true)
            return
        }
        val source = File(sourcePath)
        // 编译读磁盘：编辑器里那份没保存就先写盘（只这一个文件）
        val savedName = LuoguActions.saveIfModified(source)
        val timeMs = parseTimeMs(timeField.text)
        val memoryMb = parseMemoryMb(memoryField.text).takeIf { t.meter != null }

        running = true
        runButton.isEnabled = false
        stopLink.isVisible = true
        jumpLink.isVisible = false
        lastCompileText = null
        lastRunPid = pid
        pendingJump = null
        setStatus("编译中…")
        sidePanel.render(runningSections(savedName, CompilerService.executableName(pid), source.path, timeMs, memoryMb, t.meter))
        stdoutPanel.render(pendingOutputSections())

        task = SelfTestService.run(
            project = project,
            request = SelfTestService.Request(
                pid = pid,
                source = source,
                compiler = compiler,
                stdin = inputArea.text,
                workDir = File(t.projectBasePath ?: project.basePath ?: buildDir.path),
                buildDir = buildDir,
                args = LuoguSettings.getInstance().compareCompilerArgList(),
                timeoutMs = timeMs,
                memoryLimitMb = memoryMb,
                meter = t.meter,
            ),
            onFinished = { report ->
                if (!project.isDisposed) finishRun(report)
            },
        )
    }

    private fun stopRun() {
        setStatus("正在停止…")
        stopLink.isVisible = false
        task?.requestStop()
    }

    private fun finishRun(report: SelfTestService.Report) {
        running = false
        stopLink.isVisible = false
        val t = target
        val memoryMb = parseMemoryMb(memoryField.text).takeIf { t?.meter != null }
        val compile = report.compile
        val run = report.run
        lastCompileText = compile?.takeIf { !it.ok }?.diagnostic

        when {
            report.startError != null -> setStatus("写不进临时文件：${report.startError}", error = true)
            compile != null && !compile.ok -> setStatus("编译失败（诊断在下方，可点「跳到出错行」）", error = true)
            run == null -> setStatus("没跑起来", error = true)
            else -> {
                val summary = SelfTestService.summaryText(run, parseTimeMs(timeField.text), memoryMb)
                setStatus(summary, error = !summary.startsWith("正常结束"))
            }
        }
        stdoutPanel.render(outputSections(report))
        sidePanel.render(sideSections(report, memoryMb, parseTimeMs(timeField.text)))
        updateJumpButton()
        // 探测可能刚变（他中途换了编译器），运行按钮要重新能点
        val target = t
        runButton.isEnabled = target != null && blockingReason(pidField.text.trim(), target, project.basePath) == null
    }

    private fun updateJumpButton() {
        val runPid = lastRunPid
        val typed = pidField.text.trim()
        val blocked = jumpBlockReason(runPid, typed, !lastCompileText.isNullOrBlank())
        if (blocked != null) {
            pendingJump = null
            jumpLink.isVisible = false
            jumpLink.toolTipText = blocked
            return
        }
        // 诊断是**跑那次**的题号，跳也只按那个题号找文件，不用当下框里的
        val pid = runPid ?: return
        val choice = CompileErrorLocator.choose(
            text = lastCompileText,
            submittedLineCount = localSourceLineCount(pid),
            hasLocalSource = LuoguActions.hasLocalSource(project, pid),
            // 自测页编的就是项目根那份，文件名直接交给第①层比对
            localFileName = "$pid.cpp",
        )
        pendingJump = choice.hit
        jumpLink.text = CompileErrorLocator.jumpLabel(choice.hit).ifEmpty { JUMP_LABEL }
        jumpLink.isVisible = choice.hit != null
        jumpLink.toolTipText = when {
            choice.hit != null ->
                "打开项目根的 $pid.cpp 并定位到第 ${choice.hit.line} 行" + CompileErrorLocator.hitNote(choice)
            else -> CompileErrorLocator.missText(choice, pid)
        }
    }

    /** 本地那份源文件的行数（跳行的上界）；读不到返回 null 表示不卡上界。 */
    private fun localSourceLineCount(pid: String): Int? {
        val base = project.basePath ?: return null
        return runCatching { File(base, "$pid.cpp").readLines().size }.getOrNull()
    }

    private fun jumpToError() {
        val hit = pendingJump ?: run {
            setStatus("这一次没有可跳转的错误位置", error = true)
            return
        }
        val pid = lastRunPid ?: pidField.text.trim()
        if (!LuoguActions.openProblemFile(project, pid, hit.line, hit.column)) {
            setStatus("项目根下找不到 $pid.cpp，跳不了", error = true)
        }
    }

    /** 与对拍页同一个入口：探测顺序找不到时让他手动指一个（编译器是机器级的事，存应用级设置）。 */
    private fun pickCompiler() {
        val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
            .createSingleFileOrExecutableAppDescriptor()
            .withTitle("选择 C++ 编译器（clang++ / g++）")
        val toSelect = runCatching {
            target?.compiler?.file?.parentFile
                ?.let { com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByIoFile(it) }
        }.getOrNull()
        val chosen = com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, project, toSelect) ?: return
        val file = File(chosen.path)
        if (!file.isFile || !file.canExecute()) {
            setStatus("选中的文件不可执行：${file.path}", error = true)
            return
        }
        LuoguSettings.getInstance().compareCompilerPath = file.absolutePath
        target = null
        targetPid = null
        limitsTouched = false
        probe()
    }

    private fun guessedPid(): String? = CurrentFilePid.guess(project)

    private fun setStatus(text: String, error: Boolean = false) = StatusRow.apply(statusLabel, text, error)

    /** 半边一个细条说明，代替 TitledBorder（他说过底部臃肿：边框 + 标题行吃掉两行高）。 */
    /** 详情区自己会横向滚（每块是独立的等宽块），外层只给竖向滚动条。 */
    private fun scroll(component: JComponent): JBScrollPane = JBScrollPane(
        component,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
    )

    /** 上限改完（回车或失焦）：标成「他改过」并把新数字反映到提示与状态行上。 */
    private fun commitLimits() {
        limitsTouched = true
        syncLimitHints()
        val text = "时限 ${parseTimeMs(timeField.text)} ms（不含编译）" +
            when {
                target?.meter == null -> "；内存量不到，那一栏只作显示"
                else -> "；内存上限 ${parseMemoryMb(memoryField.text)?.toString() ?: "不比"} MB"
            }
        setStatus(text)
    }

    private fun captioned(caption: String, component: JComponent): JComponent {
        val pane = JPanel(BorderLayout())
        val label = JBLabel(caption)
        label.border = JBUI.Borders.empty(0, 2, 1, 0)
        label.foreground = UIUtil.getLabelDisabledForeground()
        pane.add(label, BorderLayout.NORTH)
        pane.add(component, BorderLayout.CENTER)
        return pane
    }

    companion object {

        private const val JUMP_LABEL = "跳到出错行"
        private const val TICK_MS = 1000

        /**
         * 「还不能开始」的原因：文案与对拍页共用 [LocalRunGates]，但**不查样例**——
         * 「没有成对样例」正是自测要能干活的那个场景。
         */
        @JvmStatic
        fun blockingReason(pid: String, t: CompareTarget, fallbackBase: String?): SampleComparePanel.Block? =
            LocalRunGates.noProject(t)
                ?: LocalRunGates.noSource(pid, t, fallbackBase)
                ?: LocalRunGates.noCompiler(t)

        /**
         * 编辑器换文件时题号框该不该跟着换：只在**框里为空、或仍是上次自动填的那个值**时才动。
         * 手输的题号不能被切标签冲掉（与提交页、对拍页同一条规则）。
         */
        @JvmStatic
        fun followsEditor(typed: String, autoPid: String?): Boolean =
            typed.isEmpty() || typed == autoPid

        /**
         * 「这次不给跳」的原因；null 表示可以跳。
         *
         * 诊断属于**跑那一次**的那道题：题号框已经切走还照跳，跳的是另一道题的同一个行号 ——
         * 比不跳危险得多（1.7.2 定下的口径：宁可少跳也不跳错）。
         */
        @JvmStatic
        fun jumpBlockReason(runPid: String?, typedPid: String, hasDiagnostic: Boolean): String? = when {
            runPid == null -> "还没跑过，没有可跳转的位置"
            !hasDiagnostic -> "只有编译失败那次才有可跳转的位置"
            runPid != typedPid -> "这份诊断是 $runPid 的，题号已经切到 $typedPid：再跑一次才有当前这题的"
            else -> null
        }

        /** 时间字段 → 毫秒。规则与对拍页共用 [ProblemLimits.parseTimeMs]（同一条拆分写两处必然分叉）。 */
        @JvmStatic
        fun parseTimeMs(text: String?): Int = ProblemLimits.parseTimeMs(text, SelfTestService.DEFAULT_TIMEOUT_MS)

        /** 内存字段 → MB；null = 不比。 */
        @JvmStatic
        fun parseMemoryMb(text: String?): Int? = ProblemLimits.parseMemoryMb(text)

        /** 点「运行」之后、结果还没回来时看到的那一块。 */
        @JvmStatic
        fun runningSections(
            savedName: String?,
            exeName: String,
            sourcePath: String,
            timeMs: Int,
            memoryMb: Int?,
            meter: ResourceMeter.Meter?,
        ): List<DetailSection> {
            val rows = mutableListOf(
                "源文件" to sourcePath,
                "产物" to exeName,
                "编辑器" to (savedName?.let { "已保存 $it" } ?: "无需保存"),
                "时限" to "$timeMs ms（不含编译）",
            )
            rows.add(
                "内存上限" to when {
                    memoryMb == null -> "不比内存"
                    meter == null -> "量不到内存，不生效"
                    else -> "$memoryMb MB"
                },
            )
            return listOf(DetailSection("正在跑", rows, null))
        }

        /** 中间栏还没轮到输出时说的那一句（留白看起来像面板坏了）。 */
        @JvmStatic
        fun pendingOutputSections(): List<DetailSection> =
            listOf(DetailSection(null, emptyList(), "（还没运行到这一步：正在编译或正在起进程）"))

        /** 中间那一栏：**只有 stdout**。空输出也写清楚，不给空白。 */
        @JvmStatic
        fun outputSections(report: SelfTestService.Report): List<DetailSection> {
            val run = report.run ?: return emptyList()
            return listOf(DetailSection("stdout", emptyList(), SelfTestService.stdoutBlock(run)))
        }

        /**
         * 右边那一栏：概览（键值行）· 编译诊断 · stderr。
         *
         * 「时限不含编译」写在概览里：他设的是**程序跑一组的时间**，编译另有
         * [CompilerService.COMPILE_TIMEOUT_MS] 的上限，两者各占一行，省得看到「等了好几秒」
         * 以为 1000 ms 被算上了编译。空节不出现；内存量不到时那行要写「不生效」。
         */
        @JvmStatic
        @JvmOverloads
        fun sideSections(
            report: SelfTestService.Report,
            memoryLimitMb: Int? = null,
            timeMs: Int = SelfTestService.DEFAULT_TIMEOUT_MS,
        ): List<DetailSection> {
            val sections = mutableListOf<DetailSection>()
            val compile = report.compile
            val run = report.run

            val overview = mutableListOf(
                "题目" to report.pid,
                "产物" to report.exePath,
                "时限（不含编译）" to "$timeMs ms",
            )
            report.startError?.let { overview.add("没能开始" to it) }
            compile?.let {
                overview.add(
                    "编译" to (if (it.ok) "通过 · ${it.elapsedMs} ms" else "失败 · 退出码 ${it.exitCode ?: "?"} · ${it.elapsedMs} ms"),
                )
            }
            run?.let {
                overview.add("结果" to SelfTestService.summaryText(it, timeMs, memoryLimitMb))
                overview.add("运行用时" to "${it.elapsedMs} ms")
                if (memoryLimitMb != null && it.peakMemoryBytes == null) {
                    overview.add("内存上限" to LimitFields.unenforcedText(memoryLimitMb))
                }
                SelfTestService.peakMemoryText(it)?.let { peak -> overview.add("峰值内存" to peak) }
                overview.add("退出码" to (it.exitCode?.toString() ?: "无（进程被终止）"))
            }
            sections.add(DetailSection("概览", overview, null))

            compile?.takeIf { !it.ok }?.let {
                sections.add(DetailSection("编译诊断", emptyList(), it.diagnostic))
            }
            run?.let {
                SelfTestService.stderrBlock(it)?.let { text ->
                    sections.add(DetailSection("stderr", emptyList(), text))
                }
            }
            return sections
        }
    }
}
