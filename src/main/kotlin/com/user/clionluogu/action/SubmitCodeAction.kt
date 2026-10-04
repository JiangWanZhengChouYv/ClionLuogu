package com.user.clionluogu.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.user.clionluogu.ui.LuoguTabs

/** 打开底部「运行侧」窗口并切到提交页（1.8.0 起提交与自测、对拍同在一处）。 */
class SubmitCodeAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        LuoguTabs.openTab(project, LuoguTabs.TAB_SUBMIT, LuoguTabs.RUN_TOOL_WINDOW_ID)
    }
}
