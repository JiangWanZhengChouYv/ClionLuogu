package com.user.clionluogu.service

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 子进程峰值内存的**实测**测量器。
 *
 * 为什么不是「按操作系统名选一条路」：插件以前就因为猜环境翻过车（找产物、`bits` 兼容头都被否掉）。
 * 这里只认一条证据：**真的拿一条 trivial 命令跑一遍外部 `time`，能解析出峰值才算可用**。
 * 解析不出来就返回 null，调用方必须在界面上写「这台机器量不到子进程内存，所以不判 MLE」——
 * 宁可少一个数字，也不给一个编出来的数字。
 *
 * 两条已知能用的路（都靠输出文本判定，不按 os.name）：
 * - macOS 自带的 `/usr/bin/time -l`：`       1196272  peak memory footprint`（字节）；
 * - GNU time 的 `/usr/bin/time -v`：`Maximum resident set size (kbytes): 1234`（KB）。
 * Windows 上 `/usr/bin/time` 根本不存在 → 探测自然失败。
 */
object ResourceMeter {

    /** 解析格式，跟着实际跑通的参数走。 */
    enum class Flavor { MAC_L, GNU_V }

    /** 一个可用测量器：[tool] 是外部 time，[flavor] 决定用哪个参数与怎么解析。 */
    data class Meter(val tool: File, val flavor: Flavor)

    private const val TOOL_PATH = "/usr/bin/time"

    /** trivial 被测程序：只为验证「能不能解析出峰值」。 */
    private const val PROBE_VICTIM = "/bin/true"

    private const val PROBE_TIMEOUT_MS = 5_000L

    @Volatile
    private var cached: Meter? = null

    @Volatile
    private var probed = false

    /**
     * 探测一次并缓存。**不能在 EDT 调**（要起子进程）。
     *
     * 返回 null = 这台机器量不到峰值内存；调用方要把它显示成一句人话，而不是静默不判。
     */
    fun available(): Meter? {
        if (probed) return cached
        val meter = runCatching { detect() }.getOrNull()
        cached = meter
        probed = true
        return meter
    }

    /** 探针与单测用的复位（缓存是进程级的，测试之间要能重来）。 */
    fun resetCacheForTests() {
        cached = null
        probed = false
    }

    private fun detect(): Meter? {
        val tool = File(TOOL_PATH)
        if (!tool.isFile || !tool.canExecute()) return null
        // 先试 mac 那条（macOS 上 -v 是 BSD time 的「version」语义，会给一堆别的东西）
        for (flavor in listOf(Flavor.MAC_L, Flavor.GNU_V)) {
            val output = runTool(tool, flag(flavor)) ?: continue
            if (parsePeak(output, flavor) != null) return Meter(tool, flavor)
        }
        return null
    }

    private fun runTool(tool: File, flag: String): String? {
        val process = runCatching {
            ProcessBuilder(tool.absolutePath, flag, PROBE_VICTIM).redirectErrorStream(true).start()
        }.getOrNull() ?: return null
        val finished = runCatching { process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!finished) {
            runCatching { process.destroyForcibly() }
            return null
        }
        val text = runCatching { process.inputStream.readBytes().toString(Charsets.UTF_8) }.getOrNull()
        runCatching { process.destroy() }
        return text
    }

    private fun flag(flavor: Flavor): String = if (flavor == Flavor.MAC_L) "-l" else "-v"

    /**
     * 真正要执行的 argv：**包在测量器后面**。
     *
     * 没有测量器时原样跑产物。注意包一层之后，被杀的是 `time`，**它下面那个程序还活着** ——
     * 所以超时 / 取消时 [ProcessRunner] 必须连子进程树一起端掉。
     */
    @JvmStatic
    fun commandFor(meter: Meter?, exe: File): List<String> = when (meter) {
        null -> listOf(exe.absolutePath)
        else -> listOf(meter.tool.absolutePath, flag(meter.flavor), exe.absolutePath)
    }

    /** mac：`       1196272  peak memory footprint`，单位字节。 */
    @JvmStatic
    fun parsePeakMac(text: String): Long? =
        Regex("""^\s*(\d[\d,]*)\s+peak memory footprint""", RegexOption.MULTILINE)
            .find(text)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()?.takeIf { it > 0 }

    /** GNU：`Maximum resident set size (kbytes): 1234`，单位 KB → 换字节。 */
    @JvmStatic
    fun parsePeakGnu(text: String): Long? =
        Regex("""Maximum resident set size \(kbytes\):\s*([\d,]+)""")
            .find(text)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()
            ?.takeIf { it > 0 }?.let { it * 1024L }

    @JvmStatic
    fun parsePeak(text: String, flavor: Flavor): Long? = when (flavor) {
        Flavor.MAC_L -> parsePeakMac(text)
        Flavor.GNU_V -> parsePeakGnu(text)
    }

    /** 字节 → MB（向上取整，峰值 1 字节也算 1 MB）。 */
    @JvmStatic
    fun mb(bytes: Long?): Int? {
        if (bytes == null || bytes <= 0) return null
        return ((bytes + 1048575L) / 1048576L).toInt()
    }

    /**
     * 报表行的标志串（两边 `time` 的报表字段名）。
     *
     * 只**正向**认这些行才删，别的一律留着 —— 反向匹配（「像统计行就删」）会把程序自己
     * 打到 stderr 的内容吃掉，那比多显示几行报表严重得多。
     */
    private val REPORT_MARKERS = listOf(
        "peak memory footprint", "instructions retired", "cycles elapsed", "average memory footprint",
        "Maximum resident set size", "Minimum resident set size", "Average resident set size",
        "Average total size", "Average unhashed stack size", "Average hashed stack size",
        "Average data size", "Percent of CPU this job got", "Elapsed (wall clock) time",
        "User time per cent", "System time", "Command being timed", "Exit status",
        "Voluntary context switches", "Involuntary context switches", "Swaps",
        "File system inputs", "File system outputs", "Socket messages sent", "Socket messages received",
        "Signals delivered", "Page size", "System calls", "Swap ins", "Swap outs",
    )

    /**
     * BSD time 的三行计时：`        real         0.00s`。
     *
     * 用 `containsMatchIn` 而不是 `matches`：数字后面还跟着单位（`s`），
     * 整行匹配会把这三行漏掉、混进用户看到的 stderr 里（探针抓到的）。
     */
    private val TIMING = Regex("""^\s*(real|user|sys)\s+[\d.]+""")

    /** 这一行是不是测量器自己的报表。 */
    @JvmStatic
    fun isReportLine(line: String): Boolean =
        REPORT_MARKERS.any { line.contains(it, ignoreCase = true) } || TIMING.containsMatchIn(line)

    /**
     * 剥掉报表行，只留程序自己的 stderr。
     *
     * 报表和程序的 stderr 是同一条流（`time` 把报表写到自己那条 stderr，被测程序继承同一个 fd），
     * 不剥的话「stderr」那一节里会夹着 `peak memory footprint` 这种插件内部的东西。
     */
    @JvmStatic
    fun stripReport(text: String): String =
        text.lineSequence().filterNot { isReportLine(it) }.joinToString("\n")

    /** MB → 判 MLE 用的字节阈值。 */
    @JvmStatic
    fun bytesOfMb(memoryMb: Int?): Long? = memoryMb?.takeIf { it > 0 }?.times(1048576L)
}
