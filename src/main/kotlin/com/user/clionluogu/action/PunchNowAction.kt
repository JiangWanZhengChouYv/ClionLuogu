package com.user.clionluogu.action

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.user.clionluogu.service.PunchReminder

/**
 * 手动打卡入口：与「清空提交记录」并排挂在工具窗口标题栏，错过启动通知时用。
 *
 * 与通知按钮一样，点一下才发请求——插件不做自动打卡。
 */
class PunchNowAction : AnAction(
    "洛谷每日打卡",
    "查询今日打卡状态，确认后发一次打卡请求（不自动打卡）",
    AllIcons.Actions.Checked,
) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        PunchReminder.punchNow(project)
    }
}
