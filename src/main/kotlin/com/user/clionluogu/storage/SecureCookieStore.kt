package com.user.clionluogu.storage

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager

/**
 * 洛谷登录 cookie 的安全存储。
 *
 * 借助 IntelliJ 平台的 [PasswordSafe]（IDE 系统钥匙串 / Password Safe）持久化键值对，
 * 不落明文文件。每个 cookie 键存为一个独立的 credential：
 *   serviceName = ClionLuogu.cookie.<cookieName>，userName = <cookieName>；
 * 额外用一个保留名 `_names` 记录全部 cookie 名（以逗号分隔），以便 [load]/[clear] 枚举。
 *
 * Cookie 值本身不敏感，此处仅做一个便捷封装；敏感内容（会话凭证）由 PasswordSafe 保护。
 */
object SecureCookieStore {

    private const val SERVICE_NAME = "ClionLuogu.cookie"
    private const val NAMES_KEY = "_names"
    private const val SEPARATOR = ","

    /** 登录判定所需的两个关键 cookie。 */
    private const val KEY_CLIENT_ID = "__client_id"
    private const val KEY_UID = "_uid"

    private fun attributes(cookieName: String): CredentialAttributes =
        CredentialAttributes(generateServiceName(SERVICE_NAME, cookieName), cookieName)

    private fun passwordSafe(): PasswordSafe =
        ApplicationManager.getApplication().getService(PasswordSafe::class.java)

    /** 持久化 cookie 键值对（覆盖同名项），空值跳过。 */
    fun save(cookies: Map<String, String>) {
        val nonBlank = cookies.filterValues { it.isNotBlank() }
        if (nonBlank.isEmpty()) return

        // 记录当前按键名（合并已存在的按键名，保留历史 cookie）
        val oldNames = readStoredNames()
        val allNames = (oldNames + nonBlank.keys).distinct()

        nonBlank.forEach { (name, value) -> passwordSafe().setPassword(attributes(name), value) }
        passwordSafe().setPassword(attributes(NAMES_KEY), allNames.joinToString(SEPARATOR))
    }

    /** 读回全部已存 cookie。 */
    fun load(): Map<String, String> {
        val names = readStoredNames()
        if (names.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        names.forEach { name ->
            passwordSafe().getPassword(attributes(name))?.let { result[name] = it }
        }
        return result
    }

    /** 删除已存的所有 cookie。 */
    fun clear() {
        val names = readStoredNames()
        names.forEach { name -> passwordSafe().setPassword(attributes(name), null) }
        passwordSafe().setPassword(attributes(NAMES_KEY), null)
    }

    /** ____client_id 与 _uid 是否均有非空值（视为已登录）。 */
    fun hasLogin(): Boolean {
        val cookies = load()
        return !cookies[KEY_CLIENT_ID].isNullOrBlank() && !cookies[KEY_UID].isNullOrBlank()
    }

    private fun readStoredNames(): List<String> {
        val raw = passwordSafe().getPassword(attributes(NAMES_KEY)) ?: return emptyList()
        return raw.split(SEPARATOR).map { it.trim() }.filter { it.isNotBlank() }
    }
}