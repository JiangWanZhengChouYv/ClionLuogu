package com.user.clionluogu.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

/**
 * 「洛谷拉题」的设置页：生成 .cpp 用的 C++ 代码模板、打卡提醒开关、AC 后清理提醒开关。
 */
class LuoguConfigurable : Configurable {

    private var codeTemplateEditor: JBTextArea? = null
    private var punchReminderCheckBox: JBCheckBox? = null
    private var acCleanupCheckBox: JBCheckBox? = null

    override fun getDisplayName(): String = "洛谷拉题 / ClionLuogu"

    override fun createComponent(): JComponent {
        val editor = JBTextArea()
        editor.setColumns(60)
        editor.setRows(18)
        editor.lineWrap = true
        editor.text = LuoguSettings.getInstance().codeTemplate
        codeTemplateEditor = editor

        val settings = LuoguSettings.getInstance()

        val punchBox = JBCheckBox("启动时提醒今日未打卡（通知里有「打卡」按钮，点了才发请求）")
        punchBox.isSelected = settings.punchReminderEnabled
        punchReminderCheckBox = punchBox

        val cleanupBox = JBCheckBox("题目 AC 后询问是否删除本题文件（.cpp / .md / 样例目录）")
        cleanupBox.isSelected = settings.acCleanupEnabled
        acCleanupCheckBox = cleanupBox

        return FormBuilder.createFormBuilder()
            .addComponent(punchBox)
            .addComponent(cleanupBox)
            .addSeparator()
            .addComponent(
                JBLabel("C++ 代码模板（生成 .cpp 文件时使用）：")
            )
            .addComponentFillVertically(editor, 0)
            .panel
    }

    override fun isModified(): Boolean {
        val editor = codeTemplateEditor ?: return false
        val punchBox = punchReminderCheckBox ?: return false
        val cleanupBox = acCleanupCheckBox ?: return false
        val settings = LuoguSettings.getInstance()
        return editor.text != settings.codeTemplate ||
            punchBox.isSelected != settings.punchReminderEnabled ||
            cleanupBox.isSelected != settings.acCleanupEnabled
    }

    override fun apply() {
        val editor = codeTemplateEditor ?: return
        val punchBox = punchReminderCheckBox ?: return
        val cleanupBox = acCleanupCheckBox ?: return
        val settings = LuoguSettings.getInstance()
        settings.codeTemplate = editor.text
        settings.punchReminderEnabled = punchBox.isSelected
        settings.acCleanupEnabled = cleanupBox.isSelected
    }

    override fun reset() {
        val editor = codeTemplateEditor ?: return
        val punchBox = punchReminderCheckBox ?: return
        val cleanupBox = acCleanupCheckBox ?: return
        val settings = LuoguSettings.getInstance()
        editor.text = settings.codeTemplate
        punchBox.isSelected = settings.punchReminderEnabled
        cleanupBox.isSelected = settings.acCleanupEnabled
    }
}
