package com.user.clionluogu.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.user.clionluogu.api.SubmissionStatus
import com.user.clionluogu.api.SubtaskResult
import com.user.clionluogu.api.TestCaseResult
import com.user.clionluogu.api.statusTextOf
import com.user.clionluogu.service.JudgePollingService
import com.user.clionluogu.storage.SubmissionHistoryService
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
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

        /** 列表展示文本（就地更新，无需重建模型项）。 */
        fun displayText(): String {
            val st = status?.statusText ?: "等待"
            return "$pid | #$rid | $st"
        }
    }

    private val entries = ArrayList<SubmissionEntry>()
    private val listModel = DefaultListModel<SubmissionEntry>()

    // ---- UI 组件 ----
    private val historyList = JBList<SubmissionEntry>(listModel)
    private val detailArea = JTextArea()
    private val statusLabel = JBLabel("未选择记录")
    private val copyCodeButton = JButton("复制代码")
    private val squares = TestCaseSquares()
    private val squaresScroll = JBScrollPane(squares).apply {
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        border = null
        preferredSize = java.awt.Dimension(10, 120)
    }
    private val rootPanel = JPanel(BorderLayout())

    init {
        historyList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        historyList.cellRenderer = object : ColoredListCellRenderer<SubmissionEntry>() {
            override fun customizeCellRenderer(
                list: JList<out SubmissionEntry>,
                value: SubmissionEntry,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                icon = VerdictSquareIcon(value.color)
                append(value.displayText())
            }
        }
        historyList.addListSelectionListener { updateDetail() }

        detailArea.isEditable = false
        detailArea.lineWrap = true
        detailArea.isFocusable = false
        detailArea.border = JBUI.Borders.empty(6)

        copyCodeButton.addActionListener { copySelectedCode() }

        val listPane = JPanel(BorderLayout())
        listPane.add(JBLabel("提交历史"), BorderLayout.NORTH)
        listPane.add(JBScrollPane(historyList), BorderLayout.CENTER)

        val detailTopRow = JPanel(BorderLayout())
        detailTopRow.add(statusLabel, BorderLayout.WEST)
        detailTopRow.add(copyCodeButton, BorderLayout.EAST)

        val detailTop = JPanel(BorderLayout())
        detailTop.add(detailTopRow, BorderLayout.NORTH)
        detailTop.add(squaresScroll, BorderLayout.CENTER)

        val detailPane = JPanel(BorderLayout())
        detailPane.add(detailTop, BorderLayout.NORTH)
        detailPane.add(JBScrollPane(detailArea), BorderLayout.CENTER)

        val split = javax.swing.JSplitPane(
            javax.swing.JSplitPane.HORIZONTAL_SPLIT,
            listPane,
            detailPane,
        )
        split.resizeWeight = 0.4
        split.dividerLocation = 220
        split.border = JBUI.Borders.empty(4)

        rootPanel.add(split, BorderLayout.CENTER)

        squaresScroll.isVisible = false

        loadHistory()
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
     */
    fun updateSubmission(rid: String, status: SubmissionStatus) {
        runOnEdt {
            val entry = entries.firstOrNull { it.rid == rid } ?: return@runOnEdt
            entry.status = status
            val idx = entries.indexOf(entry)
            if (idx >= 0 && idx < listModel.size()) {
                listModel.set(idx, entry)
            }
            if (entry === selectedEntry()) {
                renderDetail(entry)
            }
        }
        SubmissionHistoryService.getInstance(project).update(rid, status)
    }

    /**
     * 跟踪一次评测：写入持久化记录，在后台启动 [JudgePollingService.startPolling] 轮询该 rid，
     * 并将 onUpdate/onDone/onError 回调切回 EDT，实时更新提交历史与详情（终态仍保留结果）。
     */
    fun trackSubmission(pid: String, rid: String, lang: String = "", code: String = "") {
        addSubmission(pid, rid, lang, code)

        val record = SubmissionHistoryService.Record().apply {
            this.pid = pid
            this.rid = rid
            this.submitTime = System.currentTimeMillis()
            this.lang = lang
            this.code = code
        }
        SubmissionHistoryService.getInstance(project).add(record)

        // 同一 rid 的旧轮询会被 startPolling 取消，不同 rid 可并发轮询。
        JudgePollingService.startPolling(
            rid = rid,
            onUpdate = { st -> updateSubmission(rid, st) },
            onDone = { st -> updateSubmission(rid, st) },
            onError = { t ->
                runOnEdt {
                    val entry = entries.firstOrNull { it.rid == rid } ?: return@runOnEdt
                    entry.status = SubmissionStatus(
                        rid = rid,
                        statusText = "轮询出错",
                        compileError = t.message ?: t.javaClass.simpleName,
                    )
                    val idx = entries.indexOf(entry)
                    if (idx >= 0 && idx < listModel.size()) {
                        listModel.set(idx, entry)
                    }
                    if (entry === selectedEntry()) renderDetail(entry)
                }
            },
        )
    }

    fun clearHistory() {
        SubmissionHistoryService.getInstance(project).clear()
        runOnEdt {
            entries.clear()
            listModel.clear()
            detailArea.text = ""
            statusLabel.text = "未选择记录"
            squares.setSubtasks(emptyList())
            squaresScroll.isVisible = false
        }
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
        squares.setSubtasks(subtasks)
        squaresScroll.isVisible = subtasks.isNotEmpty()
        squaresScroll.revalidate()
        statusLabel.text = "${entry.pid} | #${entry.rid}  →  ${st?.statusText ?: "等待"}"
        val sb = StringBuilder()
        sb.append("提交记录：#${entry.rid}\n")
        sb.append("题目：${entry.pid}\n")
        entry.lang?.takeIf { it.isNotBlank() }?.let { sb.append("语言：$it\n") }
        sb.append("状态：${st?.statusText ?: "等待"}\n")
        st?.timeMs?.let { sb.append("耗时：$it ms\n") }
        st?.memoryKb?.let { sb.append("内存：$it KB\n") }
        if (subtasks.isEmpty() && st?.subtaskInfo?.isNotEmpty() == true) {
            sb.append("子任务：\n")
            st.subtaskInfo.forEach { sb.append("  • ").append(it).append('\n') }
        }
        st?.compileError?.let {
            sb.append("\n—— 编译错误 ——\n").append(it).append('\n')
        }
        entry.code?.takeIf { it.isNotEmpty() }?.let {
            sb.append("\n—— 提交的代码 ——\n").append(it).append('\n')
        }
        detailArea.text = sb.toString()
        detailArea.caretPosition = 0
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
    }
}

/** 将持久化记录还原为一次评测状态快照；无任何评测数据时返回 null（视为等待中）。 */
private fun SubmissionHistoryService.Record.toStatus(): SubmissionStatus? {
    if (statusCode == null && timeMs == null && memoryKb == null && subtasks.isEmpty()) return null
    return SubmissionStatus(
        rid = rid,
        statusCode = statusCode,
        statusText = statusTextOf(statusCode),
        timeMs = timeMs,
        memoryKb = memoryKb,
        subtaskResults = subtasks.map { sub ->
            SubtaskResult(
                id = sub.id,
                status = sub.status,
                score = sub.score,
                testCases = sub.testCases.map { tc ->
                    TestCaseResult(
                        id = tc.id,
                        status = tc.status,
                        timeMs = tc.timeMs,
                        memoryKb = tc.memoryKb,
                        score = tc.score,
                    )
                },
            )
        },
    )
}
