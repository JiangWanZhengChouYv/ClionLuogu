package com.user.clionluogu.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

/**
 * 「洛谷拉题」的设置页：代码模板、三个提醒开关、以及本地对拍用的编译器与编译参数。
 */
class LuoguConfigurable : Configurable {

    private var codeTemplateEditor: JBTextArea? = null
    private var punchReminderCheckBox: JBCheckBox? = null
    private var acCleanupCheckBox: JBCheckBox? = null
    private var judgeNotifyCheckBox: JBCheckBox? = null
    private var compilerPathField: JBTextField? = null
    private var compilerArgsField: JBTextField? = null

    override fun getDisplayName(): String = "洛谷拉题 / ClionLuogu"

    override fun createComponent(): JComponent {
        val settings = LuoguSettings.getInstance()

        val editor = JBTextArea().apply {
            setColumns(60)
            setRows(18)
            lineWrap = true
            text = settings.codeTemplate
        }
        codeTemplateEditor = editor

        val punchBox = JBCheckBox("启动时提醒今日未打卡（通知里有「打卡」按钮，点了才发请求）")
        punchBox.isSelected = settings.punchReminderEnabled
        punchReminderCheckBox = punchBox

        val cleanupBox = JBCheckBox("题目 AC 后询问是否删除本题文件（.cpp / .md / 样例 / 反例目录）")
        cleanupBox.isSelected = settings.acCleanupEnabled
        acCleanupCheckBox = cleanupBox

        val notifyBox = JBCheckBox("评测判为非 AC 终态（WA / RE / TLE / MLE / CE…）时发通知")
        notifyBox.isSelected = settings.judgeNotifyEnabled
        judgeNotifyCheckBox = notifyBox

        // 对拍是自己调编译器，不复用 CLion 的工具链（那要依赖 CMake 模块，会把插件绑死在 CLion）
        val compilerPath = JBTextField().apply {
            toolTipText = "留空则依次找 PATH 上的 clang++ / g++ / c++，再退到 IDE 自带的 MinGW（Windows）"
            text = settings.compareCompilerPath.orEmpty()
        }
        compilerPathField = compilerPath

        val compilerArgs = JBTextField().apply {
            toolTipText = "多个参数用空格分隔；改动后下一次「编译并对拍」生效"
            text = settings.compareCompilerArgs
        }
        compilerArgsField = compilerArgs

        return FormBuilder.createFormBuilder()
            .addComponent(punchBox)
            .addComponent(cleanupBox)
            .addComponent(notifyBox)
            .addSeparator()
            .addComponent(JBLabel("本地对拍：编译器绝对路径（留空 = 自动找）："))
            .addComponent(compilerPath)
            .addComponent(JBLabel("本地对拍：编译参数（默认 ${LuoguSettings.DEFAULT_COMPILER_ARGS}）："))
            .addComponent(compilerArgs)
            .addSeparator()
            .addComponent(JBLabel("C++ 代码模板（生成 .cpp 文件时使用）："))
            .addComponentFillVertically(editor, 0)
            .panel
    }

    override fun isModified(): Boolean {
        val editor = codeTemplateEditor ?: return false
        val punchBox = punchReminderCheckBox ?: return false
        val cleanupBox = acCleanupCheckBox ?: return false
        val notifyBox = judgeNotifyCheckBox ?: return false
        val pathField = compilerPathField ?: return false
        val argsField = compilerArgsField ?: return false
        val settings = LuoguSettings.getInstance()
        return editor.text != settings.codeTemplate ||
            punchBox.isSelected != settings.punchReminderEnabled ||
            cleanupBox.isSelected != settings.acCleanupEnabled ||
            notifyBox.isSelected != settings.judgeNotifyEnabled ||
            pathField.text.trim() != settings.compareCompilerPath.orEmpty() ||
            // 空 = 取默认值，两边都按同一个口径比，否则「填回默认」会被判成已改动
            argsField.text.ifBlank { LuoguSettings.DEFAULT_COMPILER_ARGS } != settings.compareCompilerArgs
    }

    override fun apply() {
        val editor = codeTemplateEditor ?: return
        val punchBox = punchReminderCheckBox ?: return
        val cleanupBox = acCleanupCheckBox ?: return
        val notifyBox = judgeNotifyCheckBox ?: return
        val pathField = compilerPathField ?: return
        val argsField = compilerArgsField ?: return
        val settings = LuoguSettings.getInstance()
        settings.codeTemplate = editor.text
        settings.punchReminderEnabled = punchBox.isSelected
        settings.acCleanupEnabled = cleanupBox.isSelected
        settings.judgeNotifyEnabled = notifyBox.isSelected
        settings.compareCompilerPath = pathField.text.trim().takeIf { it.isNotEmpty() }
        settings.compareCompilerArgs = argsField.text
    }

    override fun reset() {
        val editor = codeTemplateEditor ?: return
        val punchBox = punchReminderCheckBox ?: return
        val cleanupBox = acCleanupCheckBox ?: return
        val notifyBox = judgeNotifyCheckBox ?: return
        val pathField = compilerPathField ?: return
        val argsField = compilerArgsField ?: return
        val settings = LuoguSettings.getInstance()
        editor.text = settings.codeTemplate
        punchBox.isSelected = settings.punchReminderEnabled
        cleanupBox.isSelected = settings.acCleanupEnabled
        notifyBox.isSelected = settings.judgeNotifyEnabled
        pathField.text = settings.compareCompilerPath.orEmpty()
        argsField.text = settings.compareCompilerArgs
    }
}
