package com.user.clionluogu.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.user.clionluogu.api.LuoguApiException
import com.user.clionluogu.api.LuoguApiService
import com.user.clionluogu.api.LuoguHttpClient
import com.user.clionluogu.api.LuoguProblemDto
import com.user.clionluogu.api.ProblemSummary
import com.user.clionluogu.api.UserProfile
import com.user.clionluogu.storage.SecureCookieStore
import kotlinx.coroutines.runBlocking

/**
 * 洛谷核心业务的无弹窗封装，供各 Action 与工具窗口页面复用。
 *
 * 网络 / 耗时操作一律在后台线程执行，最终回调统一切回 EDT；
 * 所有弹窗与通知由调用方负责。
 */
object LuoguActions {

    /** 将底层异常映射为可读提示。 */
    fun readableMessage(t: Throwable): String {
        val msg = t.message ?: t.javaClass.simpleName
        return if (t is LuoguApiException) msg else "网络请求异常：$msg"
    }

    /** 拉取题目并生成本地文件；成功回调可读结果文案（多行），失败回调可读错误。 */
    fun fetchAndGenerate(
        project: Project,
        pid: String,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val problem = try {
                runBlocking { LuoguApiService.getProblem(pid) }
            } catch (t: Throwable) {
                invokeLater { onError(readableMessage(t)) }
                return@executeOnPooledThread
            }
            invokeLater { generateFiles(project, pid, problem, onResult) }
        }
    }

    /** 在 EDT 生成题目描述 md 与 .cpp/样例文件，并自动打开 cpp。 */
    private fun generateFiles(
        project: Project,
        pid: String,
        problem: LuoguProblemDto,
        onResult: (String) -> Unit,
    ) {
        // 生成题目描述 md（写入根目录 Pxxxx.md；失败不中断后续逻辑）
        var mdFile: VirtualFile? = null
        var mdSignal = ""
        try {
            val mdText = ProblemMdGenService.buildMarkdown(problem)
            mdFile = ProblemMdGenService.write(project, pid, mdText)
            if (mdFile != null) mdSignal = "已在根目录生成 $pid.md"
        } catch (_: Throwable) {
            mdFile = null
        }

        // 生成 .cpp + 全部样例文件夹
        val result = ProblemFileGenService.generate(project, pid, problem)

        // 组装完成文案
        val lines = mutableListOf<String>()
        if (mdFile != null) lines.add(mdSignal)
        if (result.sampleCount > 0 && !result.samplesDirPath.isNullOrBlank()) {
            lines.add("样例 ${result.sampleCount} 组，位于 ${result.samplesDirPath}")
        } else if (result.sampleCount == 0) {
            lines.add("该题无样例")
        }
        if (result.notices.isNotEmpty()) {
            lines.addAll(result.notices)
        }

        onResult(lines.joinToString("\n"))

        // 自动打开 cpp
        result.cpp?.let { FileEditorManager.getInstance(project).openFile(it, true) }
    }

    /** 只拉取题面数据（不生成任何文件）；成功回调题目数据，失败回调可读错误。 */
    fun loadProblem(
        project: Project,
        pid: String,
        onResult: (LuoguProblemDto) -> Unit,
        onError: (String) -> Unit,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val problem = try {
                runBlocking { LuoguApiService.getProblem(pid) }
            } catch (t: Throwable) {
                invokeLater { onError(readableMessage(t)) }
                return@executeOnPooledThread
            }
            invokeLater { onResult(problem) }
        }
    }

    /** 按关键词搜索题目；成功回调结果列表，失败回调可读错误。 */
    fun search(
        project: Project,
        keyword: String,
        onResult: (List<ProblemSummary>) -> Unit,
        onError: (String) -> Unit,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val results = try {
                runBlocking { LuoguApiService.searchProblems(keyword) }
            } catch (t: Throwable) {
                invokeLater { onError(readableMessage(t)) }
                return@executeOnPooledThread
            }
            invokeLater { onResult(results) }
        }
    }

    /**
     * 提交代码；成功回调评测记录 id，失败回调可读错误。
     *
     * [captchaPrompter] 用于验证码输入（内部在 EDT 阻塞等待），返回 null 表示取消。
     */
    fun submit(
        project: Project,
        pid: String,
        lang: LuoguApiService.CppLang,
        code: String,
        captchaPrompter: (ByteArray) -> String?,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val rid = try {
                runBlocking {
                    LuoguApiService.submitCode(
                        pid = pid,
                        lang = lang,
                        code = code,
                        captchaPrompter = { captchaPrompter(it) },
                    )
                }
            } catch (t: Throwable) {
                invokeLater { onError(readableMessage(t)) }
                return@executeOnPooledThread
            }
            invokeLater { onResult(rid) }
        }
    }

    /**
     * 保存 cookie + 注入 + 校验登录态。
     *
     * clientId/uid 缺失或 uid 非数字时通过 [onError] 报错；
     * 校验成功通过 [onResult] 回调用户名，cookie 已保存但未能验证用户名时回调 null。
     */
    fun login(
        project: Project,
        clientId: String,
        uid: String,
        onResult: (String?) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (clientId.isEmpty()) {
            onError("缺少 __client_id")
            return
        }
        if (uid.isEmpty()) {
            onError("缺少 _uid")
            return
        }
        val uidNumber = uid.toIntOrNull()
        if (uidNumber == null) {
            onError("登录校验失败：uid 不是有效数字")
            return
        }

        val map = linkedMapOf(
            "__client_id" to clientId,
            "_uid" to uid,
        )

        ApplicationManager.getApplication().executeOnPooledThread {
            SecureCookieStore.save(map)
            LuoguHttpClient.injectCookies(map)
            val name = try {
                runBlocking { LuoguApiService.getUser(uidNumber) }
            } catch (_: Throwable) {
                null
            }
            invokeLater { onResult(name) }
        }
    }

    /** 清除登录态。 */
    fun logout() {
        SecureCookieStore.clear()
        LuoguHttpClient.clearCookies()
    }

    /**
     * 读取已保存的登录态，回调当前登录 uid（未登录 / 凭据不完整时为 null）。
     *
     * 说明：访问钥匙串 [SecureCookieStore.load] 是同步阻塞操作，故放到后台线程执行，
     * 避免在 EDT 上卡顿；这里不用 [SecureCookieStore.hasLogin]（它内部同样同步读钥匙串）。
     * 判定口径与 hasLogin 一致：`__client_id` 与 `_uid` 均有非空值才算已登录。回调切回 EDT。
     */
    fun loadLoginState(onResult: (Int?) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val uid = try {
                val cookies = SecureCookieStore.load()
                val clientId = cookies["__client_id"].orEmpty()
                val uidRaw = cookies["_uid"].orEmpty()
                if (clientId.isNotBlank() && uidRaw.isNotBlank()) uidRaw.trim().toIntOrNull() else null
            } catch (_: Throwable) {
                null
            }
            invokeLater { onResult(uid) }
        }
    }

    /** 拉取用户资料；成功回调 [UserProfile]，失败回调可读错误。 */
    fun loadProfile(
        uid: Int,
        onResult: (UserProfile) -> Unit,
        onError: (String) -> Unit,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val profile = try {
                runBlocking { LuoguApiService.getUserProfile(uid) }
            } catch (t: Throwable) {
                invokeLater { onError(readableMessage(t)) }
                return@executeOnPooledThread
            }
            invokeLater { onResult(profile) }
        }
    }

    /** 后台下载头像图片字节；回调参数为 null 表示失败（静默，由 UI 跳过展示）。 */
    fun loadAvatar(url: String, onResult: (ByteArray?) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val bytes = try {
                runBlocking { LuoguApiService.fetchAvatar(url) }
            } catch (_: Throwable) {
                null
            }
            invokeLater { onResult(bytes) }
        }
    }

    /** 把回调切回 EDT 执行。 */
    private fun invokeLater(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(action)
    }
}
