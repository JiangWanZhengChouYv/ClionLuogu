package com.user.clionluogu.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.user.clionluogu.service.LuoguActions
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JButton
import javax.swing.JPanel

/** 登录页：输入 __client_id 与 _uid，保存并校验登录态；可退出登录。 */
class LoginPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val clientIdField = JBTextField()
    private val uidField = JBTextField()
    private val loginButton = JButton("登录")
    private val logoutButton = JButton("退出登录")
    private val statusLabel = JBLabel(" ")

    init {
        clientIdField.columns = 40
        uidField.columns = 40

        val form = JPanel(GridBagLayout())
        form.border = JBUI.Borders.empty(8)
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(4)
        }
        form.add(JBLabel("__client_id（浏览器 F12 → 应用程序 → Cookie）"), gbc)
        gbc.gridy++
        form.add(clientIdField, gbc)
        gbc.gridy++
        form.add(JBLabel("_uid"), gbc)
        gbc.gridy++
        form.add(uidField, gbc)

        val buttonRow = JPanel()
        buttonRow.add(loginButton)
        buttonRow.add(logoutButton)

        val top = JPanel(BorderLayout())
        top.add(form, BorderLayout.CENTER)
        top.add(buttonRow, BorderLayout.SOUTH)

        add(top, BorderLayout.NORTH)
        add(statusLabel, BorderLayout.CENTER)

        loginButton.addActionListener { doLogin() }
        logoutButton.addActionListener { doLogout() }
    }

    private fun doLogin() {
        val clientId = clientIdField.text.trim()
        val uid = uidField.text.trim()
        statusLabel.text = "登录中…"
        LuoguActions.login(
            project = project,
            clientId = clientId,
            uid = uid,
            onResult = { name ->
                statusLabel.text =
                    if (name.isNullOrBlank()) "Cookie 已保存，但未能验证用户名" else "已登录：$name"
            },
            onError = { msg -> statusLabel.text = msg },
        )
    }

    private fun doLogout() {
        LuoguActions.logout()
        statusLabel.text = "已退出登录，Cookie 已清除"
    }
}
