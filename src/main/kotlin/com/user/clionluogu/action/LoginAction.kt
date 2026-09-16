package com.user.clionluogu.action

import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.user.clionluogu.api.LuoguApiService
import com.user.clionluogu.api.LuoguHttpClient
import com.user.clionluogu.storage.SecureCookieStore
import kotlinx.coroutines.runBlocking
import java.util.LinkedHashMap

/** 登录洛谷：粘贴 Cookie 登录，或清除已保存登录态。 */
class LoginAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // 1. 粘贴 Cookie 串
        val pasted = Messages.showMultilineInputDialog(
            project,
            EXPLAIN_TEXT,
            "登录洛谷（粘贴 Cookie）",
            "",
            null,
            null,
        ) ?: return

        val cookieText = pasted.trim()
        if (cookieText.isEmpty()) {
            handleLogout(project)
            return
        }

        // 2. 解析 cookie
        val map = parseCookies(cookieText)

        // 3. 校验充分性（不发网络）
        val hasClientId = !map["__client_id"].isNullOrBlank()
        val hasUid = !map["_uid"].isNullOrBlank()
        if (!hasClientId && !hasUid) {
            notify(project, NotificationType.ERROR, "Cookie 缺少 __client_id / _uid")
            return
        }
        if (!hasClientId) {
            notify(project, NotificationType.ERROR, "Cookie 缺少 __client_id")
            return
        }
        if (!hasUid) {
            notify(project, NotificationType.ERROR, "Cookie 缺少 _uid")
            return
        }

        // 4. 保存并注入
        SecureCookieStore.save(map)
        LuoguHttpClient.injectCookies(map)

        // 5. 后台校验登录态 → 回 EDT 通知
        ApplicationManager.getApplication().executeOnPooledThread {
            val name = runCatching { runBlocking { LuoguApiService.getCurrentUser() } }.getOrNull()
            ApplicationManager.getApplication().invokeLater {
                if (!name.isNullOrBlank()) {
                    notify(project, NotificationType.INFORMATION, "已登录：$name")
                } else {
                    notify(
                        project,
                        NotificationType.WARNING,
                        "Cookie 已保存，但未能验证用户名（可能是 cookie 过期或身份不足）",
                    )
                }
            }
        }
    }

    /** 粘贴为空：询问是否清除已保存的登录态。 */
    private fun handleLogout(project: Project) {
        if (!SecureCookieStore.hasLogin()) {
            notify(project, NotificationType.ERROR, "没有已保存的登录态，且未粘贴任何 Cookie")
            return
        }
        val ok = Messages.showYesNoDialog(
            project,
            "清除已保存的登录态？",
            "登录洛谷（粘贴 Cookie）",
            Messages.getQuestionIcon(),
        ) == Messages.YES
        if (!ok) return
        SecureCookieStore.clear()
        LuoguHttpClient.clearCookies()
        notify(project, NotificationType.INFORMATION, "已退出登录，Cookie 已清除")
    }

    /** 把 `k=v; k2=v2` 形式的 cookie 串解析成 Map。 */
    private fun parseCookies(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        text.split(';').forEach { part ->
            val idx = part.indexOf('=')
            if (idx > 0) {
                val name = part.substring(0, idx).trim()
                val value = part.substring(idx + 1).trim()
                if (name.isNotEmpty()) result[name] = value
            }
        }
        return result
    }

    private fun notify(project: Project, type: NotificationType, content: String) {
        NOTIFICATION_GROUP.createNotification("登录洛谷", content, type).notify(project)
    }

    companion object {
        private val NOTIFICATION_GROUP: NotificationGroup =
            NotificationGroupManager.getInstance().getNotificationGroup("ClionLuogu.Notifications")

        private const val EXPLAIN_TEXT =
            "请在下方粘贴从浏览器复制的 Cookie（形如 __client_id=xxx; _uid=yyy; __suid=zzz）。\n" +
                "获取方法：浏览器登录洛谷后按 F12 → Application/Storage → Cookies 复制 __client_id 和 _uid 等键值。\n" +
                "留空并确定表示清除已保存的登录态。"
    }
}