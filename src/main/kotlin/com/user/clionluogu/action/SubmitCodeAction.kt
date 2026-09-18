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
import java.awt.Image
import javax.imageio.ImageIO
import javax.swing.Icon
import javax.swing.ImageIcon

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
        val langs = LuoguApiService.LANGUAGES
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
        langs: Map<String, LuoguApiService.CppLang>,
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
        val lang = langs[chosen.trim()] ?: run {
            notify(project, NotificationType.ERROR, "未知语言版本：$chosen")
            return
        }

        // 5. 后台提交（网络绝不在 EDT），通知回 EDT
        ApplicationManager.getApplication().executeOnPooledThread {
            val rid = try {
                runBlocking { LuoguApiService.submitCode(pid, lang, code, captchaPrompter = { promptCaptcha(project, it) }) }
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

    /**
     * 在 EDT 上展示验证码图片并让用户输入；线程内阻塞等待用户输入。
     * 返回 null 表示用户取消。Swing 操作必须在 EDT 进行，故用 invokeAndWait 切回主线程。
     */
    private fun promptCaptcha(project: Project, png: ByteArray): String? {
        // 非 EDT 则切到 EDT 并以阻塞方式等待结果
        val app = ApplicationManager.getApplication()
        var result: String? = null
        if (!app.isDispatchThread) {
            app.invokeAndWait { result = showCaptchaDialog(project, png) }
        } else {
            result = showCaptchaDialog(project, png)
        }
        return result
    }

    private fun showCaptchaDialog(project: Project, png: ByteArray): String? {
        val image: Image = ImageIO.read(png.inputStream()) ?: return null
        val icon: Icon = ImageIcon(image)
        return Messages.showInputDialog(
            "请识别并输入下方验证码（提交需要）:",
            "洛谷验证码",
            icon,
            "",
            null,
        )?.takeIf { it.isNotBlank() }
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