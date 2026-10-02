package com.user.clionluogu.service

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.user.clionluogu.api.SubmissionStatus
import com.user.clionluogu.api.statusTextOf
import com.user.clionluogu.settings.LuoguSettings
import com.user.clionluogu.ui.LuoguTabs

/**
 * 评测进入**非 AC 终态**时发一条 balloon 通知，让人可以切去写别的代码。
 *
 * 三条判据（在 [LuoguToolWindow.updateSubmission] 里一起用）缺一不可：终态、不是 AC、
 * 且上一份快照还不是终态——同一次终态会被 `onUpdate` 与 `onDone` 各回调一次，
 * 少了跃迁守卫就会弹两条一模一样的通知。AC 也不在这里发：1.6.0 已经给 AC 做了
 * 清理模态框，再叠一层通知只会吵。
 *
 * **本文件禁止使用 `Messages.*`**：通知挂在轮询回调链上，弹模态框会和 AC 的清理弹窗抢 EDT。
 */
object JudgeNotifyService {

    private const val GROUP_ID = "ClionLuogu.Notifications"
    private const val AC_CODE = 12

    fun notifyFailure(project: Project, pid: String, status: SubmissionStatus) {
        if (!LuoguSettings.getInstance().judgeNotifyEnabled) return

        val head = buildString {
            append("记录 #").append(status.rid)
            ScoreTotals.totalScoreText(ScoreTotals.totalScoreOf(status)).takeIf { it.isNotEmpty() }?.let {
                append(" ·").append(it).append("（各子任务合计）")
            }
            status.timeMs?.let { append(" · 用时 ").append(it).append(" ms") }
            status.memoryKb?.let { append(" · 内存 ").append(it).append(" KB") }
        }
        val body = buildString {
            append(head)
            firstFailingLabel(status)?.let { append('\n').append(it) }
            status.compileError?.lineSequence()?.firstOrNull { it.isNotBlank() }?.let {
                append("\n编译错误：").append(it.trim())
            }
            append("\n到「评测」页看逐测试点详情。")
        }

        val notification = group().createNotification(
            "$pid ${status.statusText ?: "评测结束"}",
            body,
            NotificationType.ERROR,
        )
        notification.addAction(
            NotificationAction.createSimpleExpiring("查看") {
                notification.expire()
                LuoguTabs.openTab(project, LuoguTabs.TAB_EVAL)
                // 页签内容可能是这一轮之后才建好，下一轮 EDT 再选记录
                ApplicationManager.getApplication().invokeLater {
                    LuoguTabs.evalWindow(project)?.selectSubmission(status.rid)
                }
            },
        )
        notification.notify(project)
    }

    /**
     * 首个未通过的测试点 / 子任务。编号一律 `id + 1`，与评测页方块的编号方式一致。
     * 洛谷不下发 `detail` 时（只有子任务得分文本）就退化成报第一个非满分子任务。
     */
    private fun firstFailingLabel(status: SubmissionStatus): String? {
        status.subtaskResults.forEach { sub ->
            sub.testCases.firstOrNull { it.status != AC_CODE }?.let { tc ->
                return "首个未通过测试点 #${tc.id + 1}：${statusTextOf(tc.status)}" +
                    tc.score?.let { "（得分 $it）" }.orEmpty()
            }
            sub.status?.takeIf { it != AC_CODE }?.let {
                return "首个未通过子任务 #${sub.id + 1}：${statusTextOf(it)}"
            }
        }
        return status.subtaskInfo.firstOrNull { !it.contains("100") }?.let { "首个未通过：$it" }
    }

    private fun group() = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
}
