package com.user.clionluogu.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.user.clionluogu.api.SubmissionStatus
import com.user.clionluogu.service.AcCleanupService
import com.user.clionluogu.service.CompileErrorLocator
import com.user.clionluogu.service.CompileHit
import com.user.clionluogu.service.JudgeNotifyService
import com.user.clionluogu.service.JudgePollingService
import com.user.clionluogu.service.LuoguActions
import com.user.clionluogu.service.ScoreTotals
import com.user.clionluogu.service.SubmissionTracker
import com.user.clionluogu.storage.SubmissionHistoryService
import com.user.clionluogu.storage.toStatus
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.datatransfer.StringSelection
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel

/**
 * 侧边评测工具窗口：
 * 左侧为提交历史列表（pid + rid + 状态），右侧为选中记录的详情
 * （状态 / 时间 / 内存 / 子任务逐行 / 逐测试点方块 / 提交的代码 / 编译错误）。
 *
 * 提交记录由项目级 [SubmissionHistoryService] 持久化，构造时加载历史，
 * 新提交与状态更新写回存储。所有 UI 更新必须发生在 EDT（本类内部通过 [runOnEdt] 将 IO 回调切回 EDT）。
 */
class LuoguToolWindow(private val project: Project) {

    /** 一条提交记录的在内存中的简化模型。 */
    private class SubmissionEntry(val pid: String, val rid: String) {
        var status: SubmissionStatus? = null
        var lang: String? = null
        var code: String? = null

        val color: java.awt.Color get() = verdictColorOf(status?.statusCode)

        /** 列表与详情都显示这一句；没评测过时是「等待」。 */
        val statusText: String get() = status?.statusText ?: "等待"
    }

    private val entries = ArrayList<SubmissionEntry>()
    private val listModel = DefaultListModel<SubmissionEntry>()

    // ---- UI 组件 ----
    private val historyList = JBList<SubmissionEntry>(listModel)
    private val detail = DetailPanel()
    private val detailScroll = JBScrollPane(
        detail,
        JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
        JScrollPane.HORIZONTAL_SCROLLBAR_NEVER,
    ).apply { border = null }
    private val statusLabel = JBLabel("未选择记录")
    private val copyCodeButton = JButton("复制代码")
    private val jumpButton = JButton("跳到出错行")
    private val squares = TestCaseSquares()
    private val squaresScroll = JBScrollPane(squares).apply {
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        border = null
        preferredSize = java.awt.Dimension(10, 120)
    }
    private val rootPanel = JPanel(BorderLayout())

    /** 当前详情里解析出的跳转目标；null 表示这条记录没有可跳的编译错误位置。 */
    private var pendingJump: CompileHit? = null

    init {
        historyList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        historyList.emptyText.appendText("还没有提交记录；提交一次就会出现在这里")
        historyList.cellRenderer = object : ColoredListCellRenderer<SubmissionEntry>() {
            override fun customizeCellRenderer(
                list: JList<out SubmissionEntry>,
                value: SubmissionEntry,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                icon = VerdictSquareIcon(value.color, hollow = value.status == null)
                // 三段分开着色，不再用 `|` 拼成一行
                append(value.pid, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                appendTextPadding(JBUI.scale(6))
                append("#${value.rid}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                appendTextPadding(JBUI.scale(6))
                append(
                    value.statusText,
                    SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, value.color),
                )
            }
        }
        historyList.addListSelectionListener { updateDetail() }

        copyCodeButton.addActionListener { copySelectedCode() }
        copyCodeButton.icon = AllIcons.Actions.Copy
        jumpButton.addActionListener { jumpToCompileError() }
        jumpButton.icon = AllIcons.Actions.NextOccurence
        jumpButton.isEnabled = false
        jumpButton.toolTipText = "打开项目根的 Pxxx.cpp 并定位到编译错误那一行"
        // 输入即定位（不用把鼠标移到列表上翻）
        ListSpeedSearch(historyList) { entry: SubmissionEntry -> "${entry.pid} #${entry.rid}" }

        val listPane = JPanel(BorderLayout())
        listPane.add(JBLabel("提交历史"), BorderLayout.NORTH)
        listPane.add(JBScrollPane(historyList), BorderLayout.CENTER)

        // 状态文字独占一行，两个按钮放 WrapLayout：并排放得下就并排，放不下折行且高度算得对。
        // 探针实测（Layout172Probe）：把「文字 WEST + 两个按钮 GridLayout EAST」塞进 BorderLayout，
        // 侧边栏窄到 240 / 170 像素时文字会溢出、第一个按钮的 x 变成负数——两个组件直接被裁掉。
        val detailButtons = JPanel(WrapLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(copyCodeButton)
            add(jumpButton)
        }
        val detailTopRow = JPanel(GridBagLayout()).apply {
            isOpaque = false
            fun cell(y: Int) = GridBagConstraints().apply {
                gridx = 0
                gridy = y
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
            }
            add(statusLabel, cell(0))
            add(detailButtons, cell(1))
        }

        val detailTop = JPanel(BorderLayout())
        detailTop.add(detailTopRow, BorderLayout.NORTH)
        detailTop.add(squaresScroll, BorderLayout.CENTER)

        val detailPane = JPanel(BorderLayout())
        detailPane.add(detailTop, BorderLayout.NORTH)
        detailPane.add(detailScroll, BorderLayout.CENTER)

        // 窄侧边栏里左右两栏都挤，交给 AutoFlipSplitter 按宽度自动改成上下
        val split = AutoFlipSplitter(0.4f).apply {
            firstComponent = listPane
            secondComponent = detailPane
            border = JBUI.Borders.empty(4)
        }

        rootPanel.add(split, BorderLayout.CENTER)

        squaresScroll.isVisible = false

        loadHistory()
        refreshUnfinished()
    }

    /** 供 factory 装配的内容组件。 */
    val content: JComponent get() = rootPanel

    // ---- 公开 API ----

    /** 从持久化服务加载历史记录并填充列表（不写回服务）。 */
    private fun loadHistory() {
        SubmissionHistoryService.getInstance(project).list().forEach { record ->
            val entry = SubmissionEntry(record.pid, record.rid)
            entry.lang = record.lang
            entry.code = record.code
            entry.status = record.toStatus()
            entries.add(entry)
            listModel.addElement(entry)
        }
    }

    /**
     * 两类记录需要在启动时补拉一次：
     * - 非终态（评测没跑完就重启了 IDE，之后永远停在「进行中」）；
     * - **CE 但记录里没有编译错误详情**——1.7.2 之前 `Record` 压根不存这个字段，
     *   老记录必然是空的，不补拉就只能重新提交一次才能跳行。
     *
     * 都是 quiet（见 [updateSubmission]）：那是过期的跃迁，弹通知或清理框都很莫名其妙。
     * 第二类限量，免得攒了几十条 CE 记录时启动就打一排请求。
     */
    private fun refreshUnfinished() {
        val usable = entries.filter { it.rid.isNotBlank() }
        val judging = usable.filterNot { JudgePollingService.isTerminal(it.status?.statusCode) }
        val ceWithoutText = usable.filter {
            it.status?.statusCode == CE_CODE && it.status?.compileError.isNullOrBlank()
        }.take(MAX_CATCHUP_FETCH)
        (judging + ceWithoutText).distinct().forEach { entry ->
            JudgePollingService.startPolling(
                rid = entry.rid,
                onUpdate = { st -> SubmissionTracker.applyStatus(project, entry.rid, st, quiet = true) },
                onDone = { st -> SubmissionTracker.applyStatus(project, entry.rid, st, quiet = true) },
                onError = { },
            )
        }
    }

    /**
     * 记录一次新提交（调用方需保证在 EDT；若在后台线程则由 [runOnEdt] 切回）。
     */
    fun addSubmission(pid: String, rid: String, lang: String = "", code: String = "") {
        runOnEdt {
            val entry = SubmissionEntry(pid, rid)
            entry.lang = lang
            entry.code = code
            entries.add(entry)
            listModel.addElement(entry)
            historyList.selectedIndex = listModel.size() - 1
        }
    }

    /**
     * 更新某 rid 的状态并刷新列表与详情（调用方需在 EDT，或在本窗口回调场景由 [runOnEdt] 切回）。
     *
     * **只管界面**：持久化在 [SubmissionTracker.applyStatus] 里无条件做（提交页搬到底部窗口后，
     * 这里可能压根不存在），别再在这一行偷偷写盘。
     *
     * [quiet] = true 用于重启后补轮询的老记录：那已经是**过期的跃迁**，
     * 再弹清理模态框或失败通知只会莫名其妙，只把数据刷新回来即可。
     */
    fun updateSubmission(rid: String, status: SubmissionStatus, quiet: Boolean = false) {
        runOnEdt {
            val entry = entries.firstOrNull { it.rid == rid } ?: return@runOnEdt
            // 只在「跃迁」时提示：一次终态会被 onUpdate 与 onDone 各回调一次，
            // 若只判状态码就会连弹两个模态框 / 两条通知。
            val wasTerminal = JudgePollingService.isTerminal(entry.status?.statusCode)
            val reachedAc = !quiet && status.statusCode == AC_CODE && entry.status?.statusCode != AC_CODE
            val failedFirstTime = !quiet &&
                JudgePollingService.isTerminal(status.statusCode) &&
                status.statusCode != AC_CODE &&
                !wasTerminal
            entry.status = status
            val idx = entries.indexOf(entry)
            if (idx >= 0 && idx < listModel.size()) {
                listModel.set(idx, entry)
            }
            if (entry === selectedEntry()) {
                renderDetail(entry)
            }
            if (reachedAc) {
                AcCleanupService.promptAndCleanup(project, entry.pid)
            } else if (failedFirstTime) {
                JudgeNotifyService.notifyFailure(project, entry.pid, status)
            }
        }
    }

    /**
     * 轮询本身出错（网络 / 解析）：把原因写进这条记录并刷新界面。
     *
     * 不写持久化——那是 [SubmissionTracker] 的事，这里只负责「看得见的失败」。
     */
    fun markPollError(rid: String, error: Throwable) {
        runOnEdt {
            val entry = entries.firstOrNull { it.rid == rid } ?: return@runOnEdt
            entry.status = SubmissionStatus(
                rid = rid,
                statusText = "轮询出错",
                compileError = error.message ?: error.javaClass.simpleName,
            )
            val idx = entries.indexOf(entry)
            if (idx >= 0 && idx < listModel.size()) {
                listModel.set(idx, entry)
            }
            if (entry === selectedEntry()) renderDetail(entry)
        }
    }

    fun clearHistory() {
        SubmissionHistoryService.getInstance(project).clear()
        runOnEdt {
            entries.clear()
            listModel.clear()
            detail.render(emptyList())
            statusLabel.text = "未选择记录"
            squares.setSubtasks(emptyList())
            squaresScroll.isVisible = false
            pendingJump = null
            jumpButton.isEnabled = false
        }
    }

    /**
     * 选中某条记录（通知里的「查看」走这里）：只改选中项，渲染复用列表监听器。
     * 记录已被清空时静默返回。
     */
    fun selectSubmission(rid: String) {
        val idx = entries.indexOfFirst { it.rid == rid }
        if (idx < 0) return
        historyList.selectedIndex = idx
        historyList.ensureIndexIsVisible(idx)
    }

    // ---- 内部实现 ----

    private fun selectedEntry(): SubmissionEntry? {
        val idx = historyList.selectedIndex
        return if (idx in entries.indices) entries[idx] else null
    }

    private fun updateDetail() {
        selectedEntry()?.let { renderDetail(it) }
    }

    private fun copySelectedCode() {
        val code = selectedEntry()?.code
        if (!code.isNullOrEmpty()) {
            CopyPasteManager.getInstance().setContents(StringSelection(code))
        }
    }

    private fun renderDetail(entry: SubmissionEntry) {
        val st = entry.status
        val subtasks = st?.subtaskResults ?: emptyList()
        // 取值顺序（明细优先、退化到得分文本）在 ScoreTotals 里，与未通过通知共用
        val total = ScoreTotals.totalScoreOf(st)
        squares.setSubtasks(subtasks, total)
        squaresScroll.isVisible = subtasks.isNotEmpty()
        squaresScroll.revalidate()
        statusLabel.text = "${entry.pid} · #${entry.rid} · ${st?.statusText ?: "等待"}" +
            ScoreTotals.totalScoreText(total)
        detail.render(
            DetailPanel.evalSections(
                pid = entry.pid,
                rid = entry.rid,
                lang = entry.lang,
                statusText = st?.statusText ?: "等待",
                totalScore = total,
                timeMs = st?.timeMs,
                memoryKb = st?.memoryKb,
                // 有逐测试点方块时不再重复列得分文本，跟原来一致
                subtaskInfo = if (subtasks.isEmpty()) st?.subtaskInfo ?: emptyList() else emptyList(),
                compileError = st?.compileError,
                code = entry.code,
            ),
        )
        detailScroll.verticalScrollBar.value = 0
        updateJumpButton(entry)
    }

    /**
     * 刷新「跳到出错行」：只看这次评测带回的编译诊断。
     *
     * 行号上界用**当次提交的代码**行数——本地文件后来改过、诊断指向的行已经不存在时，
     * 宁可不给跳（点了会跳错地方），也不猜。
     */
    private fun updateJumpButton(entry: SubmissionEntry) {
        val lineCount = entry.code?.takeIf { it.isNotEmpty() }?.let { c ->
            runCatching { c.lineSequence().count() }.getOrNull()
        }
        val choice = CompileErrorLocator.choose(
            text = entry.status?.compileError,
            submittedLineCount = lineCount,
            hasLocalSource = LuoguActions.hasLocalSource(project, entry.pid),
            // 评测页知道自己要开的是项目根那份 Pxxx.cpp，把它交给第①层去比对
            localFileName = "${entry.pid}.cpp",
        )
        pendingJump = choice.hit
        jumpButton.isEnabled = choice.hit != null
        jumpButton.toolTipText = if (choice.hit != null) {
            "打开项目根的 ${entry.pid}.cpp 并定位到第 ${choice.hit.line} 行" +
                CompileErrorLocator.hitNote(choice)
        } else {
            CompileErrorLocator.missText(choice, entry.pid)
        }
    }

    /** 打开项目根的 `Pxxx.cpp` 并定位到编译错误。 */
    private fun jumpToCompileError() {
        val entry = selectedEntry() ?: return
        val hit = pendingJump ?: run {
            statusLabel.text = "${entry.pid} 这一条没有可跳转的编译错误"
            return
        }
        if (!LuoguActions.openProblemFile(project, entry.pid, hit.line, hit.column)) {
            statusLabel.text = "项目根下找不到 ${entry.pid}.cpp，跳不了"
        }
    }

    /** 若当前不在 EDT，则切回 EDT 执行；否则直接执行。 */
    private fun runOnEdt(action: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            action()
        } else {
            app.invokeLater(action)
        }
    }

    companion object {
        /** 承载评测页 [LuoguToolWindow] 实例的用户数据键（由 [LuoguToolWindowFactory] 写入工具窗口）。 */
        val WINDOW_KEY: Key<LuoguToolWindow> = Key.create("com.user.clionluogu.LuoguToolWindow")

        /** 洛谷「通过 (AC)」状态码，与 `statusTextOf` 的映射表一致。 */
        private const val AC_CODE = 12

        /** 洛谷「编译错误 (CE)」状态码，同样取自 `statusTextOf` 的表。 */
        private const val CE_CODE = 2

        /** 启动时最多给多少条「CE 但没有编译错误详情」的老记录补拉一次。 */
        private const val MAX_CATCHUP_FETCH = 8
    }
}
