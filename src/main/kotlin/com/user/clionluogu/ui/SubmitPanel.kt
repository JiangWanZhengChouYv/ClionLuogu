package com.user.clionluogu.ui

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.user.clionluogu.api.LuoguApiService
import com.user.clionluogu.api.LuoguPidValidator
import com.user.clionluogu.service.LuoguActions
import com.user.clionluogu.storage.SecureCookieStore
import java.awt.BorderLayout
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

        val bottom = JPanel(BorderLayout())
        bottom.add(submitButton, BorderLayout.WEST)
        bottom.add(statusLabel, BorderLayout.CENTER)

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

    private fun refreshPreview() {
        readCurrentCode()
        if (FileEditorManager.getInstance(project).selectedTextEditor == null) {
            statusLabel.text = "未打开编辑器，无代码可预览"
        }
    }

    private fun doSubmit() {
        if (!SecureCookieStore.hasLogin()) {
            statusLabel.text = "请先登录"
            return
        }
        val pid = pidField.text.trim()
        if (!LuoguPidValidator.isValidPid(pid)) {
            statusLabel.text = "题号格式无效"
            return
        }
        val langName = (langCombo.selectedItem as? String) ?: run {
            statusLabel.text = "未知语言版本"
            return
        }
        val lang = LuoguApiService.LANGUAGES[langName] ?: run {
            statusLabel.text = "未知语言版本"
            return
        }
        val code = readCurrentCode()
        if (code.isEmpty()) {
            statusLabel.text = "未打开编辑器或文件为空，无代码可提交"
            return
        }

        statusLabel.text = "提交中…"
        LuoguActions.submit(
            project = project,
            pid = pid,
            lang = lang,
            code = code,
            captchaPrompter = { CaptchaPrompt.promptCaptcha(project, it) },
            onResult = { rid ->
                statusLabel.text = "已提交到 $pid，记录 id=$rid"
                onSubmitted(pid, rid, langName, code)
            },
            onError = { msg -> statusLabel.text = msg },
        )
    }

    private fun guessedPid(): String? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
        val name = file.name
        return if (name.endsWith(".cpp")) name.removeSuffix(".cpp") else null
    }
}
