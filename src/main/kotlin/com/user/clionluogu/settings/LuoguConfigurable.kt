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

        // 编译器认的是 CLion 项目实际用的那一个（读 CMake 自己写的 CMakeCache.txt），
        // 不去调 com.jetbrains.cmake.* 那些 API —— 那会把插件绑死在某个 CLion 版本上。
        val compilerPath = JBTextField().apply {
            toolTipText = "留空 = 用 CLion 项目实际在用的编译器（读自 CMakeCache.txt 的 CMAKE_CXX_COMPILER）；" +
                "CLion 那边没有可用的（项目还没配置过 CMake）才退到 PATH 上的 clang++ / g++ / c++，" +
                "再退到 IDE 自带的 MinGW（Windows）。\n" +
                "填了绝对路径就**优先于 CLion 的那一个**；mac 上想换成 GCC，" +
                "brew install gcc 之后把带版本号的那个填进来（例如 /opt/homebrew/bin/g++-16，brew 没有裸 g++）。"
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
            .addComponent(JBLabel("本地编译：编译器绝对路径（留空 = 用 CLion 项目实际用的那个）："))
            .addComponent(compilerPath)
            .addComponent(JBLabel("本地编译：编译参数（默认 ${LuoguSettings.DEFAULT_COMPILER_ARGS}）："))
            .addComponent(compilerArgs)
            .addSeparator()
            .addComponent(
                JBLabel(
                    "C++ 代码模板（生成 .cpp 文件时使用）——留空时头文件按当前编译器给：" +
                        "clang（mac 默认）用 <iostream> <vector> <algorithm>，GCC 用 <bits/stdc++.h>。" +
                        "改过内容就一直用你这一份。",
                ),
            )
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
        // 模板这一项要按「他到底改没改」判，而不是按「文本跟当前默认一不一样」：
        // 默认本身跟着编译器变（clang→标准头，GCC→bits），页面开着的时候后台探测可能把它换掉，
        // 那时文本与 `settings.codeTemplate` 不等，但**他没有动过一个字**。
        val looksBuiltIn = editor.text == LuoguSettings.DEFAULT_CODE_TEMPLATE ||
            editor.text == LuoguSettings.BITS_CODE_TEMPLATE
        val templateDirty = editor.text != settings.codeTemplate && !(looksBuiltIn && !settings.hasCustomCodeTemplate)
        return templateDirty ||
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
