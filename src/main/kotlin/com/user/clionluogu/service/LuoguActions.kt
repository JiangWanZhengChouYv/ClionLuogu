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
import com.user.clionluogu.api.PunchResult
import com.user.clionluogu.api.PunchState
import com.user.clionluogu.api.SolutionPage
import com.user.clionluogu.api.UserProfile
import com.user.clionluogu.settings.LuoguSettings
import com.user.clionluogu.storage.SecureCookieStore
import kotlinx.coroutines.runBlocking
import java.io.File

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
     * 拉取某题的洛谷题解（一页）；成功回调 [SolutionPage]，失败回调可读错误。
     *
     * 这里**不做**「是否已登录」的预检查：钥匙串读到的快照与账号页看到的不一致过（误报「需要登录」），
     * 而洛谷在没有登录态时本就会回 401 —— 交给服务器判定，错误文案由 [LuoguApiService.getSolutions]
     * 带上状态码与凭据诊断，比预检查更可信。
     */
    fun loadSolutions(
        project: Project,
        pid: String,
        page: Int,
        onResult: (SolutionPage) -> Unit,
        onError: (String) -> Unit,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val solutions = try {
                runBlocking { LuoguApiService.getSolutions(pid, page) }
            } catch (t: Throwable) {
                invokeLater { onError(readableMessage(t)) }
                return@executeOnPooledThread
            }
            invokeLater { onResult(solutions) }
        }
    }

    /** 查询今日打卡状态；网络/解析失败也回调可读文案（启动提醒对失败是静默的）。 */
    fun checkPunch(onResult: (PunchState) -> Unit, onError: (String) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val state = try {
                runBlocking { LuoguApiService.getPunchState() }
            } catch (t: Throwable) {
                invokeLater { onError(readableMessage(t)) }
                return@executeOnPooledThread
            }
            invokeLater { onResult(state) }
        }
    }

    /**
     * 执行打卡。
     *
     * 只有**用户点了通知里的「打卡」按钮**（或按侧边栏标题栏的打卡图标）才会走到这里——
     * 本插件不做自动打卡，也不做定时轮询。
     */
    fun punch(onResult: (PunchResult) -> Unit, onError: (String) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = try {
                runBlocking { LuoguApiService.punch() }
            } catch (t: Throwable) {
                invokeLater { onError(readableMessage(t)) }
                return@executeOnPooledThread
            }
            invokeLater { onResult(result) }
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
            // 登录态检查放在后台线程：SecureCookieStore 会访问系统钥匙串，不能在 EDT 上同步调用
            if (!SecureCookieStore.hasLogin()) {
                invokeLater { onError("请先登录（到「登录」页填写 __client_id 与 _uid）") }
                return@executeOnPooledThread
            }
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

    /**
     * 对拍页的探测：样例目录、要编译的源文件、以及可用的编译器。
     * **全在后台线程**（`--version` 也是起子进程），回调切回 EDT。
     *
     * 这里刻意**不去找 `cmake-build` 里的产物**：产物名取决于 target 名，还得他先手动 Build，
     * 不如让 [CompilerService] 现场把 `Pxxx.cpp` 编出来——点一下就跑完。
     */
    fun probeCompareTarget(
        project: Project,
        pid: String,
        onResult: (CompareTarget) -> Unit,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val base = runCatching { project.basePath }.getOrNull()?.let { File(it) }
            val settings = LuoguSettings.getInstance()
            val detected = CompilerService.detect(settings.compareCompilerPath)
            if (base == null || !base.isDirectory) {
                invokeLater {
                    onResult(CompareTarget(null, null, null, detected.compiler, detected.overrideIgnored))
                }
                return@executeOnPooledThread
            }
            val found = SampleSetService.discover(base, pid)
            val source = runCatching { File(base, "$pid.cpp").takeIf { it.isFile } }.getOrNull()
            invokeLater {
                onResult(
                    CompareTarget(
                        projectBasePath = base.path,
                        samples = found,
                        sourcePath = source?.path,
                        compiler = detected.compiler,
                        compilerNotice = detected.overrideIgnored,
                    ),
                )
            }
        }
    }

    /** 把回调切回 EDT 执行。 */
    private fun invokeLater(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(action)
    }
}

/**
 * [LuoguActions.probeCompareTarget] 的结果。
 *
 * 三个可空字段各自对应一条独立的失败原因，UI 要能分别说清楚：
 * [projectBasePath] 为空 = 项目没落盘；[samples] 为空 = 同上；[compiler] 为空 = 这台机器没找到编译器。
 */
data class CompareTarget(
    val projectBasePath: String?,
    val samples: SampleSetService.Found?,
    /** 项目根下 `Pxxx.cpp` 的路径；还没拉题或改过名时为 null。 */
    val sourcePath: String?,
    val compiler: CompilerService.Compiler?,
    /** 设置里的编译器不可用、已回落时的那句提示。 */
    val compilerNotice: String?,
)
