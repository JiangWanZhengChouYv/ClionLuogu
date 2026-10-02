package com.user.clionluogu.ui

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.user.clionluogu.api.LuoguApiService
import com.user.clionluogu.api.LuoguPidValidator
import com.user.clionluogu.service.LuoguActions
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JPanel

/** 提交页：确认题号与语言版本，预览当前编辑器代码并提交。 */
class SubmitPanel(
    private val project: Project,
    private val onSubmitted: (pid: String, rid: String, lang: String, code: String) -> Unit,
) : JPanel(BorderLayout()) {

    private val pidField = JBTextField()
    private val langCombo = ComboBox<String>()
    private val previewArea = JBTextArea()
    private val refreshButton = JButton("刷新预览")
    private val submitButton = JButton("提交")
    private val statusLabel = JBLabel(" ")

    init {
        pidField.text = guessedPid() ?: "P1001"

        val langs = LuoguApiService.LANGUAGES
        langCombo.model = DefaultComboBoxModel(langs.keys.toTypedArray())
        val defaultLang = "C++17 (O2)"
        langCombo.selectedItem = if (langs.containsKey(defaultLang)) defaultLang else langs.keys.firstOrNull()

        previewArea.isEditable = false
        previewArea.lineWrap = false
        previewArea.border = JBUI.Borders.empty(6)
        // 预览的是 C++ 源码，用等宽字体；不折行，长行横向滚
        previewArea.font = DetailPanel.monoFont()
        previewArea.emptyText.appendText("这里显示将要提交的代码（当前编辑器的那份）")

        val form = JPanel(GridBagLayout())
        form.border = JBUI.Borders.empty(8)
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(4)
        }
        form.add(JBLabel("题号"), gbc)
        gbc.gridy++
        form.add(pidField, gbc)
        gbc.gridy++
        form.add(JBLabel("语言版本"), gbc)
        gbc.gridy++
        form.add(langCombo, gbc)
        gbc.gridy++
        form.add(refreshButton, gbc)

        // 不用 BorderLayout 的 WEST + CENTER：窄侧边栏下状态文字会把提交按钮挤出去
        val bottom = JPanel(WrapLayout(FlowLayout.LEFT, 6, 2))
        bottom.border = JBUI.Borders.empty(4, 8)
        bottom.add(submitButton)
        bottom.add(statusLabel)

        add(form, BorderLayout.NORTH)
        add(JBScrollPane(previewArea), BorderLayout.CENTER)
        add(bottom, BorderLayout.SOUTH)

        refreshButton.addActionListener { refreshPreview() }
        submitButton.addActionListener { doSubmit() }
    }

    override fun addNotify() {
        super.addNotify()
        refreshPreview()
    }

    private fun readCurrentCode(): String {
        val text = FileEditorManager.getInstance(project).selectedTextEditor?.document?.text ?: ""
        previewArea.text = text
        return text
    }

    /** 出错的话染成错误色——原来成功与失败长一个样，得读完才知道是报错。 */
    private fun setStatus(text: String, error: Boolean = false) {
        statusLabel.text = text
        statusLabel.toolTipText = text.takeIf { error }
        statusLabel.foreground = if (error) UIUtil.getErrorForeground() else UIUtil.getLabelForeground()
    }

    private fun refreshPreview() {
        readCurrentCode()
        if (FileEditorManager.getInstance(project).selectedTextEditor == null) {
            setStatus("未打开编辑器，无代码可预览", error = true)
        }
    }

    private fun doSubmit() {
        val pid = pidField.text.trim()
        if (!LuoguPidValidator.isValidPid(pid)) {
            setStatus("题号格式无效", error = true)
            return
        }
        val langName = (langCombo.selectedItem as? String) ?: run {
            setStatus("未知语言版本", error = true)
            return
        }
        val lang = LuoguApiService.LANGUAGES[langName] ?: run {
            setStatus("未知语言版本", error = true)
            return
        }
        val code = readCurrentCode()
        if (code.isEmpty()) {
            setStatus("未打开编辑器或文件为空，无代码可提交", error = true)
            return
        }

        setStatus("提交中…")
        LuoguActions.submit(
            project = project,
            pid = pid,
            lang = lang,
            code = code,
            captchaPrompter = { CaptchaPrompt.promptCaptcha(project, it) },
            onResult = { rid ->
                setStatus("已提交到 $pid，记录 id=$rid")
                onSubmitted(pid, rid, langName, code)
            },
            onError = { msg -> setStatus(msg, error = true) },
        )
    }

    private fun guessedPid(): String? = CurrentFilePid.guess(project)
}
