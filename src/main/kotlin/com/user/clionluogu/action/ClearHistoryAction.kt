package com.user.clionluogu.action

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager
import com.user.clionluogu.ui.LuoguTabs
import com.user.clionluogu.ui.LuoguToolWindow

class ClearHistoryAction : AnAction("清空提交记录", "清空本地提交历史列表", AllIcons.Actions.GC) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(LuoguTabs.TOOL_WINDOW_ID) ?: return
        val content = toolWindow.contentManager.findContent(LuoguTabs.TAB_EVAL) ?: return
        content.getUserData(LuoguToolWindow.WINDOW_KEY)?.clearHistory()
    }
}
