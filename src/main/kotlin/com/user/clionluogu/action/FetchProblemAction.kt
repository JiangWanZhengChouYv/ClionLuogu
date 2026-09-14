package com.user.clionluogu.action

import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.user.clionluogu.api.LuoguApiException
import com.user.clionluogu.api.LuoguApiService
import com.user.clionluogu.api.LuoguPidValidator
import com.user.clionluogu.service.ProblemFileGenService
import kotlinx.coroutines.runBlocking

/** 拉取洛谷题目并生成本地 .cpp / .in / .out 测试文件。 */
class FetchProblemAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // 1. 输入题号
        val input = Messages.showInputDialog(
            project,
            "输入题号（例如 P1001）:",
            "拉取洛谷题目",
            Messages.getQuestionIcon(),
        ) ?: return

        // 2. 格式校验（不发起网络）
        val pid = input.trim()
        if (!LuoguPidValidator.isValidPid(pid)) {
            notify(project, NotificationType.ERROR, "题号格式无效，示例：P1001")
            return
        }

        // 3. 后台拉取（绝不在 EDT）
        ApplicationManager.getApplication().executeOnPooledThread {
            val problem = try {
                runBlocking { LuoguApiService.getProblem(pid) }
            } catch (t: Throwable) {
                notify(project, NotificationType.ERROR, readableMessage(t))
                return@executeOnPooledThread
            }

            // 4. 回 EDT 生成文件并自动打开 cpp
            ApplicationManager.getApplication().invokeLater {
                val result = ProblemFileGenService.generate(project, pid, problem)
                result.cpp?.let { FileEditorManager.getInstance(project).openFile(it, true) }
                if (result.notices.isNotEmpty()) {
                    notify(project, NotificationType.INFORMATION, result.notices.joinToString("\n"))
                }
            }
        }
    }

    /** 将底层异常映射为可读提示。 */
    private fun readableMessage(t: Throwable): String {
        val msg = t.message ?: t.javaClass.simpleName
        return if (t is LuoguApiException) msg else "网络请求异常：$msg"
    }

    private fun notify(project: Project, type: NotificationType, content: String) {
        NOTIFICATION_GROUP.createNotification("拉取洛谷题目", content, type).notify(project)
    }

    companion object {
        private val NOTIFICATION_GROUP: NotificationGroup =
            NotificationGroupManager.getInstance().getNotificationGroup("ClionLuogu.Notifications")
    }
}