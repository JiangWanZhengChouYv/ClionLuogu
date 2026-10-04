package com.user.clionluogu.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.user.clionluogu.api.LuoguPidValidator
import com.user.clionluogu.service.LuoguActions
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

/** 拉取页：输入题号 → 后台拉取并生成测试文件，结果展示在页面内的只读文本区；也可只查看该题题解。 */
class FetchPanel(
    private val project: Project,
    private val onSolutions: (String) -> Unit,
) : JPanel(BorderLayout()) {

    private val pidField = JBTextField()
    private val fetchButton = JButton("拉取")
    private val solutionsButton = JButton("查看题解")
    private val resultArea = JBTextArea()

    init {
        pidField.text = "P1001"
        pidField.columns = 16

        resultArea.isEditable = false
        resultArea.lineWrap = true
        resultArea.border = JBUI.Borders.empty(6)
        resultArea.emptyText.appendText("输入题号点「拉取」：会生成 Pxxx.cpp / Pxxx.md 与全部样例")

        // WrapLayout 而不是 FlowLayout：普通 FlowLayout 折行后 preferredSize 只算一行，
        // 窄侧边栏里第二行会被整块裁掉（1.7.1 真踩过）
        val inputRow = JPanel(WrapLayout(FlowLayout.LEFT, 6, 4))
        inputRow.border = JBUI.Borders.empty(6, 8, 0, 8)
        inputRow.add(JBLabel("题号"))
        inputRow.add(pidField)
        inputRow.add(fetchButton)
        inputRow.add(solutionsButton)

        add(inputRow, BorderLayout.NORTH)
        add(JBScrollPane(resultArea), BorderLayout.CENTER)

        fetchButton.addActionListener { doFetch() }
        // 回车就该干这件正事：题号是「抄来的」，敲完直接回车比挪鼠标点按钮快得多
        pidField.addActionListener { doFetch() }
        solutionsButton.addActionListener { doShowSolutions() }
    }

    /** 结果区的一句话开关：出错时染成错误色，别和成功信息长一个样。 */
    private fun show(text: String, error: Boolean = false) = StatusRow.apply(resultArea, text, error)

    private fun doFetch() {
        val pid = pidField.text.trim()
        if (!LuoguPidValidator.isValidPid(pid)) {
            show("题号格式无效", error = true)
            return
        }
        show("拉取中…")
        LuoguActions.fetchAndGenerate(
            project = project,
            pid = pid,
            onResult = { text -> show(text) },
            onError = { msg -> show(msg, error = true) },
        )
    }

    /** 只查看题解：不生成任何文件，交给预览页的题解模式拉取并渲染。 */
    private fun doShowSolutions() {
        val pid = pidField.text.trim()
        if (!LuoguPidValidator.isValidPid(pid)) {
            show("题号格式无效", error = true)
            return
        }
        show("已在「预览」页打开 $pid 的题解")
        onSolutions(pid)
    }
}
