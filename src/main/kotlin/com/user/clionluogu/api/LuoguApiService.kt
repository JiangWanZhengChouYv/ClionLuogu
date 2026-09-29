package com.user.clionluogu.api

import com.user.clionluogu.storage.SecureCookieStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.URLEncoder

/** 洛谷 API 业务异常，供 UI 层提示。 */
class LuoguApiException(msg: String, cause: Throwable? = null) : Exception(msg, cause)

/**
 * 一次评测记录的当前状态摘要（宽松解析，字段可有缺省）。
 * [subtaskInfo] 为各子任务得分文本，[compileError] 为编译错误详情。
 */
data class SubmissionStatus(
    val rid: String,
    val statusCode: Int? = null,
    val statusText: String? = null,   // 如 AC/WA/TLE/MLE/编译错误/进行中/等待
    val timeMs: Long? = null,
    val memoryKb: Long? = null,
    val subtaskInfo: List<String> = emptyList(), // 各子任务/测试点得分文本
    val subtaskResults: List<SubtaskResult> = emptyList(), // 逐测试点明细（有 detail 时）
    val compileError: String? = null,            // 编译错误详情
)

/** 单个测试点（测试用例）的评测状态。[id] 为洛谷原始编号（展示时 +1，即 #1 起）。 */
data class TestCaseResult(
    val id: Int,
    val status: Int? = null,
    val timeMs: Long? = null,
    val memoryKb: Long? = null,
    val score: Int? = null,
)

/** 一个子任务及其下各测试点的评测状态。 */
data class SubtaskResult(
    val id: Int,
    val status: Int? = null,
    val score: Int? = null,
    val testCases: List<TestCaseResult> = emptyList(),
)

/** 题目搜索结果的单条摘要。 */
data class ProblemSummary(
    val pid: String,
    val name: String,
    val difficulty: Int = 0,
)

/**
 * 洛谷评测状态码 → 可读文本。
 *
 * 映射依据 vscode-luogu 的 `RecordStatus` 表（洛谷 `/record/{rid}` 返回的 `record.status`）：
 * 0 等待、1 评测中、2 CE、3 OLE、4 MLE、5 TLE、6 WA、7 RE、11 UKE、12 AC、14 Unaccepted、
 * 21/22/23 分别对应 Hack 成功/失败/跳过，-1 为未显示。不在表内时回退为「原始数字」。
 */
fun statusTextOf(code: Int?): String = when (code) {
    -1 -> "未显示 (Unshown)"
    0 -> "等待中 (Waiting)"
    1 -> "评测中 (Judging)"
    2 -> "编译错误 (CE)"
    3 -> "输出超限 (OLE)"
    4 -> "内存超限 (MLE)"
    5 -> "时间超限 (TLE)"
    6 -> "答案错误 (WA)"
    7 -> "运行错误 (RE)"
    11 -> "未知错误 (UKE)"
    12 -> "通过 (AC)"
    14 -> "未通过 (Unaccepted)"
    21 -> "Hack 成功"
    22 -> "Hack 失败"
    23 -> "Hack 跳过"
    null -> "未知"
    else -> "状态码 $code（未知）"
}

object LuoguApiService {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        encodeDefaults = true
    }

    private val CONTEXT_REGEX =
        Regex("""(?s)<script id="lentille-context"[^>]*type="application/json"[^>]*>(.*?)</script>""")

    /** 首页 `<meta name="csrf-token">` 提取。 */
    private val CSRF_REGEX = Regex("""(?s)<meta name="csrf-token" content="(.*?)">""")

    /** 拉取题目公开数据。 */
    suspend fun getProblem(pid: String): LuoguProblemDto = withContext(Dispatchers.IO) {
        val url = "https://www.luogu.com.cn/problem/$pid"
        val request = Request.Builder()
            .url(url)
            .apply { LuoguHttpClient.HEADERS.forEach { (k, v) -> header(k, v) } }
            .get()
            .build()

        val envelope = try {
            LuoguHttpClient.getClient().newCall(request).execute().use { response ->
                if (response.code != 200) {
                    throw LuoguApiException("请求失败：HTTP ${response.code}")
                }
                val body = response.body?.string()
                    ?: throw LuoguApiException("响应为空")
                val match = CONTEXT_REGEX.find(body)
                    ?: throw LuoguApiException("数据块缺失（可能被拦截），请稍后重试")
                val block = match.groupValues[1].trim()
                try {
                    json.decodeFromString<LuoguEnvelopeDto>(block)
                } catch (e: Exception) {
                    throw LuoguApiException("数据解析失败：${e.message}", e)
                }
            }
        } catch (e: LuoguApiException) {
            throw e
        } catch (e: Exception) {
            throw LuoguApiException("网络请求异常：${e.message}", e)
        }

        when {
            envelope.status != 200 ->
                throw LuoguApiException("题目不存在（status=${envelope.status}）")
            envelope.data?.problem == null ->
                throw LuoguApiException("题目不存在")
            else -> envelope.data.problem
        }
    }

    /**
     * 按关键词搜索题目，返回摘要列表（可能为空）。
     *
     * 命中洛谷 `/problem/list` 接口：该接口即使带 `_contentOnly=1` 仍返回 HTML，
     * 数据同样藏在 `lentille-context` 脚本块内，故复用与拉题一致的解析方式；
     * 反爬的 302/C3VK 由 [LuoguHttpClient] 的 CookieJar 自动跟随处理。
     */
    suspend fun searchProblems(keyword: String, page: Int = 1): List<ProblemSummary> =
        withContext(Dispatchers.IO) {
            val kw = keyword.trim()
            if (kw.isEmpty()) return@withContext emptyList()

            val encoded = URLEncoder.encode(kw, "UTF-8")
            val url = "https://www.luogu.com.cn/problem/list?keyword=$encoded&page=$page&_contentOnly=1"
            val request = Request.Builder()
                .url(url)
                .apply { LuoguHttpClient.HEADERS.forEach { (k, v) -> header(k, v) } }
                .get()
                .build()

            val body = try {
                LuoguHttpClient.getClient().newCall(request).execute().use { response ->
                    if (response.code != 200) {
                        throw LuoguApiException("搜索失败：HTTP ${response.code}")
                    }
                    response.body?.string() ?: throw LuoguApiException("搜索响应为空")
                }
            } catch (e: LuoguApiException) {
                throw e
            } catch (e: Exception) {
                throw LuoguApiException("网络请求异常：${e.message}", e)
            }

            val block = CONTEXT_REGEX.find(body)?.groupValues?.get(1)?.trim()
                ?: throw LuoguApiException("搜索结果解析失败（可能被拦截），请稍后重试")

            val root = try {
                json.parseToJsonElement(block) as? JsonObject
                    ?: throw LuoguApiException("搜索结果非 JSON 对象")
            } catch (e: LuoguApiException) {
                throw e
            } catch (e: Exception) {
                throw LuoguApiException("搜索结果解析失败：${e.message}", e)
            }

            val data = root["data"] as? JsonObject ?: return@withContext emptyList()
            val problems = data["problems"] as? JsonObject ?: return@withContext emptyList()
            val result = problems["result"] as? JsonArray ?: return@withContext emptyList()

            result.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val pid = (obj["pid"] as? JsonPrimitive)?.content?.trim().orEmpty()
                if (pid.isEmpty()) return@mapNotNull null
                val name = (obj["name"] as? JsonPrimitive)?.content?.trim().orEmpty()
                ProblemSummary(pid = pid, name = name, difficulty = obj.asInt("difficulty") ?: 0)
            }
        }

    /**
     * 从洛谷 `/ranking` 页面提取 CSRF token（`<meta name="csrf-token">`）。
     *
     * 说明：首页 `/` 会被反爬拦截（返回混淆 JS，无该 meta），故改为访问 `/ranking`
     * （vscode-luogu 用的正是此路径；实测带登录 cookie 可稳定返回 200 + csrf-token）。
     * 找不到该标签时抛 [LuoguApiException]。
     */
    private fun fetchCsrfToken(): String {
        val request = Request.Builder()
            .url("https://www.luogu.com.cn/ranking")
            .apply { LuoguHttpClient.HEADERS.forEach { (k, v) -> header(k, v) } }
            // 与提交使用同一份登录 cookie，确保拿到的 csrf-token 与提交会话匹配
            .apply { loginCookieHeader()?.let { header("Cookie", it) } }
            .get()
            .build()
        val body = LuoguHttpClient.getClient().newCall(request).execute().use {
            val b = it.body?.string() ?: throw LuoguApiException("CSRF token 获取失败")
            b
        }
        val token = CSRF_REGEX.find(body)?.groupValues?.get(1)?.trim()
        if (token.isNullOrBlank()) throw LuoguApiException("CSRF token 获取失败")
        return token
    }

    /**
     * 校验登录态并返回用户名；无法确认登录 / 请求失败时返回 null。
     *
     * 参照 vscode-luogu 的实现：先用 `/auth/login` 拿到会话（OkHttp 自动跟随重定向并携带 / 保存
     * Cookie，含反爬 C3VK），再请求 `/api/user/search?keyword=<uid>`，从返回 JSON 的
     * `users[0].name` 取用户名。该响应会携带 `_uid` HttpOnly cookie，是登录态有效的标志。
     */
    suspend fun getUser(uid: Int): String? = withContext(Dispatchers.IO) {
        try {
            // 热身：访问登录页，让 CookieJar 保存反爬 C3VK 等，确保后续请求携带会话
            LuoguHttpClient.getClient().newCall(
                Request.Builder()
                    .url("https://www.luogu.com.cn/auth/login")
                    .apply { LuoguHttpClient.HEADERS.forEach { (k, v) -> header(k, v) } }
                    .get()
                    .build()
            ).execute().use { }

            // 查询用户，从响应解析用户名
            val builder = Request.Builder()
                .url("https://www.luogu.com.cn/api/user/search?keyword=$uid")
                .apply { LuoguHttpClient.HEADERS.forEach { (k, v) -> header(k, v) } }
                .get()
            val response = LuoguHttpClient.getClient().newCall(builder.build()).execute()
            response.use {
                if (it.code != 200) return@withContext null
                val body = it.body?.string() ?: return@withContext null
                parseSearchUser(body)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 解析 `/api/user/search` 响应，返回首个用户 name；解析不出则 null。 */
    private fun parseSearchUser(body: String): String? = try {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return null
        val users = root["users"] as? JsonArray ?: return null
        val first = users.firstOrNull() as? JsonObject ?: return null
        (first["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifBlank { null }
    } catch (_: Exception) {
        null
    }

    /**
     * C++ 版本 → 洛谷语言 id 映射表。
     *
     * 洛谷的语言 id 取自 vscode-luogu 维护的真实映射；`CppLang(id, o2)` 表示语言本体的 id 与
     * 是否开 O2（O2 不是独立的 lang id，而是提交时的 enableO2 布尔）。如平台变动可在设置校准。
     */
    data class CppLang(val id: Int, val o2: Boolean)

    val LANGUAGES: Map<String, CppLang> = linkedMapOf(
        "C++98" to CppLang(3, false),
        "C++98 (O2)" to CppLang(3, true),
        "C++11" to CppLang(4, false),
        "C++11 (O2)" to CppLang(4, true),
        "C++14" to CppLang(11, false),
        "C++14 (O2)" to CppLang(11, true),
        "C++17" to CppLang(12, false),
        "C++17 (O2)" to CppLang(12, true),
        "C++20" to CppLang(27, false),
        "C++20 (O2)" to CppLang(27, true),
        "C++23" to CppLang(34, false),
        "C++23 (O2)" to CppLang(34, true),
    )

    /** 洛谷提交端点（按题目 pid，POST JSON）。 */
    private fun submitEndpoint(pid: String): String =
        "https://www.luogu.com.cn/fe/api/problem/submit/$pid"

    /** 匹配回跳地址里的评测记录 id，如 /record/123456。 */
    private val RECORD_ID_REGEX = Regex("""/?record/(\d+)""")

    /**
     * 提交代码到洛谷。
     *
     * 遇服务器要求图形验证码（errorMessage=验证码错误）时，若提供了 [captchaPrompter]，
     * 会自动下载验证码图片交给回调（可空返回表示用户取消），再用验证码重新提交，
     * 最多重试 [maxCaptchaRetries] 次。成功返回评测记录 id。
     *
     * @param captchaPrompter 接收验证码 PNG 字节，返回用户输入的验证码；返回 null 表示取消。
     */
    suspend fun submitCode(
        pid: String,
        lang: CppLang,
        code: String,
        captchaPrompter: (suspend (ByteArray) -> String?)? = null,
        maxCaptchaRetries: Int = 3,
    ): String = withContext(Dispatchers.IO) {
        var captcha: String? = null
        repeat(maxCaptchaRetries + 1) {
            val csrf = try {
                fetchCsrfToken()
            } catch (e: LuoguApiException) {
                throw LuoguApiException("未登录或 CSRF 获取失败：${e.message}", e)
            }

            // 与 vscode-luogu 一致的 JSON 请求体：{ code, lang, enableO2, captcha? }
            val payload = buildJsonObject {
                put("code", JsonPrimitive(code))
                put("lang", JsonPrimitive(lang.id))
                put("enableO2", JsonPrimitive(lang.o2))
                if (!captcha.isNullOrBlank()) put("captcha", JsonPrimitive(captcha))
            }

            val request = Request.Builder()
                .url(submitEndpoint(pid))
                .apply { LuoguHttpClient.HEADERS.forEach { (k, v) -> header(k, v) } }
                .header("X-CSRF-Token", csrf)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Origin", "https://www.luogu.com.cn")
                .header("Referer", "https://www.luogu.com.cn/")
                .header("Content-Type", "application/json")
                // 手动带上登录 cookie（与 vscode-luogu 一致：_uid=<uid>; __client_id=<clientID>），
                // 避免依赖 CookieJar 匹配失败导致被当作未登录。
                .apply { loginCookieHeader()?.let { header("Cookie", it) } }
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = try {
                LuoguHttpClient.getClient().newCall(request).execute()
            } catch (e: Exception) {
                throw LuoguApiException("提交请求异常：${e.message}", e)
            }
            response.use {
                val body = it.body?.string() ?: ""
                if (it.isSuccessful) {
                    val rid = extractRid(it, body)
                        ?: throw LuoguApiException("提交成功但未能解析评测记录 id")
                    return@withContext rid
                }
                // 服务器要求图形验证码：下载图片交给回调，获取到输入后重新提交
                val errMsgForCaptcha = extractErrorMessage(body) // 已对 unicode 转义解码
                if (it.code == 403 && errMsgForCaptcha.contains("验证码错误")) {
                    val prompter = captchaPrompter ?: throw LuoguApiException("提交失败：需要输入图形验证码")
                    val image = try {
                        fetchCaptchaImage()
                    } catch (ie: Exception) {
                        throw LuoguApiException("验证码图片获取失败：${ie.message}", ie)
                    }
                    val input = prompter(image)
                    if (input.isNullOrBlank()) throw LuoguApiException("已取消提交（验证码未输入）")
                    captcha = input.trim()
                    return@repeat // 继续下一次循环，用新验证码重试
                }
                // 403 等：提取响应体错误信息 + set-cookie 里服务端回写的 _uid/__client_id 便于诊断
                val errMsg = extractErrorMessage(body)
                val cookieHint = responseCookiesHint(it)
                throw LuoguApiException(
                    "提交失败：HTTP ${it.code}$errMsg$cookieHint（未登录或需刷新 Cookie）"
                )
            }
        }
        throw LuoguApiException("提交失败：多次验证码重试仍未成功")
    }

    /**
     * 下载洛谷的图形验证码 PNG 图片（与 vscode-luogu 一致：GET /api/verify/captcha）。
     * 需携带登录 cookie 以拿到与当前会话匹配的验证码。
     */
    private fun fetchCaptchaImage(): ByteArray {
        val request = Request.Builder()
            .url("https://www.luogu.com.cn/api/verify/captcha")
            .apply { LuoguHttpClient.HEADERS.forEach { (k, v) -> header(k, v) } }
            .apply { loginCookieHeader()?.let { header("Cookie", it) } }
            .get()
            .build()
        val bytes = LuoguHttpClient.getClient().newCall(request).execute().use {
            if (!it.isSuccessful) throw LuoguApiException("HTTP ${it.code}")
            it.body?.bytes() ?: throw LuoguApiException("空响应")
        }
        if (bytes.isEmpty()) throw LuoguApiException("空响应")
        return bytes
    }

    /** 从提交失败响应体里提取 errorMessage，用于诊断。 */
    private fun extractErrorMessage(body: String): String {
        if (body.isBlank()) return ""
        val root = (try { json.parseToJsonElement(body) } catch (_: Exception) { return "" }) as? JsonObject ?: return ""
        val msg = root["errorMessage"]?.takeIf { it is JsonPrimitive }?.let { (it as JsonPrimitive).content }
        return if (msg.isNullOrBlank()) "" else "，服务器返回：$msg"
    }

    /** 从失败响应头提取服务端回写的 _uid / __client_id（登录态被重置的风向标）。 */
    private fun responseCookiesHint(response: Response): String {
        val hints = mutableListOf<String>()
        response.headers.values("Set-Cookie").forEach { raw ->
            if (raw.startsWith("_uid=")) hints.add("uid=${raw.removePrefix("_uid=").takeWhile { it.isDigit() }}")
            else if (raw.startsWith("__client_id=")) hints.add("cookie 已刷新")
        }
        return if (hints.isEmpty()) "" else "，服务端回写${hints.joinToString("/")}"
    }

    /**
     * 从安全存储读取登录 cookie，拼成 `_uid=<uid>; __client_id=<clientID>` 请求头。
     * 取不到（未登录 / uid 非数字）时返回 null。
     */
    private fun loginCookieHeader(): String? {
        val cookies = SecureCookieStore.load()
        val clientId = cookies["__client_id"].orEmpty()
        val uid = cookies["_uid"].orEmpty()
        if (clientId.isBlank() || uid.isBlank()) return null
        if (uid.toIntOrNull() == null) return null
        return "_uid=$uid;__client_id=$clientId"
    }

    /** 从响应头 Location / JSON 的 rid / 回跳地址中提取评测记录 id。 */
    private fun extractRid(response: Response, body: String): String? {
        // 1) 响应头 Location（重定向到 /record/{rid}）
        response.headers["Location"]?.let { loc ->
            RECORD_ID_REGEX.find(loc)?.let { return it.groupValues[1] }
        }
        // 2) JSON 任意层级 rid 字段
        val root = try { json.parseToJsonElement(body) } catch (_: Exception) { return null }
        val stack = ArrayDeque<JsonElement>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            when (val el = stack.removeLast()) {
                is JsonObject -> {
                    el["rid"]?.let {
                        if (it is JsonPrimitive) {
                            val c = it.content.trim()
                            if (c.isNotEmpty() && c.all(Char::isDigit)) return c
                        }
                    }
                    stack.addAll(el.values)
                }
                is JsonArray -> stack.addAll(el)
                else -> {}
            }
        }
        // 3) 正文回跳地址（HTML 情形兜底）
        RECORD_ID_REGEX.find(body)?.let { return it.groupValues[1] }
        return null
    }

    /**
     * 查询评测记录状态。
     *
     * GET `/record/{rid}` 后从邮件 `lentille-context` 的 `data.record` 宽松解析出
     * 状态码 / 分数 / 时间 / 内存 / 编译错误 / 子任务得分等字段；解析失败抛可读
     * [LuoguApiException]（未登录或数据被拦截时数据块缺失）。
     */
    suspend fun getSubmissionStatus(rid: String): SubmissionStatus = withContext(Dispatchers.IO) {
        val url = "https://www.luogu.com.cn/record/$rid?_contentOnly=1"
        val request = Request.Builder()
            .url(url)
            .apply { LuoguHttpClient.HEADERS.forEach { (k, v) -> header(k, v) } }
            .apply { loginCookieHeader()?.let { header("Cookie", it) } }
            .get()
            .build()

        val raw = try {
            LuoguHttpClient.getClient().newCall(request).execute().use { response ->
                if (response.code !in 200..299) {
                    throw LuoguApiException(
                        "评测记录获取失败：HTTP ${response.code}（未登录或需刷新 Cookie）"
                    )
                }
                response.body?.string() ?: throw LuoguApiException("评测记录响应为空")
            }
        } catch (e: LuoguApiException) {
            throw e
        } catch (e: Exception) {
            throw LuoguApiException("评测记录网络异常：${e.message}", e)
        }

        val block = CONTEXT_REGEX.find(raw)?.groupValues?.get(1)?.trim()
            ?: throw LuoguApiException("评测数据块缺失（可能被拦截或未登录），请稍后重试")

        val record: JsonObject = try {
            val root = json.parseToJsonElement(block) as? JsonObject
                ?: throw LuoguApiException("评测数据块非 JSON 对象")
            root["data"] as? JsonObject ?: throw LuoguApiException("评测数据缺少 data 字段")
        } catch (e: LuoguApiException) {
            throw e
        } catch (e: Exception) {
            throw LuoguApiException("评测数据解析失败：${e.message}", e)
        }

        val recordBlock = record["record"] as? JsonObject
        val status = recordBlock?.asInt("status")
        val subtasks = buildList {
            val list = recordBlock?.get("subtasks") as? JsonArray ?: return@buildList
            list.forEachIndexed { idx, el ->
                val sobj = el as? JsonObject ?: return@forEachIndexed
                val name = (sobj["subtaskName"] as? JsonPrimitive)?.content
                    ?.trim()?.ifBlank { "子任务${idx + 1}" } ?: "子任务${idx + 1}"
                val score = sobj.asInt("score")
                add(if (score != null) "$name: ${score}分" else name)
            }
        }

        SubmissionStatus(
            rid = rid,
            statusCode = status,
            statusText = statusTextOf(status),
            timeMs = recordBlock?.asLong("time"),
            memoryKb = recordBlock?.asLong("memory"),
            subtaskInfo = subtasks,
            subtaskResults = parseSubtaskResults(recordBlock),
            compileError = recordBlock?.get("compileErrorMessage")
                ?.let { it as? JsonPrimitive }?.content?.trim()?.ifBlank { null },
        )
    }

    /**
     * 解析 `record.detail.judgeResult.subtasks` 下的逐测试点状态。
     *
     * 实测结构（洛谷 `/record/{rid}?_contentOnly=1`）中 `subtasks` 与 `testCases` 均为**数组**：
     * ```
     * detail.judgeResult.subtasks = [
     *   { id, score, status, time, memory,
     *     testCases: [ { id, status, time, memory, score, description } ] }
     * ]
     * ```
     * 亦兼容按 id 索引的对象形态。`detail` 仅在记录对当前身份可见时下发（如本人提交）；
     * 缺失时返回空列表，由调用方回退到 [SubmissionStatus.subtaskInfo] 的汇总文本。
     * 子任务与测试点均按 id 升序排列；测试点展示编号为 id + 1（洛谷原始 id 从 0 起）。
     */
    private fun parseSubtaskResults(record: JsonObject?): List<SubtaskResult> {
        if (record == null) return emptyList()

        // 逐测试点数据可能出现的位置，逐一尝试、命中即用：
        //   record.detail.judgeResult.subtasks（实测形态）
        //   record.judgeResult.subtasks
        //   record.subtasks
        val subtasksEl = listOfNotNull(
            ((record["detail"] as? JsonObject)?.get("judgeResult") as? JsonObject)?.get("subtasks"),
            (record["judgeResult"] as? JsonObject)?.get("subtasks"),
            record["subtasks"],
        ).firstOrNull() ?: return emptyList()

        return toNamedObjects(subtasksEl)
            .mapNotNull { (fallbackId, obj) ->
                val sid = obj.asInt("id") ?: fallbackId ?: return@mapNotNull null
                val cases = toNamedObjects(obj["testCases"])
                    .mapNotNull { (fallbackCid, cobj) ->
                        val cid = cobj.asInt("id") ?: fallbackCid ?: return@mapNotNull null
                        TestCaseResult(
                            id = cid,
                            status = cobj.asInt("status"),
                            timeMs = cobj.asLong("time"),
                            memoryKb = cobj.asLong("memory"),
                            score = cobj.asInt("score"),
                        )
                    }
                    .sortedBy { it.id }
                SubtaskResult(
                    id = sid,
                    status = obj.asInt("status"),
                    score = obj.asInt("score"),
                    testCases = cases,
                )
            }
            .sortedBy { it.id }
    }

    /**
     * 把「数组形态」或「按 id 索引的对象形态」统一成 (兜底id, 对象) 列表。
     * 数组形态下 id 取自元素内部字段，故兜底 id 为 null。
     */
    private fun toNamedObjects(element: JsonElement?): List<Pair<Int?, JsonObject>> = when (element) {
        is JsonArray -> element.mapNotNull { it as? JsonObject }.map { null to it }
        is JsonObject -> element.entries.mapNotNull { (key, value) ->
            (value as? JsonObject)?.let { key.toIntOrNull() to it }
        }
        else -> emptyList()
    }

    /** 从 JSON 对象宽松读取 int 字段。 */
    private fun JsonObject.asInt(key: String): Int? = (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

    /** 从 JSON 对象宽松读取 long 字段。 */
    private fun JsonObject.asLong(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

}