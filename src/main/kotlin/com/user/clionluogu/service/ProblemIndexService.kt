package com.user.clionluogu.service

import java.io.File

/**
 * 一道题在项目根下留下的四类产物：源文件、题面、样例目录、反例目录。缺哪类就是 null。
 *
 * 由 [ProblemIndexService.scan] 产出，只反映磁盘现状（不做缓存、不猜子目录）。
 */
data class ProblemEntry(
    val pid: String,
    val cpp: File?,
    val md: File?,
    val samples: File?,
    val cases: File?,
) {
    val sampleCount: Int get() = fileCount(samples)
    val caseCount: Int get() = fileCount(cases)

    /** 实际存在的东西，给确认框和详情区用；目录带尾部 `/` 与文件数。 */
    fun presentNames(): List<String> = buildList {
        cpp?.let { add(it.name) }
        md?.let { add(it.name) }
        samples?.let { add("${it.name}/（$sampleCount 个文件）") }
        cases?.let { add("${it.name}/（$caseCount 个文件）") }
    }

    /** 四类产物一个都没有（只在提交记录里见过这题）。 */
    fun isEmpty(): Boolean = cpp == null && md == null && samples == null && cases == null

    private companion object {
        fun fileCount(dir: File?): Int =
            runCatching { dir?.listFiles()?.count { it.isFile } }.getOrNull() ?: 0
    }
}

/**
 * 本地题库索引：扫项目根**一层**目录，按题号把拉题落盘的产物归并成一行。
 *
 * 只认四种精确后缀（`.cpp` / `.md` / `_samples` / `_cases`），与 [AcCleanupService] 的删除
 * 口径共用同一套名字规则——列出来却删不掉、或删掉了没列出来的东西，都是不能接受的。
 * 改过名、放进子目录的文件一律不进索引（也不在可删范围内），因为一旦允许模糊匹配，
 * 一个不可逆的删除功能就没有「确定删的是哪个」可言了。
 *
 * 用 [File] 而不是 `VirtualFile`：扫描只看磁盘现状（删除前用户可能在 IDE 外面动过手），
 * 也省掉读动作（read action）的线程要求。
 */
object ProblemIndexService {

    private const val CPP = ".cpp"
    private const val MD = ".md"
    private const val SAMPLES = "_samples"
    private const val CASES = "_cases"

    /** 题号格式与 [com.user.clionluogu.api.LuoguPidValidator] 同口径：字母前缀 + 数字。 */
    private val ROOT_REGEX =
        Regex("^([A-Za-z]{1,4}\\d{1,5})(\\.cpp|\\.md|_samples|_cases)$", RegexOption.IGNORE_CASE)

    /** 文件名里的题号部分；不是本题产物时返回 null。 */
    @JvmStatic
    fun pidOfName(name: String): String? = ROOT_REGEX.matchEntire(name)?.groupValues?.get(1)

    /** 文件名属于哪一类产物（统一成小写形态返回）；无关文件返回 null。 */
    @JvmStatic
    fun kindOfName(name: String): String? =
        ROOT_REGEX.matchEntire(name)?.groupValues?.get(2)?.lowercase()

    /** 名字列表 → 题号 → 命中的文件名（原样、按输入顺序）。给探针与「有几个文件」用。 */
    @JvmStatic
    fun scanNames(names: List<String>): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for (name in names) {
            val pid = pidOfName(name) ?: continue
            out.getOrPut(pid.uppercase()) { mutableListOf() }.add(name)
        }
        return out
    }

    /** 题号里的数字部分；没有数字返回 0（`P999` 才能排在 `P1001` 前面）。 */
    @JvmStatic
    fun pidNumber(pid: String): Int = pid.filter { it.isDigit() }.toIntOrNull() ?: 0

    /** 字母前缀（大写），排序主键。 */
    @JvmStatic
    fun pidPrefix(pid: String): String = pid.takeWhile { it.isLetter() }.uppercase()

    /**
     * 扫一层目录。[baseDir] 为 null 或读不到（权限、IO 错）时返回空表而不抛异常——
     * 页签刚打开就弹堆栈，比列表短一截糟糕得多。
     */
    fun scan(baseDir: File?): List<ProblemEntry> {
        val children = runCatching { baseDir?.listFiles()?.toList() }.getOrNull() ?: return emptyList()
        val displayPid = LinkedHashMap<String, String>()
        val slots = LinkedHashMap<String, MutableMap<String, File>>()
        for (file in children) {
            val kind = kindOfName(file.name) ?: continue
            val rawPid = pidOfName(file.name) ?: continue
            // 类型也要对：`P1001_samples` 得是目录、`.cpp` 得是文件，否则不进索引
            if (kind == SAMPLES || kind == CASES) {
                if (!file.isDirectory) continue
            } else if (!file.isFile) {
                continue
            }
            val key = rawPid.uppercase()
            val slot = slots.getOrPut(key) {
                displayPid[key] = rawPid
                mutableMapOf()
            }
            // 同一类型有两份（大小写不同的卷上可能）时留字典序靠前的那个，别静默换掉
            val kept = slot[kind]
            if (kept == null || file.name < kept.name) slot[kind] = file
        }
        return displayPid.entries
            .map { (key, rawPid) ->
                val slot = slots.getValue(key)
                ProblemEntry(
                    pid = rawPid,
                    cpp = slot[CPP],
                    md = slot[MD],
                    samples = slot[SAMPLES],
                    cases = slot[CASES],
                )
            }
            .sortedWith(compareBy({ pidPrefix(it.pid) }, { pidNumber(it.pid) }, { it.pid.uppercase() }))
    }
}
