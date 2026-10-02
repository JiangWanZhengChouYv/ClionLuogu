package com.user.clionluogu.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.user.clionluogu.ui.LuoguTabs

/** 打开工具窗口并切到「题目」页（本地题库索引与删除本题文件的入口）。 */
class ProblemIndexAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        LuoguTabs.openTab(project, LuoguTabs.TAB_PROBLEMS)
    }
}
