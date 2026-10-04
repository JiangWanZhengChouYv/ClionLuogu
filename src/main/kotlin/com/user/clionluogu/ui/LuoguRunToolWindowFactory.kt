package com.user.clionluogu.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.user.clionluogu.service.SubmissionTracker
import javax.swing.JComponent

/**
 * 底部「运行侧」工具窗口：自测 / 对拍 / 提交。
 *
 * 1.8.0 把「跑代码」这件事从侧边栏搬到底部，和 CLion 自己的 Run 窗口同一个位置 ——
 * 写代码 → 点运行 → 看输出是一条直线，而不是在右边的页签堆里翻。
 * 「题目侧」（评测 / 拉取 / 搜索 / 预览 / 题目 / 登录）留在左侧的 [LuoguToolWindowFactory]。
 */
class LuoguRunToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()
        val contentManager = toolWindow.contentManager

        fun add(component: JComponent, name: String) {
            val content = contentFactory.createContent(component, name, false)
            content.setPreferredFocusableComponent(component)
            contentManager.addContent(content)
        }

        add(SelfTestPanel(project), LuoguTabs.TAB_SELFTEST)
        add(SampleComparePanel(project), LuoguTabs.TAB_COMPARE)
        add(
            SubmitPanel(project) { pid, rid, lang, code ->
                // 记账走 tracker：左侧的评测页可能压根没被打开过，记录不能因此不落盘、不轮询
                SubmissionTracker.track(project, pid, rid, lang, code)
            },
            LuoguTabs.TAB_SUBMIT,
        )
    }
}
