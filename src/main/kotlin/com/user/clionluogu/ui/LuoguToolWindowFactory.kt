package com.user.clionluogu.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.user.clionluogu.action.ClearHistoryAction
import com.user.clionluogu.service.LuoguActions
import javax.swing.JComponent

/**
 * 侧边工具窗口工厂：装配「评测 / 拉取 / 搜索 / 预览 / 提交 / 登录」多页签与工具栏。
 */
class LuoguToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val window = LuoguToolWindow(project)
        val fetchPanel = FetchPanel(project)
        val previewPanel = PreviewPanel(project)
        val searchPanel = SearchPanel(project) { pid ->
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
        }
        val submitPanel = SubmitPanel(project) { pid, rid, lang, code ->
            window.trackSubmission(pid, rid, lang, code)
        }
        val loginPanel = LoginPanel(project)

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
        addContent(loginPanel, LuoguTabs.TAB_LOGIN)

        toolWindow.setTitleActions(listOf(ClearHistoryAction()))
    }
}
