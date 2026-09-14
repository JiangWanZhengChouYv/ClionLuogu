package com.user.clionluogu.api

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

    /** 应用层需要的浏览器伪装请求头。 */
    val HEADERS: Map<String, String> = mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
        "Accept-Language" to "zh-CN,zh;q=0.9",
        "Referer" to "https://www.luogu.com.cn/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    )

    fun getClient(): OkHttpClient = client
}