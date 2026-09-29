package com.user.clionluogu.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.user.clionluogu.ui.LuoguTabs

/** 打开工具窗口并切到「拉取」页。 */
class FetchProblemAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        LuoguTabs.openTab(project, LuoguTabs.TAB_FETCH)
    }
}
