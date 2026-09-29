package com.user.clionluogu.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.user.clionluogu.api.LuoguPidValidator
import com.user.clionluogu.service.LuoguActions
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

/** 拉取页：输入题号 → 后台拉取并生成测试文件，结果展示在页面内的只读文本区。 */
class FetchPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val pidField = JBTextField()
    private val fetchButton = JButton("拉取")
    private val resultArea = JBTextArea()

    init {
        pidField.text = "P1001"
        pidField.columns = 16

        resultArea.isEditable = false
        resultArea.lineWrap = true
        resultArea.border = JBUI.Borders.empty(6)

        val inputRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 6))
        inputRow.add(JBLabel("题号"))
        inputRow.add(pidField)
        inputRow.add(fetchButton)

        add(inputRow, BorderLayout.NORTH)
        add(JBScrollPane(resultArea), BorderLayout.CENTER)

        fetchButton.addActionListener { doFetch() }
    }

    private fun doFetch() {
        val pid = pidField.text.trim()
        if (!LuoguPidValidator.isValidPid(pid)) {
            resultArea.text = "题号格式无效"
            return
        }
        resultArea.text = "拉取中…"
        LuoguActions.fetchAndGenerate(
            project = project,
            pid = pid,
            onResult = { text -> resultArea.text = text },
            onError = { msg -> resultArea.text = msg },
        )
    }
}
