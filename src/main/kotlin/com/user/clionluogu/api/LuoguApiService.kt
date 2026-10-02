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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
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
 * 单篇题解（列表项即含正文，无需再请求详情）。
 *
 * 字段来源（2026-10-01 实测，见 [getSolutions] 的说明）：题解在洛谷内部就是一篇 article，
 * 标识是**字符串 [lid]**（如 `dddotkf8`）而非数字 id；[contentMarkdown] 是 **Markdown 源码**
 * （如 `> Read on **Luogu** | [UOJ](...)`），不是 HTML；票数字段名为 [upvote]。
 */
data class SolutionSummary(
    val lid: String,
    val title: String,
    val author: String? = null,
    val upvote: Int? = null,
    val contentMarkdown: String? = null,
)

/** 一页题解及分页信息。 */
data class SolutionPage(
    val pid: String,
    val page: Int,
    val total: Int,
    val hasMore: Boolean,
    val solutions: List<SolutionSummary>,
)

/**
 * 首页「打卡」状态。
 *
 * 打卡入口只存在于**旧版首页的服务端渲染**里（SPA 的 36 条路由与 145 个 chunk 都搜不到打卡/punch）。
 * [punchable] 的判据是打卡按钮 `[name=punch]`：实测未打卡时 `btn=1`，打完卡 `btn=0`（整块被服务端换掉）。
 */
data class PunchState(val punchable: Boolean)

/** 一次打卡的结果。 */
data class PunchResult(
    val code: Int,
    val message: String?,
)

/**
 * 洛谷用户资料（宽松解析：除 [uid] 外字段均可缺省，缺省时保持 null 由 UI 决定是否展示）。
 *
 * 字段名来源：实测 `GET https://www.luogu.com.cn/user/{uid}?_contentOnly=1`
 * （2026-09-30 以浏览器 UA 抓取 uid=2270787）响应中 `lentille-context` 脚本块
 * `data.user` 的对象键，实测为：
 * uid / avatar / name / slogan / badge / isAdmin / isBanned / color / ccfLevel / xcpcLevel /
 * background / eloValue / followingCount / followerCount / ranking / passedProblemCount /
 * submittedProblemCount / elo / registerTime / introduction。
 * 其中 [avatarUrl] 对应 JSON 字段 `avatar`（URL 字符串）。
 *
 * [verified]：「认证状态」字段，**实测响应中未出现**（未能核实），也无可靠旁证；
 * 为兼容起见保留为可空字段，但 **界面不再渲染该字段**（改为展示实测存在的
 * [passedProblemCount] / [submittedProblemCount]）。
 */
data class UserProfile(
    val uid: Int,                        // data.user.uid（非空）
    val name: String? = null,            // data.user.name
    val avatarUrl: String? = null,       // data.user.avatar（头像图片 URL）
    val slogan: String? = null,          // data.user.slogan（个性签名）
    val color: String? = null,           // data.user.color（如 Gray/Blue/Green/Orange/Red）
    val ccfLevel: Int? = null,           // data.user.ccfLevel（CCF 等级）
    val ranking: Int? = null,            // data.user.ranking（咕值排名）
    val followingCount: Int? = null,     // data.user.followingCount（关注数）
    val followerCount: Int? = null,      // data.user.followerCount（粉丝数）
    val passedProblemCount: Int? = null,     // data.user.passedProblemCount（通过题目数，实测存在）
    val submittedProblemCount: Int? = null,  // data.user.submittedProblemCount（提交题目数，实测存在）
    val eloValue: Int? = null,           // data.user.eloValue（elo，实测可为 null）
    val verified: Boolean? = null,       // 认证状态：实测接口不下发，保留字段但界面不再渲染
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

    /**
     * 打卡按钮是否存在的判据。
     *
     * 必须限定在**标签属性**上：首页始终下发那段 jQuery 处理函数，源码里就写着
     * `$("[name=punch]")`，若只匹配 `name=punch` 则未登录页面也会被判成有按钮。
     */
    private val PUNCH_BUTTON_REGEX = Regex("""(?is)<[a-z]+[^>]*name\s*=\s*["']?punch["']?""")

    /** 打卡响应是喂给 `eval` 的 JS 字面量，键可能带也可能不带引号，故只宽松取 code。 */
    private val PUNCH_CODE_REGEX = Regex("""["']?code["']?\s*:\s*(\d+)""")

    /** `message` 的键名引号同样可有可无（实测 `{code:200,message:"…"}` 这种裸键形态）。 */
    private val PUNCH_MESSAGE_REGEX =
        Regex("""["']?message["']?\s*:\s*(["'])((?:(?!\1)[^\\]|\\.)*?)\1""")

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
     * 拉取某题的洛谷题解（一页）。
     *
     * 需要登录：未登录时 `/problem/solution/{pid}` 在四种请求形态（`_contentOnly=1`、纯 HTML、
     * `X-Requested-With`、`Accept: application/json`）下**一律返回 401**，故与 [getUserProfile]/[submitCode]
     * 共用同一份凭据（见 [loginSnapshot]）。
     *
     * 结构实测（2026-10-01，未用到登录态）：题解页前端读的是 `solutions.count` 与 `solutions.result`
     * （见其 chunk `columba~183a77506b1246fd.js` 里的 `solutions.count` / `solutions.result` /
     * `article.upvote` / `article.lid`），条目即 article——用公开的 `GET /article?page=1&_contentOnly=1`
     * 可比对到同形状：`{count, perPage, result:[{lid, title, author{name,uid}, upvote, time, content}]}`，
     * 其中 `content` 为 **Markdown 源码**。分页只有 `page` 与 `orderBy`（`weight`/`time`）两个查询参数，
     * 没有 pageCount，故 [SolutionPage.hasMore] 由 `page * perPage < count` 推出。
     *
     * 解析骨架沿用 [searchProblems]（同一份 `lentille-context` 数据块）；`result` 兼容数组与按 id
     * 索引的对象两种形态（[toNamedObjects]），字段缺失一律置 null 不中断。
     * 失败时抛出的 [LuoguApiException] 一律带上状态码与 [LoginSnapshot.hint] 凭据诊断，
     * 使「凭据没读到 / 凭据被拒 / 结构不匹配」可分辨。
     */
    suspend fun getSolutions(pid: String, page: Int = 1): SolutionPage = withContext(Dispatchers.IO) {
        val url = "https://www.luogu.com.cn/problem/solution/$pid?page=$page&_contentOnly=1"
        val login = loginSnapshot()
        val request = Request.Builder()
            .url(url)
            .apply { LuoguHttpClient.HEADERS.forEach { (k, v) -> header(k, v) } }
            .apply { login.header?.let { header("Cookie", it) } }
            .get()
            .build()

        val body = try {
            LuoguHttpClient.getClient().newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                // 每条错误都带上状态码与凭据诊断（只含 uid 与 __client_id 长度），
                // 用来分清「凭据没读到」「凭据被服务器拒」「要先通过本题」三种情况。
                when (response.code) {
                    200 -> text
                    401 -> throw LuoguApiException("题解请求未被接受（HTTP 401，登录态无效或已过期）：${login.hint}")
                    403 -> throw LuoguApiException("题解访问被拒绝（HTTP 403${extractErrorMessage(text)}）：${login.hint}")
                    else -> throw LuoguApiException("题解获取失败：HTTP ${response.code}，${login.hint}")
                }
            }
        } catch (e: LuoguApiException) {
            throw e
        } catch (e: Exception) {
            throw LuoguApiException("题解网络异常：${e.message}", e)
        }

        val block = CONTEXT_REGEX.find(body)?.groupValues?.get(1)?.trim()
            ?: throw LuoguApiException(
                "题解数据块缺失（HTTP 200，正文 ${body.length} 字符，" +
                    (if (body.contains("csrf-token")) "含 csrf-token，疑似被反爬拦截" else "不含 csrf-token，疑似返回登录/错误页") +
                    "），${login.hint}"
            )

        val solutions = try {
            val root = json.parseToJsonElement(block) as? JsonObject
                ?: throw LuoguApiException("题解数据非 JSON 对象")
            val data = root["data"] as? JsonObject
                ?: throw LuoguApiException("题解响应缺少 data（顶层键：${root.keys.joinToString()}）")
            val solutionsEl = data["solutions"]
                ?: throw LuoguApiException("题解响应缺少 solutions（data 键：${data.keys.joinToString()}）")
            solutionsEl as? JsonObject
                ?: throw LuoguApiException("solutions 不是对象（实际为 ${solutionsEl::class.simpleName}）")
        } catch (e: LuoguApiException) {
            throw e
        } catch (e: Exception) {
            throw LuoguApiException("题解解析失败：${e.message}", e)
        }

        val resultEl = solutions["result"]
            ?: throw LuoguApiException("题解响应缺少 result（solutions 键：${solutions.keys.joinToString()}）")

        val items = toNamedObjects(resultEl).mapNotNull { (fallbackId, obj) ->
            // 题解在洛谷内部就是一篇 article，标识是字符串 lid；万一改回数字 id 也一并兼容
            val lid = obj.asString("lid")
                ?: (obj["id"] as? JsonPrimitive)?.content?.trim()?.ifBlank { null }
                ?: fallbackId?.toString()
                ?: return@mapNotNull null
            SolutionSummary(
                lid = lid,
                title = obj.asString("title") ?: "题解 $lid",
                author = (obj["author"] as? JsonObject)?.asString("name"),
                upvote = obj.asInt("upvote"),
                contentMarkdown = obj.asString("content"),
            )
        }

        // result 有内容却一条都没解析出来 → 条目字段名与预期不符，直接报出真实键名
        if (items.isEmpty() && resultEl is JsonArray && resultEl.isNotEmpty()) {
            val first = resultEl[0] as? JsonObject
                ?: throw LuoguApiException("题解条目不是 JSON 对象（首项为 ${resultEl[0]::class.simpleName}）")
            throw LuoguApiException("题解条目缺少 lid（首项键：${first.keys.joinToString()}）")
        }

        val total = solutions.asInt("count") ?: 0
        // 洛谷不下发 pageCount，前端按 perPage 自行分页；perPage 缺失时按本页条数估算
        val perPage = solutions.asInt("perPage") ?: items.size
        // 服务器报告有 N 篇却一条都没返回：不是「本题无题解」，而是参数或结构不匹配，如实报出
        if (items.isEmpty() && total > 0) {
            throw LuoguApiException(
                "服务器报告共 $total 篇题解，但 result 为空（solutions 键：${solutions.keys.joinToString()}）"
            )
        }
        SolutionPage(
            pid = pid,
            page = page,
            total = total,
            hasMore = perPage > 0 && total > 0 && page * perPage < total,
            solutions = items,
        )
    }

    /**
     * 查询今日打卡状态（GET 首页）。
     *
     * 判据是**打卡按钮在不在**：实测未打卡的登录首页有 `[name=punch]`，打过卡后按钮随整块 HTML 一起消失。
     * 这里只取状态，不取任何令牌——打卡本身走 [punch]，那条 GET 不需要验证码。
     *
     * 未登录直接返回不可打卡：既省掉一次 ~74KB 的首页请求，也因为登录态缺失时页面本就没有打卡入口。
     * 首页偶尔被反爬打成空壳，故与其余请求一样走 [LuoguHttpClient.getClient]（内存 CookieJar 会先拿到
     * C3VK 再跟随重定向），并带上 [loginSnapshot] 的凭据。
     */
    suspend fun getPunchState(): PunchState = withContext(Dispatchers.IO) {
        val login = loginSnapshot()
        if (login.header == null) return@withContext PunchState(punchable = false)

        val request = Request.Builder()
            .url("https://www.luogu.com.cn/")
            .apply { LuoguHttpClient.HEADERS.forEach { (k, v) -> header(k, v) } }
            .header("Cookie", login.header)
            .get()
            .build()
        val body = try {
            LuoguHttpClient.getClient().newCall(request).execute().use { response ->
                if (response.code != 200) throw LuoguApiException("打卡状态获取失败：HTTP ${response.code}，${login.hint}")
                response.body?.string().orEmpty()
            }
        } catch (e: LuoguApiException) {
            throw e
        } catch (e: Exception) {
            throw LuoguApiException("打卡状态网络异常：${e.message}", e)
        }

        PunchState(punchable = PUNCH_BUTTON_REGEX.containsMatchIn(body))
    }

    /**
     * 打卡：`GET /index/ajax_punch?_=<毫秒时间戳>`，**不需要任何令牌或验证码**。
     *
     * 端点形态取自公开实现（Hughpig/LuoguAutoPunch）：只要登录 cookie、`Referer` 指向首页
     * 与 `x-requested-with: XMLHttpRequest`。首页那段 jQuery 的 `$.post(…, {verify})` 是**另一条带图形码的旧路径**，
     * 拿空 verify 去 POST 会被判 `{"status":400,"data":"会话超时…"}`——本仓库先误走了那条路。
     *
     * 响应是 JSON：`code` 200 成功（`more.html` 为当日运势文案）、201「今天已经打过卡了」、401 cookie 失效。
     */
    suspend fun punch(): PunchResult = withContext(Dispatchers.IO) {
        val login = loginSnapshot()
        if (login.header == null) {
            throw LuoguApiException("打卡需要登录（到「登录」页填写 __client_id 与 _uid）")
        }
        val request = Request.Builder()
            .url("https://www.luogu.com.cn/index/ajax_punch?_=${System.currentTimeMillis()}")
            .apply { LuoguHttpClient.HEADERS.forEach { (k, v) -> header(k, v) } }
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Cookie", login.header)
            .get()
            .build()

        val body = try {
            LuoguHttpClient.getClient().newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.code !in 200..299) {
                    throw LuoguApiException("打卡请求失败：HTTP ${response.code}，${login.hint}")
                }
                text
            }
        } catch (e: LuoguApiException) {
            throw e
        } catch (e: Exception) {
            throw LuoguApiException("打卡网络异常：${e.message}", e)
        }

        parsePunchResponse(body)
    }

    /**
     * 打卡响应的两种形态都得吃：
     *
     * 1. 正常 JSON（GET 打卡）：`{code:200, message:"…", more:{html:"<…>当日运势<…>"}}`，
     *    成功时给用户看的文案在 `more.html` 里（剥掉标签）；
     * 2. 旧版 POST 路径的失败信封：`{"status":400,"data":"会话超时，请刷新页面后重试","trace":""}`，
     *    原因在 `data`。
     *
     * 两种都取不到状态码时抛 [LuoguApiException] 并带响应片段，避免把「解析不出来」说成「打卡失败」。
     */
    private fun parsePunchResponse(body: String): PunchResult {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        if (root != null) {
            val status = root["code"]?.jsonPrimitive?.intOrNull ?: root["status"]?.jsonPrimitive?.intOrNull
            if (status != null) {
                val fortune = (root["more"] as? JsonObject)?.let { more ->
                    more["html"]?.jsonPrimitive?.contentOrNull
                        ?.replace(Regex("<[^>]+>"), "")
                        ?.replace("&nbsp;", " ")
                        ?.trim()
                        ?.ifBlank { null }
                }
                return PunchResult(
                    code = status,
                    message = root["message"]?.jsonPrimitive?.contentOrNull
                        ?: fortune
                        ?: root["data"]?.jsonPrimitive?.contentOrNull,
                )
            }
        }
        val code = PUNCH_CODE_REGEX.find(body)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw LuoguApiException("打卡响应无法解析（长度 ${body.length}）：${body.take(120)}")
        return PunchResult(code = code, message = PUNCH_MESSAGE_REGEX.find(body)?.groupValues?.get(2))
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
     * 拉取用户资料。
     *
     * 请求 `GET /user/{uid}?_contentOnly=1`（带登录 cookie，复用浏览器伪装头），按以下顺序解析：
     * 1. **先整体按 JSON 解析**：部分网关/接口会直接返回 JSON，此时取 `data.user`；
     * 2. 失败再退回 [CONTEXT_REGEX]：实测该 URL 返回的是 `text/html`，JSON 藏在
     *    `<script id="lentille-context">` 脚本块内，从中取 `data.user`；
     * 3. 两者都拿不到（如被反爬拦截）时，走 `/api/user/search?keyword=<uid>` 回退，至少取到用户名。
     *
     * 映射一律**宽松**：字段缺失 / 类型不符置 null，不中断解析；回退仍取不到 name 时抛可读
     * [LuoguApiException]。
     */
    suspend fun getUserProfile(uid: Int): UserProfile = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://www.luogu.com.cn/user/$uid?_contentOnly=1")
            .apply { LuoguHttpClient.HEADERS.forEach { (k, v) -> header(k, v) } }
            // 与提交/评测一致：带上登录 cookie，确保拿到本人可见的完整资料
            .apply { loginCookieHeader()?.let { header("Cookie", it) } }
            .get()
            .build()

        val body = try {
            LuoguHttpClient.getClient().newCall(request).execute().use { response ->
                if (response.code != 200) {
                    throw LuoguApiException("用户资料请求失败：HTTP ${response.code}")
                }
                response.body?.string() ?: throw LuoguApiException("用户资料响应为空")
            }
        } catch (e: LuoguApiException) {
            throw e
        } catch (e: Exception) {
            throw LuoguApiException("用户资料网络异常：${e.message}", e)
        }

        // 主路径 1：整个响应体直接按 JSON 解析（返回 JSON 的网关形态）
        parseProfileFromJson(body, uid)?.let { return@withContext it }

        // 主路径 2：从 lentille-context 脚本块解析（实测该 URL 的 HTML 形态）
        parseProfileFromLentille(body, uid)?.let { return@withContext it }

        // 回退：以上都失败时用 /api/user/search 至少取到用户名
        val fallbackName = try {
            fallbackUserName(uid)
        } catch (_: Exception) {
            null
        }
        if (fallbackName.isNullOrBlank()) {
            throw LuoguApiException("用户资料解析失败（数据块缺失或非 JSON），请稍后重试")
        }
        UserProfile(uid = uid, name = fallbackName)
    }

    /** 整个响应体即 JSON（`data.user`）时的解析；解析失败返回 null（不抛异常）。 */
    private fun parseProfileFromJson(body: String, uid: Int): UserProfile? = try {
        val root = json.parseToJsonElement(body) as? JsonObject
        val data = root?.get("data") as? JsonObject
        val user = data?.get("user") as? JsonObject
        user?.toUserProfile(uid)
    } catch (_: Exception) {
        null
    }

    /**
     * 从 `lentille-context` 脚本块解析 `data.user` 为用户资料；任一步失败返回 null（不抛异常），
     * 交由 [getUserProfile] 决定是否回退。
     */
    private fun parseProfileFromLentille(body: String, uid: Int): UserProfile? = try {
        val block = CONTEXT_REGEX.find(body)?.groupValues?.get(1)?.trim()
        val root = block?.let { json.parseToJsonElement(it) as? JsonObject }
        val data = root?.get("data") as? JsonObject
        val user = data?.get("user") as? JsonObject
        user?.toUserProfile(uid)
    } catch (_: Exception) {
        null
    }

    /** 回退路径：请求 `/api/user/search?keyword=<uid>` 取用户名；失败返回 null。 */
    private fun fallbackUserName(uid: Int): String? {
        val request = Request.Builder()
            .url("https://www.luogu.com.cn/api/user/search?keyword=$uid")
            .apply { LuoguHttpClient.HEADERS.forEach { (k, v) -> header(k, v) } }
            .get()
            .build()
        return LuoguHttpClient.getClient().newCall(request).execute().use { response ->
            if (response.code != 200) return@use null
            response.body?.string()?.let { parseSearchUser(it) }
        }
    }

    /** 把 `data.user` 对象宽松映射为 [UserProfile]：字段缺失 / 类型不符 → null。 */
    private fun JsonObject.toUserProfile(fallbackUid: Int): UserProfile = UserProfile(
        uid = asInt("uid") ?: fallbackUid,
        name = asString("name"),
        avatarUrl = asString("avatar"),
        slogan = asString("slogan"),
        color = asString("color"),
        ccfLevel = asInt("ccfLevel"),
        ranking = asInt("ranking"),
        followingCount = asInt("followingCount"),
        followerCount = asInt("followerCount"),
        passedProblemCount = asInt("passedProblemCount"),
        submittedProblemCount = asInt("submittedProblemCount"),
        eloValue = asInt("eloValue"),
        verified = asBool("verified"),
    )

    /**
     * 下载头像图片字节；请求失败、响应为空或解码前异常时返回 null（静默，交由 UI 跳过）。
     * 头像位于 CDN，公开可访问，无需携带登录 cookie。
     */
    suspend fun fetchAvatar(url: String): ByteArray? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        try {
            val request = Request.Builder()
                .url(url)
                .apply { LuoguHttpClient.HEADERS.forEach { (k, v) -> header(k, v) } }
                .get()
                .build()
            LuoguHttpClient.getClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.bytes()?.takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            null
        }
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
     * 一次钥匙串读取形成的登录态快照，供请求头与诊断文案共用
     * （分两次读可能拿到不一致的值，那正是「已登录却提示需登录」难以判断的原因之一）。
     */
    private data class LoginSnapshot(val uid: Int?, val clientId: String?) {

        /** OkHttp 的 `Cookie` 请求头值；缺任一凭据（或 uid 非数字）时为 null。 */
        val header: String? =
            if (uid == null || clientId.isNullOrBlank()) null else "_uid=$uid;__client_id=$clientId"

        /** 诊断文案：**只报 uid 与 `__client_id` 的长度，绝不输出其值**。 */
        val hint: String = when {
            uid == null && clientId.isNullOrBlank() -> "未读到登录凭据"
            clientId.isNullOrBlank() -> "_uid=$uid 但 __client_id 缺失"
            uid == null -> "有 __client_id（${clientId.length} 位）但 _uid 缺失或不是数字"
            else -> "_uid=$uid、__client_id 已携带（${clientId.length} 位）"
        }
    }

    /** 读取已保存的登录 cookie 快照。 */
    private fun loginSnapshot(): LoginSnapshot {
        val cookies = SecureCookieStore.load()
        return LoginSnapshot(
            uid = cookies["_uid"].orEmpty().trim().toIntOrNull(),
            clientId = cookies["__client_id"].orEmpty().trim().ifBlank { null },
        )
    }

    /**
     * 从安全存储读取登录 cookie，拼成 `_uid=<uid>; __client_id=<clientID>` 请求头。
     * 取不到（未登录 / uid 非数字）时返回 null。
     */
    private fun loginCookieHeader(): String? = loginSnapshot().header

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
            compileError = parseCompileError(recordBlock),
        )
    }

    /**
     * 编译错误详情。实测在 **`record.detail.compileResult.message`**（洛谷前端也是读这个路径），
     * 顶层 `record.compileErrorMessage` 那种字段根本不存在——以前这里恒为 null，
     * 于是评测页的「跳到出错行」从来没亮过。留几种形态兜底，命中即用。
     */
    private fun parseCompileError(record: JsonObject?): String? {
        if (record == null) return null
        val detail = record["detail"] as? JsonObject
        return listOfNotNull(
            (detail?.get("compileResult") as? JsonObject)?.asString("message"),
            detail?.asString("compileError"),
            (record["compileResult"] as? JsonObject)?.asString("message"),
            record.asString("compileErrorMessage"),
            record.asString("compileError"),
        ).firstOrNull()
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

    /** 从 JSON 对象宽松读取字符串字段；空串 / 非字符串 / 缺失 → null。 */
    private fun JsonObject.asString(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifBlank { null }

    /** 从 JSON 对象宽松读取布尔字段；非布尔 / 缺失 / null → null。 */
    private fun JsonObject.asBool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

}