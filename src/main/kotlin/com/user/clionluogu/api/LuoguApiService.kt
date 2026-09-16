package com.user.clionluogu.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response

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
    val compileError: String? = null,            // 编译错误详情
)

/** 洛谷评测状态码 → 可读文本；不在表内时回退为「原始数字 + 中文未知」。 */
fun statusTextOf(code: Int?): String = when (code) {
    0 -> "等待中 (PENDING)"
    1 -> "评测中 (JUDGING)"
    2 -> "编译错误 (CE)"
    3 -> "正确 (AC)"
    4 -> "答案错误 (WA)"
    5 -> "时间超限 (TLE)"
    6 -> "内存超限 (MLE)"
    7 -> "运行错误 (RE)"
    8 -> "系统错误 (SE)"
    9 -> "HACKED"
    10 -> "未知错误 (UKE)"
    11 -> "输出超限 (OLE)"
    12 -> "格式错误 (PE)"
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

    /** 从洛谷首页提取 CSRF token。首页无该标签（如未登录被重定向到登录页）时抛 [LuoguApiException]。 */
    private fun fetchCsrfToken(): String {
        val request = Request.Builder()
            .url("https://www.luogu.com.cn/")
            .apply { LuoguHttpClient.HEADERS.forEach { (k, v) -> header(k, v) } }
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
     * 校验当前登录态并返回登录用户名；未登录 / 请求失败 / 解析不出时返回 null。
     * 请求会尽力附带 X-CSRF-Token（失败不影响校验）。
     */
    suspend fun getCurrentUser(): String? = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder()
                .url("https://www.luogu.com.cn/api/user/info")
                .apply { LuoguHttpClient.HEADERS.forEach { (k, v) -> header(k, v) } }
                .get()
            runCatching { builder.header("X-CSRF-Token", fetchCsrfToken()) }
            val response = LuoguHttpClient.getClient().newCall(builder.build()).execute()
            response.use {
                if (it.code != 200) return@withContext null
                val body = it.body?.string() ?: return@withContext null
                parseUserName(body)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 宽松提取 JSON 里任意层级的第一个 name 字符串字段（返回 null 表示未解析到）。 */
    private fun parseUserName(body: String): String? {
        val root = try {
            json.parseToJsonElement(body)
        } catch (_: Exception) {
            return null
        }
        val stack = ArrayDeque<JsonElement>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            when (val el = stack.removeLast()) {
                is JsonObject -> {
                    el["name"]?.let {
                        if (it is JsonPrimitive && it.isString) return it.content.trim().ifBlank { null }
                    }
                    stack.addAll(el.values)
                }
                is JsonArray -> stack.addAll(el)
                else -> {}
            }
        }
        return null
    }

    /**
     * C++ 版本 → 洛谷语言 id 映射表。
     *
     * 洛谷的语言 id 来自社区逆向，可能随平台变动，仅作默认值；如提交失败或判定异常，
     * 请在设置中逐项校准（也可按需在表内增删）。此处优先提供含/不含 O2 的变体。
     */
    val LANGUAGE_IDS: Map<String, Int> = linkedMapOf(
        "C++98" to 2,
        "C++98 (O2)" to 72,
        "C++11" to 28,
        "C++11 (O2)" to 34,
        "C++14" to 34,
        "C++14 (O2)" to 72,
        "C++17" to 76,
        "C++17 (O2)" to 78,
        "C++20" to 83,
        "C++20 (O2)" to 82,
    )

    /** 洛谷提交端点（表单提交）。 */
    private const val SUBMIT_ENDPOINT = "https://www.luogu.com.cn/submit"

    /** 匹配回跳地址里的评测记录 id，如 /record/123456。 */
    private val RECORD_ID_REGEX = Regex("""/?record/(\d+)""")

    /**
     * 提交代码到洛谷。
     *
     * 无登录 cookie（或 CSRF 获取失败）时抛出可读 [LuoguApiException]；成功返回评测记录 id。
     * 请求体为 application/x-www-form-urlencoded（pid/lid/code/csrf_token），
     * 并附带 X-CSRF-Token、Referer、Origin、X-Requested-With 等浏览器伪装头。
     */
    suspend fun submitCode(pid: String, languageId: Int, code: String): String = withContext(Dispatchers.IO) {
        val csrf = try {
            fetchCsrfToken()
        } catch (e: LuoguApiException) {
            throw LuoguApiException("未登录或 CSRF 获取失败：${e.message}", e)
        }

        val form = FormBody.Builder()
            .add("pid", pid)
            .add("lid", languageId.toString())
            .add("code", code)
            .add("csrf_token", csrf)
            .build()

        val request = Request.Builder()
            .url(SUBMIT_ENDPOINT)
            .apply { LuoguHttpClient.HEADERS.forEach { (k, v) -> header(k, v) } }
            .header("X-CSRF-Token", csrf)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Origin", "https://www.luogu.com.cn")
            .header("Referer", "https://www.luogu.com.cn/problem/$pid")
            .post(form)
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
            // 401/403/500 等：未登录或校验被拦截
            throw LuoguApiException("提交失败：HTTP ${it.code}（未登录或需刷新 Cookie）")
        }
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
        val url = "https://www.luogu.com.cn/record/$rid"
        val request = Request.Builder()
            .url(url)
            .apply { LuoguHttpClient.HEADERS.forEach { (k, v) -> header(k, v) } }
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
            compileError = recordBlock?.get("compileErrorMessage")
                ?.let { it as? JsonPrimitive }?.content?.trim()?.ifBlank { null },
        )
    }

    /** 从 JSON 对象宽松读取 int 字段。 */
    private fun JsonObject.asInt(key: String): Int? = (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

    /** 从 JSON 对象宽松读取 long 字段。 */
    private fun JsonObject.asLong(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

}