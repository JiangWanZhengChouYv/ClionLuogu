package com.user.clionluogu.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.user.clionluogu.action.ClearHistoryAction
import com.user.clionluogu.action.PunchNowAction
import com.user.clionluogu.service.LuoguActions
import javax.swing.JComponent

/**
 * 侧边工具窗口工厂：装配「评测 / 拉取 / 搜索 / 预览 / 提交 / 对拍 / 题目 / 登录」多页签与工具栏。
 */
class LuoguToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val window = LuoguToolWindow(project)
        val previewPanel = PreviewPanel(project)

        // 各页签的「查看题解」入口：让预览页切到题解模式，并把该页签带到前台
        val showSolutions: (String) -> Unit = { pid ->
            previewPanel.showSolutionsFor(pid)
            LuoguTabs.openTab(project, LuoguTabs.TAB_PREVIEW)
        }
        val fetchPanel = FetchPanel(project, onSolutions = showSolutions)
        val searchPanel = SearchPanel(
            project = project,
            onPreview = { pid ->
                LuoguActions.loadProblem(
                    project = project,
                    pid = pid,
                    onResult = { problem ->
                        previewPanel.showProblem(problem)
                        LuoguTabs.openTab(project, LuoguTabs.TAB_PREVIEW)
                    },
                    onError = { msg ->
                        previewPanel.showMessage(msg)
                        LuoguTabs.openTab(project, LuoguTabs.TAB_PREVIEW)
                    },
                )
            },
            onSolutions = showSolutions,
        )
        val submitPanel = SubmitPanel(project) { pid, rid, lang, code ->
            window.trackSubmission(pid, rid, lang, code)
        }
        val loginPanel = LoginPanel(project)
        val comparePanel = SampleComparePanel(project)
        val problemIndexPanel = ProblemIndexPanel(project)

        val contentFactory = ContentFactory.getInstance()
        val contentManager = toolWindow.contentManager
        val evalContent = contentFactory.createContent(window.content, LuoguTabs.TAB_EVAL, false)
        evalContent.setPreferredFocusableComponent(window.content)
        evalContent.putUserData(LuoguToolWindow.WINDOW_KEY, window)
        contentManager.addContent(evalContent)

        fun addContent(component: JComponent, name: String) {
            val content = contentFactory.createContent(component, name, false)
            content.setPreferredFocusableComponent(component)
            contentManager.addContent(content)
        }
        addContent(fetchPanel, LuoguTabs.TAB_FETCH)
        addContent(searchPanel, LuoguTabs.TAB_SEARCH)
        val previewContent = contentFactory.createContent(previewPanel, LuoguTabs.TAB_PREVIEW, false)
        previewContent.setPreferredFocusableComponent(previewPanel)
        previewContent.setDisposer(previewPanel)
        contentManager.addContent(previewContent)
        addContent(submitPanel, LuoguTabs.TAB_SUBMIT)
        addContent(comparePanel, LuoguTabs.TAB_COMPARE)
        addContent(problemIndexPanel, LuoguTabs.TAB_PROBLEMS)

        // 登录页内容显式创建，便于随登录态改写页签标题（登录 ↔ 账号）
        val loginContent = contentFactory.createContent(loginPanel, LuoguTabs.TAB_LOGIN, false)
        loginContent.setPreferredFocusableComponent(loginPanel)
        contentManager.addContent(loginContent)
        loginPanel.onLoginStateChanged = { loggedIn ->
            loginContent.displayName =
                if (loggedIn) LuoguTabs.TAB_ACCOUNT else LuoguTabs.TAB_LOGIN
        }

        // 标题栏工具按钮：清空记录 + 手动打卡（打卡错过启动通知时在这里补）
        toolWindow.setTitleActions(listOf(ClearHistoryAction(), PunchNowAction()))
    }
}
