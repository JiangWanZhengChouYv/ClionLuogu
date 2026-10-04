package com.user.clionluogu.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager

/**
 * 工具窗口的页签常量与切换入口。
 *
 * 1.8.0 起有**两个**窗口：左侧 `ClionLuogu` 是「题目侧」（评测 / 拉取 / 搜索 / 预览 / 题目 / 登录），
 * 底部 `ClionLuoguRun` 是「运行侧」（自测 / 对拍 / 提交）。[openTab] 默认找主窗口，
 * 搬到底部的那三个页签要显式传 [RUN_TOOL_WINDOW_ID]。
 */
object LuoguTabs {

    const val TOOL_WINDOW_ID = "ClionLuogu"

    /** 底部「运行侧」窗口：自测 / 对拍 / 提交。 */
    const val RUN_TOOL_WINDOW_ID = "ClionLuoguRun"

    const val TAB_EVAL = "评测"
    const val TAB_FETCH = "拉取"
    const val TAB_SEARCH = "搜索"
    const val TAB_PREVIEW = "预览"
    const val TAB_SUBMIT = "提交"
    const val TAB_COMPARE = "对拍"
    const val TAB_SELFTEST = "自测"
    const val TAB_PROBLEMS = "题目"
    const val TAB_LOGIN = "登录"
    const val TAB_ACCOUNT = "账号"

    /** 评测页的窗口实例；页签尚未创建（或工具窗口没打开）时为 null。 */
    fun evalWindow(project: Project): LuoguToolWindow? {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return null
        return toolWindow.contentManager.findContent(TAB_EVAL)
            ?.getUserData(LuoguToolWindow.WINDOW_KEY)
    }

    /** 打开工具窗口并选中指定页签。内容创建可能异步，故找不到时下一轮 EDT 再试一次。 */
    @JvmOverloads
    fun openTab(project: Project, tabName: String, windowId: String = TOOL_WINDOW_ID) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(windowId) ?: return
        toolWindow.activate(null)

        val contentManager = toolWindow.contentManager
        findContent(contentManager, tabName)?.let {
            contentManager.setSelectedContent(it)
            return
        }

        ApplicationManager.getApplication().invokeLater {
            findContent(contentManager, tabName)?.let { contentManager.setSelectedContent(it) }
        }
    }

    /**
     * 按名查找页签内容。
     *
     * 「登录」页签标题会随登录态在「登录」↔「账号」之间变化，故按名找不到时
     * 让二者互相回退：请求「登录」找不到就再找「账号」，反之亦然，
     * 保证菜单入口在标题变化后仍能定位该页签。
     */
    private fun findContent(contentManager: ContentManager, tabName: String): Content? {
        contentManager.findContent(tabName)?.let { return it }
        val alias = when (tabName) {
            TAB_LOGIN -> TAB_ACCOUNT
            TAB_ACCOUNT -> TAB_LOGIN
            else -> null
        }
        return alias?.let { contentManager.findContent(it) }
    }
}
