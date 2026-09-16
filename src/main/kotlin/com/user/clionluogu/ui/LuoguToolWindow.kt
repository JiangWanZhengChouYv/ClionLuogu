package com.user.clionluogu.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.user.clionluogu.api.SubmissionStatus
import com.user.clionluogu.service.JudgePollingService
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ListSelectionModel

/**
 * 侧边评测工具窗口：
 * 左侧为提交历史列表（pid + rid + 状态），右侧为选中记录的详情
 * （状态 / 时间 / 内存 / 子任务逐行 / 编译错误）。
 *
 * 所有更新必须发生在 EDT（本类内部通过 [runOnEdt] 将 IO 回调切回 EDT）。
 */
class LuoguToolWindow(private val project: Project) {

    /** 一条提交记录的在内存中的简化模型。 */
    private class SubmissionEntry(val pid: String, val rid: String) {
        var status: SubmissionStatus? = null

        /** 列表展示文本（就地更新，无需重建模型项）。 */
        fun displayText(): String {
            val st = status?.statusText ?: "等待"
            return "$pid | #$rid | $st"
        }
    }

    private val entries = ArrayList<SubmissionEntry>()
    private val listModel = DefaultListModel<String>()

    // ---- UI 组件 ----
    private val historyList = JBList<String>(listModel)
    private val detailArea = JTextArea()
    private val statusLabel = JBLabel("未选择记录")
    private val rootPanel = JPanel(BorderLayout())

    init {
        historyList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        historyList.addListSelectionListener { updateDetail() }

        detailArea.isEditable = false
        detailArea.lineWrap = true
        detailArea.isFocusable = false
        detailArea.border = JBUI.Borders.empty(6)

        val listPane = JPanel(BorderLayout())
        listPane.add(JBLabel("提交历史"), BorderLayout.NORTH)
        listPane.add(JBScrollPane(historyList), BorderLayout.CENTER)

        val detailPane = JPanel(BorderLayout())
        detailPane.add(statusLabel, BorderLayout.NORTH)
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
    }

    /** 供 factory 装配的内容组件。 */
    val content: JComponent get() = rootPanel

    // ---- 公开 API ----

    /**
     * 记录一次新提交（调用方需保证在 EDT；若在后台线程则由 [runOnEdt] 切回）。
     */
    fun addSubmission(pid: String, rid: String) {
        runOnEdt {
            val entry = SubmissionEntry(pid, rid)
            entries.add(entry)
            listModel.addElement(entry.displayText())
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
                listModel.set(idx, entry.displayText())
            }
            if (entry === selectedEntry()) {
                renderDetail(entry)
            }
        }
    }

    /**
     * 跟踪一次评测：在后台启动 [JudgePollingService.startPolling] 轮询该 rid，
     * 并将 onUpdate/onDone/onError 回调切回 EDT，实时更新提交历史与详情（终态仍保留结果）。
     */
    fun trackSubmission(pid: String, rid: String) {
        addSubmission(pid, rid)
        // startPolling 内部会 cancelAll 上一次轮询，故可安全依次跟踪。
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
                        listModel.set(idx, entry.displayText())
                    }
                    if (entry === selectedEntry()) renderDetail(entry)
                }
            },
        )
    }

    // ---- 内部实现 ----

    private fun selectedEntry(): SubmissionEntry? {
        val idx = historyList.selectedIndex
        return if (idx in entries.indices) entries[idx] else null
    }

    private fun updateDetail() {
        selectedEntry()?.let { renderDetail(it) }
    }

    private fun renderDetail(entry: SubmissionEntry) {
        val st = entry.status
        statusLabel.text = "${entry.pid} | #${entry.rid}  →  ${st?.statusText ?: "等待"}"
        val sb = StringBuilder()
        sb.append("提交记录：#${entry.rid}\n")
        sb.append("题目：${entry.pid}\n")
        sb.append("状态：${st?.statusText ?: "等待"}\n")
        st?.timeMs?.let { sb.append("耗时：$it ms\n") }
        st?.memoryKb?.let { sb.append("内存：$it KB\n") }
        if (st?.subtaskInfo?.isNotEmpty() == true) {
            sb.append("子任务：\n")
            st.subtaskInfo.forEach { sb.append("  • ").append(it).append('\n') }
        }
        st?.compileError?.let {
            sb.append("\n—— 编译错误 ——\n").append(it).append('\n')
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
        /** 当前工具窗口实例（由 [LuoguToolWindowFactory] 赋值），供提交等动作联动。 */
        @Volatile
        var INSTANCE: LuoguToolWindow? = null
    }
}