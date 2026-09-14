package com.user.clionluogu.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

/**
 * 「洛谷拉题」的设置页：编辑生成 .cpp 时使用的 C++ 代码模板。
 */
class LuoguConfigurable : Configurable {

    private var codeTemplateEditor: JBTextArea? = null

    override fun getDisplayName(): String = "洛谷拉题 / ClionLuogu"

    override fun createComponent(): JComponent {
        val editor = JBTextArea()
        editor.setColumns(60)
        editor.setRows(18)
        editor.lineWrap = true
        editor.text = LuoguSettings.getInstance().codeTemplate
        codeTemplateEditor = editor

        return FormBuilder.createFormBuilder()
            .addComponent(
                JBLabel("C++ 代码模板（生成 .cpp 文件时使用）：")
            )
            .addComponentFillVertically(editor, 0)
            .panel
    }

    override fun isModified(): Boolean {
        val editor = codeTemplateEditor ?: return false
        return editor.text != LuoguSettings.getInstance().codeTemplate
    }

    override fun apply() {
        val editor = codeTemplateEditor ?: return
        LuoguSettings.getInstance().codeTemplate = editor.text
    }

    override fun reset() {
        val editor = codeTemplateEditor ?: return
        editor.text = LuoguSettings.getInstance().codeTemplate
    }
}