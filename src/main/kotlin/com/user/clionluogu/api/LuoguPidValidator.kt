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
}