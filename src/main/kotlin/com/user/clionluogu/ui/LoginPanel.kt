package com.user.clionluogu.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.user.clionluogu.api.UserProfile
import com.user.clionluogu.service.LuoguActions
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Image
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * 登录页：CardLayout 双状态面板。
 *
 * - 登录卡片（未登录）：保留 `__client_id` / `_uid` 两个输入框与「登录」按钮；
 * - 账号卡片（已登录）：64px 头像 + 账号数据行 + 「刷新」+「退出登录」+ 状态/错误提示。
 *
 * 展示规则：用户名与 UID 为基本字段；其余字段（咕值/elo、排名、通过题目数、提交题目数、
 * 关注数、粉丝数、CCF 等级、个性签名）**仅在接口下发且非空时展示对应行，为空则整行隐藏**。
 * 头像获取或解码失败时静默跳过（隐藏头像，不报错）。
 *
 * 所有 Swing 更新均在 EDT；数据拉取由 [LuoguActions] 负责放到后台线程并切回 EDT。
 */
class LoginPanel(private val project: Project) : JPanel() {

    /**
     * 登录态变化回调：true=已登录，false=已退出；供工具窗口工厂同步页签标题。
     *
     * 赋值时会补发一次**当前已确定的登录态**（[lastLoginState]），用于消除初始化竞态：
     * 构造时立即发起的异步登录态读取可能早于本回调被赋值，若不补发会导致
     * 「内容已切到账号视图、页签标题仍是登录」。
     */
    var onLoginStateChanged: ((Boolean) -> Unit)? = null
        set(value) {
            field = value
            lastLoginState?.let { state -> value?.invoke(state) }
        }

    /** 最近一次已确定的登录态；null 表示尚未确定（初始化读取未返回）。 */
    private var lastLoginState: Boolean? = null

    /**
     * 请求代次：每次切换账号 / 刷新 / 退出登录时自增。
     * 异步回调携带发起时的代次，代次不一致说明回调已过期，直接丢弃，
     * 避免旧账号数据 / 旧头像写回界面，或连续刷新时旧响应覆盖新响应。
     */
    private var generation = 0

    private val cardLayout = CardLayout()

    // —— 登录卡片组件 ——
    private val clientIdField = JBTextField()
    private val uidField = JBTextField()
    private val loginButton = JButton("登录")
    private val loginStatusLabel = JBLabel(" ")

    // —— 账号卡片组件 ——
    private val avatarLabel = JBLabel()
    private val rowsPanel = JPanel(GridBagLayout())
    private val refreshButton = JButton("刷新")
    private val logoutButton = JButton("退出登录")
    private val accountStatusLabel = JBLabel(" ")

    /** 当前账号 uid；null 表示未登录（决定「刷新」是否有目标）。 */
    private var currentUid: Int? = null

    init {
        layout = cardLayout

        add(buildLoginCard(), CARD_LOGIN)
        add(buildAccountCard(), CARD_ACCOUNT)
        cardLayout.show(this, CARD_LOGIN)

        loginButton.addActionListener { doLogin() }
        refreshButton.addActionListener { refresh() }
        logoutButton.addActionListener { doLogout() }

        // 初始化：读取已保存凭据（后台读钥匙串，回调已切回 EDT）决定初始卡片。
        // 捕获发起时的代次：若用户在此期间已手动登录（成功路径 showAccount 会自增代次）
        // 或已退出登录，迟到的旧结果代次不一致即丢弃，避免覆盖刚建立的登录态。
        val initGen = generation
        LuoguActions.loadLoginState { uid ->
            if (initGen != generation) return@loadLoginState
            if (uid != null) showAccount(uid) else showLogin()
        }
    }

    /** 重新拉取当前账号资料（未登录时无操作）。 */
    fun refresh() {
        val uid = currentUid ?: return
        // 开启新一代请求：作废在途的旧资料 / 旧头像回调，避免旧响应覆盖新响应
        generation++
        val gen = generation
        accountStatusLabel.text = "刷新中…"
        loadProfile(uid, gen)
    }

    // —— 卡片构建 ——

    private fun buildLoginCard(): JPanel {
        val card = JPanel(BorderLayout())

        // columns 只影响首选宽度：表单是 fill=HORIZONTAL，窄侧边栏里照样铺满整行。
        // 原来写 40，等于把「首选宽度」撑到比侧边栏还宽一倍，白白把别的面板挤窄。
        clientIdField.columns = 20
        uidField.columns = 20
        clientIdField.emptyText.appendText("从浏览器 Cookie 里复制")
        uidField.emptyText.appendText("从浏览器 Cookie 里复制")

        val form = JPanel(GridBagLayout())
        form.border = JBUI.Borders.empty(8)
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(4)
        }
        // 长说明挪进 tooltip：那一长串（含箭头）在窄侧边栏里会被裁掉，而且和字段抢行宽
        form.add(cookieHintLabel("__client_id").apply { toolTipText = COOKIE_WHERE }, gbc)
        gbc.gridy++
        form.add(clientIdField, gbc)
        gbc.gridy++
        form.add(cookieHintLabel("_uid").apply { toolTipText = COOKIE_WHERE }, gbc)
        gbc.gridy++
        form.add(uidField, gbc)

        val buttonRow = JPanel(WrapLayout(FlowLayout.LEFT, 6, 2))
        buttonRow.border = JBUI.Borders.empty(0, 8, 8, 8)
        buttonRow.add(loginButton)

        val top = JPanel(BorderLayout())
        top.add(form, BorderLayout.CENTER)
        top.add(buttonRow, BorderLayout.SOUTH)

        card.add(top, BorderLayout.NORTH)
        card.add(loginStatusLabel, BorderLayout.CENTER)
        return card
    }

    private fun cookieHintLabel(name: String): JBLabel = JBLabel("$name　").apply {
        // 等宽：Cookie 名就是标识符，混在中文里反而认不出来
        font = DetailPanel.monoFont()
    }

    private fun buildAccountCard(): JPanel {
        val card = JPanel(BorderLayout())
        card.border = JBUI.Borders.empty(8)

        avatarLabel.preferredSize = Dimension(AVATAR_SIZE, AVATAR_SIZE)
        avatarLabel.horizontalAlignment = SwingConstants.CENTER
        avatarLabel.isVisible = false

        val header = JPanel(BorderLayout())
        header.add(avatarLabel, BorderLayout.WEST)
        header.add(rowsPanel, BorderLayout.CENTER)

        val buttonRow = JPanel(WrapLayout(FlowLayout.LEFT, 6, 2))
        buttonRow.add(refreshButton)
        buttonRow.add(logoutButton)

        val top = JPanel(BorderLayout())
        top.add(header, BorderLayout.NORTH)
        top.add(buttonRow, BorderLayout.SOUTH)

        card.add(top, BorderLayout.NORTH)
        card.add(accountStatusLabel, BorderLayout.CENTER)
        return card
    }

    // —— 登录 / 退出 ——

    private fun doLogin() {
        val clientId = clientIdField.text.trim()
        val uid = uidField.text.trim()
        // 进入函数时即捕获 uid 数值：回调中不再重读输入框，避免用户中途改动导致账号错配
        val uidNumber = uid.toIntOrNull()
        loginStatusLabel.text = "登录中…"
        LuoguActions.login(
            project = project,
            clientId = clientId,
            uid = uid,
            onResult = { _ ->
                // 登录成功（含 cookie 已存但未验证到用户名）即进入账号视图并拉资料
                if (uidNumber != null) {
                    showAccount(uidNumber)
                } else {
                    status(loginStatusLabel, "登录校验失败：uid 不是有效数字", error = true)
                }
            },
            onError = { msg -> status(loginStatusLabel, msg, error = true) },
        )
    }

    private fun doLogout() {
        // 先开启新一代：作废所有在途的资料 / 头像回调，防止旧数据在退出后被写回
        generation++
        val gen = generation
        accountStatusLabel.text = "退出中…"
        // 清除凭据会访问系统钥匙串，放到后台线程执行，完成后切回 EDT 更新界面
        ApplicationManager.getApplication().executeOnPooledThread {
            // 访问钥匙串可能抛异常：这里捕获后仍要执行收尾（切回登录卡片、清数据、还原标题），
            // 否则界面会永远停在「退出中…」且无法自愈（代次已自增，在途回调已作废）。
            val errorText = try {
                LuoguActions.logout()
                null
            } catch (t: Throwable) {
                val reason = t.message ?: t.javaClass.simpleName
                "退出登录时出错：$reason，请重试"
            }
            ApplicationManager.getApplication().invokeLater {
                if (gen != generation) return@invokeLater // 已被后续操作取代，丢弃本次收尾
                clearAccount()
                cardLayout.show(this, CARD_LOGIN)
                loginStatusLabel.text = errorText ?: "已退出登录，Cookie 已清除"
                if (errorText != null) loginStatusLabel.foreground = UIUtil.getErrorForeground()
                notifyLoginState(false)
            }
        }
    }

    // —— 状态切换 ——

    /** 切到账号卡片并加载该 uid 的资料。 */
    private fun showAccount(uid: Int) {
        currentUid = uid
        // 开启新一代请求：上一次账号（或上一次显示）的在途回调全部作废
        generation++
        val gen = generation
        cardLayout.show(this, CARD_ACCOUNT)
        accountStatusLabel.text = "加载账号数据…"
        notifyLoginState(true)
        loadProfile(uid, gen)
    }

    /** 切回登录卡片。 */
    private fun showLogin() {
        currentUid = null
        cardLayout.show(this, CARD_LOGIN)
        notifyLoginState(false)
    }

    /**
     * 广播登录态：先缓存（供回调赋值时补发），再通知监听者。
     * 缓存保证无论「异步结果先到」还是「回调先被赋值」，页签标题最终都会与内容一致。
     */
    private fun notifyLoginState(loggedIn: Boolean) {
        lastLoginState = loggedIn
        onLoginStateChanged?.invoke(loggedIn)
    }

    /**
     * 拉取资料：成功后渲染数据行并加载头像；失败仅提示，仍保持已登录视图与退出入口。
     * [gen] 为发起时的请求代次，回调在 EDT 执行时若代次已变化则丢弃（不写回旧数据）。
     */
    private fun loadProfile(uid: Int, gen: Int) {
        LuoguActions.loadProfile(
            uid = uid,
            onResult = { profile ->
                if (gen == generation) {
                    renderProfile(profile)
                    loadAvatar(profile.avatarUrl, gen)
                    accountStatusLabel.text = " "
                }
            },
            onError = { msg ->
                if (gen == generation) status(accountStatusLabel, msg, error = true)
            },
        )
    }

    // —— 账号数据渲染 ——

    /** 清空并重建账号数据行：字段为空则整行不显示。 */
    private fun renderProfile(profile: UserProfile) {
        rowsPanel.removeAll()
        var row = 0

        fun add(label: String, value: String?) {
            if (value.isNullOrBlank()) return // 为空：整行不显示
            rowsPanel.add(
                // 标签用弱化色，值用正常色：靠 GridBag 的列间距对齐，不再拿全角冒号假对齐
                JBLabel(label).apply { foreground = UIUtil.getLabelDisabledForeground() },
                GridBagConstraints().apply {
                    gridx = 0
                    gridy = row
                    anchor = GridBagConstraints.WEST
                    insets = JBUI.insets(2, 4)
                },
            )
            rowsPanel.add(
                JBLabel(value),
                GridBagConstraints().apply {
                    gridx = 1
                    gridy = row
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.WEST
                    insets = JBUI.insets(2, 4)
                },
            )
            row++
        }

        // 用户名与 UID 为基础字段
        add("用户名", profile.name)
        add("UID", profile.uid.toString())
        // 其余字段：仅非空时展示（字段清单与接口实测返回一致，不臆造）
        add("咕值 / elo", profile.eloValue?.toString())
        add("排名", profile.ranking?.toString())
        add("通过题目数", profile.passedProblemCount?.toString())
        add("提交题目数", profile.submittedProblemCount?.toString())
        add("关注数", profile.followingCount?.toString())
        add("粉丝数", profile.followerCount?.toString())
        add("CCF 等级", profile.ccfLevel?.toString())
        add("个性签名", profile.slogan)

        rowsPanel.revalidate()
        rowsPanel.repaint()
    }

    /**
     * 后台下载头像；失败或解码失败时隐藏头像，不报错、不打断其余数据展示。
     * [gen] 为发起时的请求代次：回调在 EDT 执行时若代次已变化（已退出 / 已重登 / 已刷新），
     * 则丢弃该结果，避免旧头像覆盖新头像或退出后又被写回。
     */
    private fun loadAvatar(url: String?, gen: Int) {
        avatarLabel.icon = null
        avatarLabel.isVisible = false
        if (url.isNullOrBlank()) return
        LuoguActions.loadAvatar(url) { bytes ->
            if (gen == generation) {
                val icon = bytes?.let { decodeAvatarIcon(it) }
                avatarLabel.icon = icon
                avatarLabel.isVisible = icon != null
            }
        }
    }

    /** 图片字节 → 64×64 缩放图标；解码异常返回 null（静默跳过）。 */
    private fun decodeAvatarIcon(bytes: ByteArray): ImageIcon? = try {
        val image = ImageIO.read(ByteArrayInputStream(bytes))
        if (image == null) {
            null
        } else {
            ImageIcon(image.getScaledInstance(AVATAR_SIZE, AVATAR_SIZE, Image.SCALE_SMOOTH))
        }
    } catch (_: Throwable) {
        null
    }

    /** 清空账号卡片全部数据与头像（退出登录 / 切换账号时调用）。 */
    private fun clearAccount() {
        currentUid = null
        avatarLabel.icon = null
        avatarLabel.isVisible = false
        rowsPanel.removeAll()
        rowsPanel.revalidate()
        rowsPanel.repaint()
        accountStatusLabel.text = " "
    }

    /** 状态标签原来成功与失败长一个样；出错至少染成错误色。 */
    private fun status(label: JBLabel, text: String, error: Boolean = false) {
        label.text = text
        label.foreground = if (error) UIUtil.getErrorForeground() else UIUtil.getLabelForeground()
    }

    private companion object {
        /** 两个 Cookie 怎么找：以前摊在标签里（窄侧边栏会被裁），现在放 tooltip。 */
        const val COOKIE_WHERE = "浏览器 F12 → Application（应用程序）→ Cookies → luogu.com.cn，复制对应值"
        const val CARD_LOGIN = "cardLogin"
        const val CARD_ACCOUNT = "cardAccount"
        const val AVATAR_SIZE = 64
    }
}
