package com.user.clionluogu.service

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.user.clionluogu.settings.LuoguSettings
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 洛谷每日打卡的提醒与执行入口。
 *
 * 立场很明确：**插件永不自动打卡**——启动检查只发一条 balloon 通知，
 * 只有你点了通知里的「打卡」按钮（或按侧边栏标题栏的打卡图标）才会发出那个 POST。
 * 也不做定时轮询，一次 IDE 生命周期最多提醒一次。
 */
object PunchReminder {

    private const val GROUP_ID = "ClionLuogu.Notifications"

    /** 多开项目窗口时避免重复弹通知（进程级，重启后重来）。 */
    private val notifiedThisSession = AtomicBoolean(false)

    /** 启动调用：本地已知今天处理过就完全不联网跳过。 */
    fun remindOnStartup(project: Project) {
        val settings = LuoguSettings.getInstance()
        if (!settings.punchReminderEnabled) return
        val today = today()
        if (settings.lastPunchDate == today) return
        if (!notifiedThisSession.compareAndSet(false, true)) return

        // 网络异常、首页被反爬打成空壳都静默：不该在启动时打扰
        LuoguActions.checkPunch(
            onResult = { state -> if (state.punchable) askToPunch(project, today) },
            onError = { },
        )
    }

    /** 标题栏手动入口：不必先查首页状态，直接打卡——服务器自报告会与否。 */
    fun punchNow(project: Project) = doPunch(project, today())

    private fun askToPunch(project: Project, today: String) {
        val notification = group().createNotification(
            "洛谷今日未打卡",
            "点「打卡」才会发请求（本插件不自动打卡）",
            NotificationType.INFORMATION,
        )
        notification.addAction(
            NotificationAction.createSimpleExpiring("打卡") {
                notification.expire()
                doPunch(project, today)
            },
        )
        notification.notify(project)
    }

    private fun doPunch(project: Project, today: String) {
        LuoguActions.punch(
            onResult = { result ->
                when (result.code) {
                    200 -> {
                        LuoguSettings.getInstance().lastPunchDate = today
                        // 成功时 message 是服务端给的当日运势文案，直接转述
                        notify(
                            project, "打卡成功",
                            result.message.orEmpty().ifBlank { "今日已打卡" },
                            NotificationType.INFORMATION,
                        )
                    }
                    // 「今天已经打过卡了」：同样记上日期，今天不再提醒
                    201 -> {
                        LuoguSettings.getInstance().lastPunchDate = today
                        notify(
                            project, "今天已经打过卡了",
                            result.message.orEmpty().ifBlank { "服务器提示今日已打卡" },
                            NotificationType.INFORMATION,
                        )
                    }
                    401 -> notify(
                        project, "登录态已失效",
                        "洛谷拒绝了打卡（code=401）。请到「登录」页重新填写 __client_id 与 _uid",
                        NotificationType.WARNING,
                    )
                    else -> notify(
                        project, "打卡未成功",
                        "服务器返回 code=${result.code}${result.message?.let { "，$it" }.orEmpty()}",
                        NotificationType.WARNING,
                    )
                }
            },
            onError = { msg -> notify(project, "打卡失败", msg, NotificationType.WARNING) },
        )
    }

    private fun today(): String = LocalDate.now().toString()

    private fun group() = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)

    private fun notify(project: Project, title: String, text: String, type: NotificationType) {
        group().createNotification(title, text, type).notify(project)
    }
}

/** 启动钩子：真正的工作都丢给 [LuoguActions] 的后台线程，这里只负责挂上去。 */
class PunchReminderActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        PunchReminder.remindOnStartup(project)
    }
}
