package com.user.clionluogu.ui

import com.intellij.ide.ui.UISettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.user.clionluogu.settings.LuoguSettings

/**
 * 底部运行栏一出现，就把 IDE 的**宽屏工具窗口布局**打开一次 —— 这是他要的效果：
 * 底部那条只占中间编辑区的宽度，**不把左侧栏挤短**。
 *
 * 为什么必须由 IDE 来做而不是插件自己排：JetBrains 的默认布局是「上下条横跨整宽、
 * 侧边条被夹短」，插件改不了自己所属的那条带占多宽。平台自带反向开关
 * （`UISettings.wideScreenSupport`，界面里是
 * `Settings → Appearance & Behavior → Appearance → Widescreen tool window layout`，
 * 也可以 ⌘+点击分割条临时切），我们只是替他打开。
 *
 * 三条自律，别变成跟用户抢方向盘：
 * 1. **只在「现在是窄屏布局」且「我们从来没动过」时改**（见 [shouldApply]）；
 * 2. 改完就把 `wideScreenLayoutAdopted` 记下来 —— 他之后自己关掉，我们**不会再打开**；
 * 3. 通知里给一条**撤销**，一键回到他原来的设置，并说清这个开关在 IDE 哪儿。
 *
 * 只在 EDT 调用（写的是 IDE 全局偏好，还要 `fireUISettingsChanged()` 才立刻生效）。
 */
object WideLayout {

    /** 这次该不该替他打开这个全局开关。 */
    @JvmStatic
    fun shouldApply(currentlyWidescreen: Boolean, alreadyAdopted: Boolean): Boolean =
        !currentlyWidescreen && !alreadyAdopted

    /**
     * @return true 表示**这次真的改了**，调用方据此提示一次（不要每次都提示）。
     */
    @JvmStatic
    fun applyOnce(): Boolean {
        val settings = LuoguSettings.getInstance()
        val ui = runCatching { UISettings.getInstance() }.getOrNull() ?: return false
        if (!shouldApply(ui.wideScreenSupport, settings.wideScreenLayoutAdopted)) return false
        ui.wideScreenSupport = true
        ui.fireUISettingsChanged()
        settings.wideScreenLayoutAdopted = true
        return true
    }

    /** 撤销：关掉宽屏布局，同时记下「我们动过了」，下次启动不再自作主张。 */
    @JvmStatic
    fun revert() {
        LuoguSettings.getInstance().wideScreenLayoutAdopted = true
        val ui = runCatching { UISettings.getInstance() }.getOrNull() ?: return
        ui.wideScreenSupport = false
        ui.fireUISettingsChanged()
    }

    /** 改了全局设置必须说一声，并给一条一键回退。 */
    fun notifyApplied(project: Project) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("ClionLuogu.Notifications")
            .createNotification(
                "底部运行栏不再挤左侧栏了",
                "为了不让底部「自测 / 对拍 / 提交」把左侧的洛谷栏挤短，" +
                    "已为你打开 IDE 的宽屏工具窗口布局（侧边栏占满全高，上下条只占编辑区宽度）。\n" +
                    "想自己调：Settings → Appearance & Behavior → Appearance → Widescreen tool window layout，" +
                    "或者 ⌘+点击工具窗口的分割条临时切换。",
                NotificationType.INFORMATION,
            )
        notification.addAction(NotificationAction.createSimpleExpiring("撤销（关掉宽屏布局）") {
            revert()
            notification.expire()
        })
        notification.notify(project)
    }
}
