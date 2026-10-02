package com.user.clionluogu.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.user.clionluogu.api.statusTextOf
import com.user.clionluogu.service.AcCleanupService
import com.user.clionluogu.service.ProblemEntry
import com.user.clionluogu.service.ProblemIndexService
import com.user.clionluogu.service.ScoreTotals
import com.user.clionluogu.storage.SubmissionHistoryService
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ListSelectionModel

/**
 * 「题目」页签：项目根下本地题库的索引，以及**手动删除本题文件**的入口。
 *
 * 存在的理由：1.6.0 的 AC 清理只在评测刚变 AC 那一瞬问一次，错过就只能去 Finder 手删；
 * 刷完几十道题之后根目录会攒成一团。这里按题号把 `.cpp` / `.md` / `_samples/` / `_cases/`
 * 归并成一行，勾上「只看未提交」就能挑出那些提交过、已经 AC、但文件还堆着的题。
 *
 * 删除走 [AcCleanupService.confirmAndDelete]：只删项目根下那四个精确名字，
 * 且**必须点一次按钮 + 确认一次**，没有开关能跳过确认框。提交记录不动。
 */
class ProblemIndexPanel(private val project: Project) : JPanel(BorderLayout()) {

    /** 列表一行 = 一道题的磁盘现状 + 最近一次提交。 */
    private class Row(
        val entry: ProblemEntry,
        val latest: SubmissionHistoryService.Record?,
    ) {
        val pid: String get() = entry.pid
        val submitted: Boolean get() = latest != null
    }

    private val allRows = mutableListOf<Row>()
    private val listModel = DefaultListModel<Row>()
    private val resultList = JBList<Row>(listModel)
    private val detailArea = JTextArea()
    private val onlyUnsubmittedBox = JBCheckBox("只看未提交")
    private val countLabel = JBLabel(" ")
    private val statusLabel = JBLabel(" ")
    private val rescanButton = JButton("重新扫描")
    private val deleteButton = JButton("删除本题文件")

    private var scanning = false
    private var lastNotice: String? = null

    init {
        resultList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        resultList.cellRenderer = object : ColoredListCellRenderer<Row>() {
            override fun customizeCellRenderer(
                list: JList<out Row>,
                value: Row,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                // 没提交过的题是灰色（verdictColorOf(null) 本来就是灰）
                icon = VerdictSquareIcon(verdictColorOf(value.latest?.statusCode))
                append(value.pid)
                append("  ${filesSummary(value.entry)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                value.latest?.let { r ->
                    val total = ScoreTotals.totalScoreOfRecords(r.subtasks)
                    append(
                        "  #${r.rid} ${statusTextOf(r.statusCode)}" + ScoreTotals.totalScoreText(total),
                        SimpleTextAttributes.GRAYED_ATTRIBUTES,
                    )
                }
            }
        }
        resultList.addListSelectionListener { showDetail() }

        onlyUnsubmittedBox.addActionListener { rebuildList() }

        detailArea.isEditable = false
        detailArea.lineWrap = true
        detailArea.isFocusable = false
        detailArea.border = JBUI.Borders.empty(6)

        deleteButton.isEnabled = false
        deleteButton.toolTipText = "删除项目根下本题的 .cpp / .md / 样例目录 / 反例目录（会先弹一次确认，提交记录保留）"

        val listPane = JPanel(BorderLayout())
        val filterRow = JPanel(WrapLayout(FlowLayout.LEFT, 6, 2))
        filterRow.add(onlyUnsubmittedBox)
        filterRow.add(countLabel)
        listPane.add(filterRow, BorderLayout.NORTH)
        listPane.add(JBScrollPane(resultList), BorderLayout.CENTER)

        val split = AutoFlipSplitter(0.35f).apply {
            firstComponent = listPane
            secondComponent = JBScrollPane(detailArea)
            border = JBUI.Borders.empty(4)
        }
        add(split, BorderLayout.CENTER)

        val actionRow = JPanel(WrapLayout(FlowLayout.LEFT, 6, 4))
        actionRow.add(rescanButton)
        actionRow.add(deleteButton)

        val bottom = JPanel(BorderLayout())
        bottom.border = JBUI.Borders.empty(4, 8)
        bottom.add(actionRow, BorderLayout.NORTH)
        bottom.add(statusLabel, BorderLayout.CENTER)
        add(bottom, BorderLayout.SOUTH)

        rescanButton.addActionListener { reload() }
        deleteButton.addActionListener { deleteSelected() }
    }

    /** 切到本页签就重扫：在 IDE 外面删过文件、或刚拉完新题，都不该要求手动点。 */
    override fun addNotify() {
        super.addNotify()
        if (!scanning) reload()
    }

    // ---- 扫描 ----

    private fun reload() {
        if (scanning) return
        scanning = true
        statusLabel.text = "扫描中…"
        val base = project.basePath?.let { File(it) }
        val history = SubmissionHistoryService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            val entries = ProblemIndexService.scan(base)
            val rows = entries.map { Row(it, runCatching { history.latestFor(it.pid) }.getOrNull()) }
            val notice = when {
                base == null -> "项目还没落盘目录（没保存过工程），扫不到文件"
                !base.isDirectory -> "读不到目录：${base.path}"
                rows.isEmpty() -> "项目根下没有 Pxxx.cpp / Pxxx.md 这类文件"
                else -> null
            }
            ApplicationManager.getApplication().invokeLater {
                scanning = false
                lastNotice = notice
                allRows.clear()
                allRows.addAll(rows)
                rebuildList()
                statusLabel.text = notice ?: "${allRows.size} 道题 · 只列项目根下一层的文件"
                showDetail()
            }
        }
    }

    // ---- 视图 ----

    /**
     * 让 `listModel` 追上 `allRows`，并尽量保住原本选中的那道题，
     * 免得每次重扫都跳回第一行。
     */
    private fun rebuildList() {
        val keep = selectedRow()?.pid
        val visible = visibleRows()
        listModel.clear()
        visible.forEach { listModel.addElement(it) }
        if (keep != null) {
            val idx = visible.indexOfFirst { it.pid == keep }
            if (idx >= 0) resultList.selectedIndex = idx
        }
        updateCounts()
    }

    private fun visibleRows(): List<Row> =
        if (!onlyUnsubmittedBox.isSelected) {
            allRows
        } else {
            allRows.filter { keepsInUnsubmittedOnly(it.submitted) }
        }

    private fun selectedRow(): Row? {
        val idx = resultList.selectedIndex
        return if (idx in 0 until listModel.size()) listModel.elementAt(idx) else null
    }

    private fun updateCounts() {
        val unsubmitted = allRows.count { !it.submitted }
        countLabel.text = when {
            allRows.isEmpty() -> "还没东西"
            !onlyUnsubmittedBox.isSelected || unsubmitted == 0 -> "${allRows.size} 道题"
            else -> "${allRows.size} 道题 · 未提交 $unsubmitted 道"
        }
        deleteButton.isEnabled = selectedRow()?.entry?.isEmpty() == false
    }

    private fun showDetail() {
        val row = selectedRow()
        updateCounts()
        detailArea.text = detailText(row)
        detailArea.caretPosition = 0
    }

    private fun detailText(row: Row?): String {
        if (row == null) return lastNotice ?: "左边选一道题看细节。删除只认项目根下的四个精确名字。"
        val e = row.entry
        return buildString {
            append("题目：").append(e.pid).append('\n')
            append('\n')
            append("能删的（只此四个名字，其余一概不碰）：\n")
            if (e.isEmpty()) {
                append("  （无）磁盘上没有本题文件\n")
            } else {
                e.presentNames().forEach { append("  · ").append(it).append('\n') }
            }
            append('\n')
            val r = row.latest
            if (r == null) {
                append("提交记录：这个工程里没有提交过\n")
            } else {
                val total = ScoreTotals.totalScoreOfRecords(r.subtasks)
                append("最近一次提交：#${r.rid} → ${statusTextOf(r.statusCode)}")
                append(ScoreTotals.totalScoreText(total)).append('\n')
                r.lang.takeIf { it.isNotBlank() }?.let { append("语言：$it\n") }
                r.timeMs?.let { append("耗时：$it ms\n") }
                r.memoryKb?.let { append("内存：$it KB\n") }
                append("（删除不动提交记录，评测历史还在「评测」页）\n")
            }
        }
    }

    /** 点删除才删：一次确认框，列清楚要删的东西。 */
    private fun deleteSelected() {
        val row = selectedRow() ?: run {
            statusLabel.text = "先选中一道题"
            return
        }
        AcCleanupService.confirmAndDelete(project, row.pid) { reload() }
    }

    companion object {
        /** 列表里那截灰色摘要：磁盘上有哪几类产物。 */
        @JvmStatic
        fun filesSummary(e: ProblemEntry): String {
            val parts = mutableListOf<String>()
            if (e.cpp != null) parts.add("源码")
            if (e.md != null) parts.add("题面")
            if (e.samples != null) parts.add("${e.sampleCount}组样例")
            if (e.cases != null) parts.add("${e.caseCount}个反例")
            return if (parts.isEmpty()) "没有文件" else parts.joinToString(" · ")
        }

        /** 勾了「只看未提交」时留下哪些行：提交过的（在「评测」页有记录）被滤掉。 */
        @JvmStatic
        fun keepsInUnsubmittedOnly(submitted: Boolean): Boolean = !submitted
    }
}
