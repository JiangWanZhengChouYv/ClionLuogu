package com.user.clionluogu.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request

/** 洛谷 API 业务异常，供 UI 层提示。 */
class LuoguApiException(msg: String, cause: Throwable? = null) : Exception(msg, cause)

object LuoguApiService {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        encodeDefaults = true
    }

    private val CONTEXT_REGEX =
        Regex("""(?s)<script id="lentille-context"[^>]*type="application/json"[^>]*>(.*?)</script>""")

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
}