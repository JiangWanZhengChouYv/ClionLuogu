package com.user.clionluogu.action

import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.user.clionluogu.api.LuoguApiException
import com.user.clionluogu.api.LuoguApiService
import com.user.clionluogu.api.LuoguPidValidator
import com.user.clionluogu.storage.SecureCookieStore
import kotlinx.coroutines.runBlocking

/** 提交当前编辑器里的 C++ 代码到洛谷。 */
class SubmitCodeAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // 1. 未登录检查
        if (!SecureCookieStore.hasLogin()) {
            notify(project, NotificationType.ERROR, "请先登录洛谷（粘贴 Cookie）")
            return
        }

        // 2. 读取当前激活编辑器文件
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: run {
            notify(project, NotificationType.ERROR, "请先在编辑器中打开 C++ 文件")
            return
        }
        val document: Document = editor.document
        val file: VirtualFile? = FileDocumentManager.getInstance().getFile(document)
        val name = file?.name
        if (name == null || !name.endsWith(".cpp")) {
            notify(project, NotificationType.ERROR, "请在 C++ 编辑器上执行（当前文件需为 .cpp）")
            return
        }
        val code = document.text

        // 3. 确认 pid（默认从文件名猜，如 P1001.cpp → P1001）
        val guessedPid = name.removeSuffix(".cpp")
        val input = Messages.showInputDialog(
            "输入题号（默认从文件名推断）:",
            "提交代码到洛谷",
            Messages.getQuestionIcon(),
            guessedPid,
            null,
        ) ?: return
        val pid = input.trim()
        if (!LuoguPidValidator.isValidPid(pid)) {
            notify(project, NotificationType.ERROR, "题号格式无效，示例：P1001")
            return
        }

        // 4. 选择语言版本
        val langs = LuoguApiService.LANGUAGE_IDS
        val defaultKey = "C++17 (O2)"
        val keys = langs.keys.toTypedArray()
        if (defaultKey !in langs) { // 表被改掉时回退到首个可用项
            val fallback = keys.firstOrNull() ?: run {
                notify(project, NotificationType.ERROR, "语言表为空，无法提交")
                return
            }
            return pickAndSubmit(project, pid, code, langs, fallback, keys)
        }
        pickAndSubmit(project, pid, code, langs, defaultKey, keys)
    }

    private fun pickAndSubmit(
        project: Project,
        pid: String,
        code: String,
        langs: Map<String, Int>,
        initial: String,
        keys: Array<String>,
    ) {
        val chosen = Messages.showEditableChooseDialog(
            "选择 C++ 语言版本（可输入自定义名称）:",
            "提交代码到洛谷",
            Messages.getQuestionIcon(),
            keys,
            initial,
            null,
        ) ?: return
        val languageId = langs[chosen.trim()] ?: run {
            notify(project, NotificationType.ERROR, "未知语言版本：$chosen")
            return
        }

        // 5. 后台提交（网络绝不在 EDT），通知回 EDT
        ApplicationManager.getApplication().executeOnPooledThread {
            val rid = try {
                runBlocking { LuoguApiService.submitCode(pid, languageId, code) }
            } catch (t: Throwable) {
                notify(project, NotificationType.ERROR, readableMessage(t))
                return@executeOnPooledThread
            }
            notify(project, NotificationType.INFORMATION, "已提交到 $pid，记录 id=$rid")
            // 联动工具窗口：记录该评测并在后台轮询，实时更新结果
            ApplicationManager.getApplication().invokeLater {
                com.user.clionluogu.ui.LuoguToolWindow.INSTANCE?.trackSubmission(pid, rid)
            }
        }
    }

    /** 将底层异常映射为可读提示。 */
    private fun readableMessage(t: Throwable): String {
        val msg = t.message ?: t.javaClass.simpleName
        return if (t is LuoguApiException) msg else "网络请求异常：$msg"
    }

    private fun notify(project: Project, type: NotificationType, content: String) {
        NOTIFICATION_GROUP.createNotification("提交代码到洛谷", content, type).notify(project)
    }

    companion object {
        private val NOTIFICATION_GROUP: NotificationGroup =
            NotificationGroupManager.getInstance().getNotificationGroup("ClionLuogu.Notifications")
    }
}