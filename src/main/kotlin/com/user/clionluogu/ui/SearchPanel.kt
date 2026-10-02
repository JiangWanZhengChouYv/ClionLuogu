package com.user.clionluogu.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.user.clionluogu.api.ProblemSummary
import com.user.clionluogu.service.LuoguActions
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel

/** 搜索页：关键词 → 后台搜索 → 列表展示结果，可拉取选中题目、双击预览题面、查看选中题的题解。 */
class SearchPanel(
    private val project: Project,
    private val onPreview: (String) -> Unit,
    private val onSolutions: (String) -> Unit,
) : JPanel(BorderLayout()) {

    private val keywordField = JBTextField()
    private val searchButton = JButton("搜索")
    private val fetchSelectedButton = JButton("拉取选中")
    private val solutionsButton = JButton("查看题解")
    private val listModel = DefaultListModel<ProblemSummary>()
    private val resultList = JBList(listModel)
    private val statusLabel = JBLabel("输入关键词开始搜索（双击结果可预览题面）")

    init {
        keywordField.columns = 20
        keywordField.emptyText.appendText("标题 / 题号 / 标签，回车搜索")
        resultList.emptyText.appendText("没有结果：换个关键词，或输入题号搜")

        resultList.cellRenderer = object : ColoredListCellRenderer<ProblemSummary>() {
            override fun customizeCellRenderer(
                list: JList<out ProblemSummary>,
                value: ProblemSummary,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                // 题号与题名分开着色，比「两个空格」假列对齐清楚
                append(value.pid, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                appendTextPadding(JBUI.scale(6))
                append(value.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }
        ListSpeedSearch(resultList) { s: ProblemSummary -> "${s.pid} ${s.name}" }

        val inputRow = JPanel(WrapLayout(FlowLayout.LEFT, 6, 4))
        inputRow.border = JBUI.Borders.empty(6, 8, 0, 8)
        inputRow.add(JBLabel("关键词"))
        inputRow.add(keywordField)
        inputRow.add(searchButton)
        inputRow.add(fetchSelectedButton)
        inputRow.add(solutionsButton)

        val top = JPanel(BorderLayout())
        top.add(inputRow, BorderLayout.NORTH)
        top.add(statusLabel, BorderLayout.SOUTH)
        statusLabel.border = JBUI.Borders.empty(2, 8)

        add(top, BorderLayout.NORTH)
        add(JBScrollPane(resultList), BorderLayout.CENTER)

        searchButton.addActionListener { doSearch() }
        fetchSelectedButton.addActionListener { fetchSelected() }
        solutionsButton.addActionListener { showSolutions() }

        resultList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val index = resultList.locationToIndex(e.point)
                if (index < 0 || index >= listModel.size()) return
                val bounds = resultList.getCellBounds(index, index) ?: return
                if (!bounds.contains(e.point)) return
                val problem = listModel.getElementAt(index)
                setStatus("加载题面 ${problem.pid}…")
                onPreview(problem.pid)
            }
        })
    }

    /**
     * 状态行只有一行，而「拉取完成」那是一段多行文本 —— 取首行显示、全文进 tooltip，
     * 比原来把整段塞进 `JBLabel`（结果被压成一行、还撑破宽度）诚实。
     */
    private fun setStatus(text: String, error: Boolean = false) {
        statusLabel.text = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        statusLabel.toolTipText = text.takeIf { it.contains('\n') }
        statusLabel.foreground = if (error) UIUtil.getErrorForeground() else UIUtil.getLabelForeground()
    }

    private fun doSearch() {
        val keyword = keywordField.text.trim()
        if (keyword.isEmpty()) {
            setStatus("关键词不能为空", error = true)
            return
        }
        setStatus("搜索中…")
        LuoguActions.search(
            project = project,
            keyword = keyword,
            onResult = { results ->
                listModel.clear()
                results.forEach { listModel.addElement(it) }
                if (results.isEmpty()) {
                    setStatus("未找到匹配的题目", error = true)
                } else {
                    setStatus("共 ${results.size} 条结果")
                    resultList.selectedIndex = 0
                }
            },
            onError = { msg -> setStatus(msg, error = true) },
        )
    }

    private fun fetchSelected() {
        val problem = resultList.selectedValue ?: run {
            setStatus("请先选择一条结果", error = true)
            return
        }
        setStatus("拉取 ${problem.pid} 中…")
        LuoguActions.fetchAndGenerate(
            project = project,
            pid = problem.pid,
            onResult = { text -> setStatus(text) },
            onError = { msg -> setStatus(msg, error = true) },
        )
    }

    /** 把选中题目交给预览页的题解模式；拉取与渲染均由预览页负责。 */
    private fun showSolutions() {
        val problem = resultList.selectedValue ?: run {
            setStatus("请先选择一条结果", error = true)
            return
        }
        onSolutions(problem.pid)
    }
}
