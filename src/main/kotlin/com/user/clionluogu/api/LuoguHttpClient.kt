package com.user.clionluogu.api

import com.user.clionluogu.storage.SecureCookieStore
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 共享 OkHttpClient 工程单例。
 *
 * 洛谷无官方 API，拉题需：内存 CookieJar + 跟随重定向（无 Cookie 直连返回 302 并种下 C3VK=xxx）。
 */
object LuoguHttpClient {

    /** 供注入使用的目标 host。 */
    private const val COOKIE_HOST = "www.luogu.com.cn"

    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore.computeIfAbsent(url.host) { mutableListOf() }
                .addAll(cookies)
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore[url.host] ?: emptyList()
        }
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 会话内是否已尝试从 PasswordSafe 恢复注入持久化 cookie。 */
    @Volatile
    private var injectedFromStore = false

    /** 应用层需要的浏览器伪装请求头。 */
    val HEADERS: Map<String, String> = mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
        "Accept-Language" to "zh-CN,zh;q=0.9",
        "Referer" to "https://www.luogu.com.cn/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    )

    /** 每次请求前先调 ensureCookiesInjected，以在重启后用持久化 cookie 恢复登录态。 */
    fun getClient(): OkHttpClient {
        ensureCookiesInjected()
        return client
    }

    /**
     * 惰性恢复：会话内仅尝试一次从 PasswordSafe 重载持久化 cookie 并注入内存 CookieJar。
     * 幂等——已有标识直接返回，避免每个请求都查 PasswordSafe。
     */
    private fun ensureCookiesInjected() {
        if (injectedFromStore) return
        val cookies = SecureCookieStore.load()
        if (cookies.isNotEmpty()) {
            injectCookies(cookies)
        }
        injectedFromStore = true
    }

    /**
     * 把键值对 cookie 注入 target 条目（保留既有响应 cookie，如 C3VK 等）。
     * 主要用于注入登录凭证 __client_id / _uid。
     *
     * 会过滤掉 name/value 含非 ASCII 或 `=`/`;`/空格等非法字符的项——这些 Cookie 会造成
     * [Cookie.Builder.build] 抛异常（如 `Unexpected char 0x… in Cookie value`），
     * 从而让拉题/提交被误报为「网络请求异常」。只注入合法 ASCII 的凭证 cookie。
     */
    fun injectCookies(cookies: Map<String, String>) {
        if (cookies.isEmpty()) return
        injectedFromStore = true
        val list = cookieStore.computeIfAbsent(COOKIE_HOST) { mutableListOf() }
        cookies.forEach { (name, value) ->
            if (isUsableCookie(name) && isUsableCookie(value)) {
                runCatching {
                    Cookie.Builder()
                        .name(name)
                        .value(value)
                        .domain("luogu.com.cn")
                        .path("/")
                        .httpOnly()
                        .build()
                }.onSuccess { list.add(it) }
                // onFailure 静默跳过非法 cookie
            }
        }
    }

    /** cookie 的 name / value 是否为 OkHttp 可用（仅 ASCII 且不含分隔/空白字符）。 */
    private fun isUsableCookie(raw: String): Boolean {
        if (raw.isBlank()) return false
        return raw.all { it.code in 0x21..0x7E }  // 打印 ASCII，不含空格/控制/非 ASCII
    }

    /** 清空内存 CookieJar。 */
    fun clearCookies() {
        cookieStore.clear()
        injectedFromStore = false
    }
}