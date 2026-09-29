package com.user.clionluogu.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
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

/** 搜索页：关键词 → 后台搜索 → 列表展示结果，可拉取选中题目、双击结果预览题面。 */
class SearchPanel(
    private val project: Project,
    private val onPreview: (String) -> Unit,
) : JPanel(BorderLayout()) {

    private val keywordField = JBTextField()
    private val searchButton = JButton("搜索")
    private val fetchSelectedButton = JButton("拉取选中")
    private val listModel = DefaultListModel<ProblemSummary>()
    private val resultList = JBList(listModel)
    private val statusLabel = JBLabel("输入关键词开始搜索（双击结果可预览题面）")

    init {
        keywordField.columns = 20

        resultList.cellRenderer = object : ColoredListCellRenderer<ProblemSummary>() {
            override fun customizeCellRenderer(
                list: JList<out ProblemSummary>,
                value: ProblemSummary,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                icon = null
                append("${value.pid}  ${value.name}")
            }
        }

        val inputRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 6))
        inputRow.add(JBLabel("关键词"))
        inputRow.add(keywordField)
        inputRow.add(searchButton)
        inputRow.add(fetchSelectedButton)

        val top = JPanel(BorderLayout())
        top.add(inputRow, BorderLayout.NORTH)
        top.add(statusLabel, BorderLayout.SOUTH)

        add(top, BorderLayout.NORTH)
        add(JBScrollPane(resultList), BorderLayout.CENTER)

        searchButton.addActionListener { doSearch() }
        fetchSelectedButton.addActionListener { fetchSelected() }

        resultList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val index = resultList.locationToIndex(e.point)
                if (index < 0 || index >= listModel.size()) return
                val bounds = resultList.getCellBounds(index, index) ?: return
                if (!bounds.contains(e.point)) return
                val problem = listModel.getElementAt(index)
                statusLabel.text = "加载题面 ${problem.pid}…"
                onPreview(problem.pid)
            }
        })
    }

    private fun doSearch() {
        val keyword = keywordField.text.trim()
        if (keyword.isEmpty()) {
            statusLabel.text = "关键词不能为空"
            return
        }
        statusLabel.text = "搜索中…"
        LuoguActions.search(
            project = project,
            keyword = keyword,
            onResult = { results ->
                listModel.clear()
                results.forEach { listModel.addElement(it) }
                if (results.isEmpty()) {
                    statusLabel.text = "未找到匹配的题目"
                } else {
                    statusLabel.text = "共 ${results.size} 条结果"
                    resultList.selectedIndex = 0
                }
            },
            onError = { msg -> statusLabel.text = msg },
        )
    }

    private fun fetchSelected() {
        val problem = resultList.selectedValue ?: run {
            statusLabel.text = "请先选择一条结果"
            return
        }
        statusLabel.text = "拉取 ${problem.pid} 中…"
        LuoguActions.fetchAndGenerate(
            project = project,
            pid = problem.pid,
            onResult = { text -> statusLabel.text = text },
            onError = { msg -> statusLabel.text = msg },
        )
    }
}
