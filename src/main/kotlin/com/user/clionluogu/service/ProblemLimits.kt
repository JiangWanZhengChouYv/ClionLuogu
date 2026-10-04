package com.user.clionluogu.service

import java.io.File

/**
 * 题面上的时空限制（拉题落盘的 `Pxxx.md` 里那两行）。
 *
 * 「默认时空在题里」是用户要的口感：本地跑一轮该按这道题的限制来判 TLE / MLE，
 * 而不是插件写死的 5 秒。取不到（老文件没这两行、写的是「暂无」）就回 null，
 * 由调用方退回插件默认，**不去猜**。
 */
object ProblemLimits {

    /** [timeMs] 毫秒、[memoryMb] 兆；缺任何一个就是 null。 */
    data class Limits(val timeMs: Int?, val memoryMb: Int?)

    private val TIME_REGEX = Regex("""时间限制\D*?(\d+)""")
    private val MEMORY_REGEX = Regex("""内存限制\D*?(\d+)""")

    /** 单位缺省时按洛谷那两行的口径：时间是 ms，内存是 KB。 */
    private const val MB_IN_KB = 1024L

    /** 能接受的区间：下限太小会误杀正常程序，上限等于没限。对拍与自测共用同一区间。 */
    private const val MIN_TIME_MS = 50
    private const val MAX_TIME_MS = 600_000
    private const val MIN_MEMORY_MB = 1
    private const val MAX_MEMORY_MB = 8192

    @JvmStatic
    fun parse(mdText: String?): Limits {
        if (mdText.isNullOrEmpty()) return Limits(null, null)
        val timeMs = TIME_REGEX.find(mdText)?.groupValues?.get(1)?.toIntOrNull()
        val kb = MEMORY_REGEX.find(mdText)?.groupValues?.get(1)?.toLongOrNull()
        return Limits(timeMs = timeMs?.takeIf { it > 0 }, memoryMb = memoryMbFromKb(kb))
    }

    /** 只读文件开头一小段：那两行一定在最前面，整份 .md 可能带大段样例。 */
    @JvmStatic
    fun fromMd(md: File?): Limits {
        if (md == null || !md.isFile) return Limits(null, null)
        val head = runCatching {
            md.inputStream().use { stream ->
                val buffer = ByteArray(4096)
                val read = stream.read(buffer)
                if (read <= 0) "" else String(buffer, 0, read, Charsets.UTF_8)
            }
        }.getOrNull().orEmpty()
        return parse(head)
    }

    /** KB → MB，向上取整（131072 KB → 128）；非正数视为没有。 */
    @JvmStatic
    fun memoryMbFromKb(kb: Long?): Int? {
        if (kb == null || kb <= 0) return null
        return ((kb + MB_IN_KB - 1) / MB_IN_KB).toInt()
    }

    /** 时间字段 → 毫秒：空白 / 非数字退回 [defaultMs]，越界夹住（不猜他想写什么）。 */
    @JvmStatic
    fun parseTimeMs(text: String?, defaultMs: Int): Int {
        val parsed = text?.trim()?.toIntOrNull() ?: return defaultMs
        return parsed.coerceIn(MIN_TIME_MS, MAX_TIME_MS)
    }

    /** 内存字段 → MB：空白 / 非数字 / 越界都算「不比内存」。 */
    @JvmStatic
    fun parseMemoryMb(text: String?): Int? {
        val parsed = text?.trim()?.toIntOrNull() ?: return null
        if (parsed < MIN_MEMORY_MB || parsed > MAX_MEMORY_MB) return null
        return parsed
    }

    /** MB → 字节（判 MLE 用）。 */
    @JvmStatic
    fun mbToBytes(mb: Int?): Long? = mb?.takeIf { it > 0 }?.let { it.toLong() * 1024 * 1024 }
}
