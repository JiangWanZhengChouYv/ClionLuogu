package com.user.clionluogu.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * 侧边工具窗口工厂：注册评测工具窗口。
 */
class LuoguToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val window = LuoguToolWindow(project)
        LuoguToolWindow.INSTANCE = window
        toolWindow.contentManager.addContent(
            ContentFactory.getInstance().createContent(window.content, "评测", false)
        )
    }
}