package com.user.clionluogu.api

object LuoguPidValidator {

    private val PID_REGEX = Regex("^[A-Za-z]{1,4}\\d{1,5}$")

    /** 校验题号格式：字母前缀(1-4位) + 数字(1-5位)，如 P1001、B2001。 */
    fun isValidPid(pid: String): Boolean {
        val trimmed = pid.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.all { it.isDigit() }) return false
        if (!PID_REGEX.matches(trimmed)) return false
        return true
    }

    /**
     * 从文件名反推题号：去掉最后一个扩展名后过 [isValidPid]。
     *
     * `P1001.cpp → P1001`；`main.cpp`、`临时 文件.cpp` 这类一律返回 null——
     * 原先各处只做了 `removeSuffix(".cpp")`，会把垃圾字符串塞进题号输入框当默认值。
     */
    fun pidFromFileName(name: String): String? {
        val stem = name.substringBeforeLast('.', name).trim()
        return stem.takeIf { isValidPid(it) }
    }
}