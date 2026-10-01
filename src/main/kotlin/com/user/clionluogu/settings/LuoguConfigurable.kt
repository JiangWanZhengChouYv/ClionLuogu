package com.user.clionluogu.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

/**
 * 「洛谷拉题」的设置页：编辑生成 .cpp 时使用的 C++ 代码模板，以及打卡提醒开关。
 */
class LuoguConfigurable : Configurable {

    private var codeTemplateEditor: JBTextArea? = null
    private var punchReminderCheckBox: JBCheckBox? = null

    override fun getDisplayName(): String = "洛谷拉题 / ClionLuogu"

    override fun createComponent(): JComponent {
        val editor = JBTextArea()
        editor.setColumns(60)
        editor.setRows(18)
        editor.lineWrap = true
        editor.text = LuoguSettings.getInstance().codeTemplate
        codeTemplateEditor = editor

        val checkBox = JBCheckBox("启动时提醒今日未打卡（通知里有「打卡」按钮，点了才发请求）")
        checkBox.isSelected = LuoguSettings.getInstance().punchReminderEnabled
        punchReminderCheckBox = checkBox

        return FormBuilder.createFormBuilder()
            .addComponent(checkBox)
            .addSeparator()
            .addComponent(
                JBLabel("C++ 代码模板（生成 .cpp 文件时使用）：")
            )
            .addComponentFillVertically(editor, 0)
            .panel
    }

    override fun isModified(): Boolean {
        val editor = codeTemplateEditor ?: return false
        val checkBox = punchReminderCheckBox ?: return false
        val settings = LuoguSettings.getInstance()
        return editor.text != settings.codeTemplate || checkBox.isSelected != settings.punchReminderEnabled
    }

    override fun apply() {
        val editor = codeTemplateEditor ?: return
        val checkBox = punchReminderCheckBox ?: return
        val settings = LuoguSettings.getInstance()
        settings.codeTemplate = editor.text
        settings.punchReminderEnabled = checkBox.isSelected
    }

    override fun reset() {
        val editor = codeTemplateEditor ?: return
        val checkBox = punchReminderCheckBox ?: return
        val settings = LuoguSettings.getInstance()
        editor.text = settings.codeTemplate
        checkBox.isSelected = settings.punchReminderEnabled
    }
}
