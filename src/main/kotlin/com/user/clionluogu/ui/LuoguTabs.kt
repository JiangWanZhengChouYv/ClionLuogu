package com.user.clionluogu.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

/** 洛谷工具窗口的页签常量与切换入口。 */
object LuoguTabs {

    const val TOOL_WINDOW_ID = "ClionLuogu"
    const val TAB_EVAL = "评测"
    const val TAB_FETCH = "拉取"
    const val TAB_SEARCH = "搜索"
    const val TAB_PREVIEW = "预览"
    const val TAB_SUBMIT = "提交"
    const val TAB_LOGIN = "登录"

    /** 打开工具窗口并选中指定页签。内容创建可能异步，故找不到时下一轮 EDT 再试一次。 */
    fun openTab(project: Project, tabName: String) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        toolWindow.activate(null)

        val contentManager = toolWindow.contentManager
        contentManager.findContent(tabName)?.let {
            contentManager.setSelectedContent(it)
            return
        }

        ApplicationManager.getApplication().invokeLater {
            contentManager.findContent(tabName)?.let { contentManager.setSelectedContent(it) }
        }
    }
}
