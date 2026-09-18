package com.user.clionluogu.action

import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.user.clionluogu.api.LuoguApiService
import com.user.clionluogu.api.LuoguHttpClient
import com.user.clionluogu.storage.SecureCookieStore
import kotlinx.coroutines.runBlocking
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * 登录洛谷：分别输入 __client_id 与 _uid 两个值（不再粘贴一长串 Cookie）。
 * 两个输入框留空并确定则清除已保存登录态。
 */
class LoginAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val dialog = LoginDialog()
        if (!dialog.showAndGet()) return

        val clientId = dialog.clientIdField.text.trim()
        val uidText = dialog.uidField.text.trim()

        // 两个都为空：视为清除登录态
        if (clientId.isEmpty() && uidText.isEmpty()) {
            handleLogout(project)
            return
        }

        // 校验充分性（不发网络）
        if (clientId.isEmpty()) {
            notify(project, NotificationType.ERROR, "缺少 __client_id")
            return
        }
        if (uidText.isEmpty()) {
            notify(project, NotificationType.ERROR, "缺少 _uid")
            return
        }

        // 组装并保存、注入
        val map = linkedMapOf(
            "__client_id" to clientId,
            "_uid" to uidText,
        )
        SecureCookieStore.save(map)
        LuoguHttpClient.injectCookies(map)

        // 后台校验登录态 → 回 EDT 通知
        ApplicationManager.getApplication().executeOnPooledThread {
            val uidNumber = uidText.toIntOrNull()
            val result = if (uidNumber == null) {
                "ERROR:uid 不是有效数字"
            } else {
                try {
                    runBlocking { LuoguApiService.getUser(uidNumber) }
                } catch (t: Throwable) {
                    "ERROR:${t.message ?: t.javaClass.simpleName}"
                }
            }
            ApplicationManager.getApplication().invokeLater {
                when {
                    result != null && result.startsWith("ERROR:") ->
                        notify(
                            project,
                            NotificationType.ERROR,
                            "登录校验失败：${result.removePrefix("ERROR:")}",
                        )
                    result.isNullOrBlank() ->
                        notify(
                            project,
                            NotificationType.WARNING,
                            "Cookie 已保存，但未能验证用户名（可能 cookie 过期或身份不足）",
                        )
                    else ->
                        notify(project, NotificationType.INFORMATION, "已登录：$result")
                }
            }
        }
    }

    /** 两个输入框均留空：询问是否清除已保存的登录态。 */
    private fun handleLogout(project: Project) {
        if (!SecureCookieStore.hasLogin()) {
            notify(project, NotificationType.ERROR, "没有已保存的登录态，且未输入任何 Cookie")
            return
        }
        val ok = Messages.showYesNoDialog(
            project,
            "清除已保存的登录态？",
            "登录洛谷",
            Messages.getQuestionIcon(),
        ) == Messages.YES
        if (!ok) return
        SecureCookieStore.clear()
        LuoguHttpClient.clearCookies()
        notify(project, NotificationType.INFORMATION, "已退出登录，Cookie 已清除")
    }

    private fun notify(project: Project, type: NotificationType, content: String) {
        NOTIFICATION_GROUP.createNotification("登录洛谷", content, type).notify(project)
    }

    companion object {
        private val NOTIFICATION_GROUP: NotificationGroup =
            NotificationGroupManager.getInstance().getNotificationGroup("ClionLuogu.Notifications")
    }
}

/** 两个输入框的登录对话框：__client_id 与 _uid。 */
private class LoginDialog : DialogWrapper(true) {

    val clientIdField = JTextField(40)
    val uidField = JTextField(40)

    init {
        title = "登录洛谷"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(8)
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(4)
        }

        panel.add(JBLabel("__client_id（浏览器 F12 → 应用程序 → Cookie）"), gbc)
        gbc.gridy++
        panel.add(clientIdField, gbc)

        gbc.gridy++
        panel.add(JBLabel("_uid"), gbc)
        gbc.gridy++
        panel.add(uidField, gbc)

        return panel
    }
}
