package com.user.clionluogu.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.user.clionluogu.ui.LuoguTabs

/** 打开底部「运行侧」窗口并切到对拍页（1.8.0 起对拍不在侧边栏了）。 */
class CompareAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        LuoguTabs.openTab(project, LuoguTabs.TAB_COMPARE, LuoguTabs.RUN_TOOL_WINDOW_ID)
    }
}
