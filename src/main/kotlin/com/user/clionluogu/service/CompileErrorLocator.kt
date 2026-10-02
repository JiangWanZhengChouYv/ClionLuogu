package com.user.clionluogu.service

/**
 * 编译诊断里的一条带位置信息的结果。[line] / [column] 都是**1-based**，
 * 与编译器输出、以及给人看的行号口径一致；转 0-based 只发生在
 * [LuoguActions.openProblemFile] 里，别的地方不再换算第二次。
 */
data class CompileHit(
    val path: String,
    val line: Int,
    val column: Int,
    val message: String,
    val isError: Boolean,
)

/** 跳不成时的原因。UI 把它翻成 tooltip，别只留一个灰按钮。 */
enum class JumpMiss {
    NONE,
    NO_DIAGNOSTIC,
    NO_LOCATION,
    FOREIGN_FILE,
    LINE_OUT_OF_RANGE,
    NO_LOCAL_FILE,
}

/** [CompileErrorLocator.choose] 的结果：要么有 [hit]（此时 [miss] 为 [JumpMiss.NONE]），要么只有原因。 */
data class JumpChoice(val hit: CompileHit?, val miss: JumpMiss, val detail: String)

/**
 * 从编译诊断纯文本里挑出「该跳过去的那一行」。
 *
 * 只做名字白名单，不做黑名单：诊断里大量行号指向的是标准库内部
 * （`<vector>`、`/usr/include/c++/.../bits/stl_vector.h`），跳过去既没意义又会让人以为
 * 错在自己那几行。白名单只认竞赛提交常见的那几种文件名，其余一律不跳。
 *
 * 真正要打开的文件**永远由 pid 推出项目根下的 `Pxxx.cpp`**，不会去开诊断里写的那个路径——
 * 洛谷的编译路径是它服务器上的临时名（`Main.cpp`），拿它当本地路径既不合法也不安全。
 */
object CompileErrorLocator {

    /**
     * 分隔符同时接受半角与全角冒号：评测机是中文 locale 的 g++ 时，输出是
     * `Main.cpp:12:12: 错误：‘foo’在此作用域中尚未声明`，全角冒号 + 中文级别词。
     */
    private const val SEP = "[:：]"

    /** `path:line:col: [fatal |internal |致命](error|warning|错误|警告): message` */
    private val HIT_REGEX = Regex(
        "^(.+?)$SEP(\\d+)$SEP(\\d+)$SEP\\s*(fatal\\s+|internal\\s+|致命)?" +
            "(error|warning|错误|警告)$SEP\\s*(.*)$",
    )

    /** 两段式兜底：部分 g++ 输出不带列号。 */
    private val SHORT_REGEX = Regex("^(.+?)$SEP(\\d+)$SEP\\s*(error|warning|错误|警告)$SEP\\s*(.*)$")

    private val ERROR_WORDS = setOf("error", "错误")

    /** 解析不出位置时，tooltip 里带回首行样本的长度上限。 */
    private const val SAMPLE_LEN = 90

    /** 提交文件名：`P1001.cpp` 这类（前缀 1~4 个字母 + 1~5 位数字）或 OJ 常用的 `Main.cpp`。 */
    private val SUBMISSION_NAME =
        Regex("^([A-Za-z]{1,4}\\d{1,5}|main)\\.(cpp|cc|cxx|c|h|hpp)$", RegexOption.IGNORE_CASE)

    /**
     * 没有扩展名的提交文件名。实测洛谷把源码编成 `/tmp/compiler_lik1oiiu/src`，
     * 所以光看「带后缀的文件名」会把真·提交判成别人的文件（症状：按钮灰着说「不是你自己那份提交」）。
     * 标准库那一路径的 basename（`vector`、`stl_vector.h`、`basic_string.h`…）都不在这个表里。
     */
    private val BARE_SUBMISSION_NAMES = setOf("src", "main", "program", "submission")

    /**
     * 逐行解析诊断。[text] 为空返回空表。
     *
     * 每行先 `trim()`：编译器的 stdout/stderr 被合流后可能带 CRLF，
     * 留着行尾 `'\r'` 时 `$` 锚匹配不上，症状是「明明有 error 却点不出按钮」。
     * `note:` 行不当候选（clang 用它指出宏展开处，行号常指向别处）。
     * 级别词与冒号都兼容中英两种 locale（`error:` / `错误：`）。
     */
    @JvmStatic
    fun parseAll(text: String?): List<CompileHit> {
        if (text.isNullOrEmpty()) return emptyList()
        val hits = ArrayList<CompileHit>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.contains("note:")) continue
            HIT_REGEX.find(line)?.let { m ->
                val n = m.groupValues[2].toIntOrNull() ?: return@let
                hits.add(
                    CompileHit(
                        path = m.groupValues[1].trim(),
                        line = n,
                        column = m.groupValues[3].toIntOrNull() ?: 1,
                        message = m.groupValues[6],
                        isError = m.groupValues[5] in ERROR_WORDS || m.groupValues[4].isNotEmpty(),
                    ),
                )
            } ?: SHORT_REGEX.find(line)?.let { m ->
                // 没带列号的兜底：列号给 1，跳过去落在行首
                val n = m.groupValues[2].toIntOrNull() ?: return@let
                hits.add(
                    CompileHit(
                        path = m.groupValues[1].trim(),
                        line = n,
                        column = 1,
                        message = m.groupValues[4],
                        isError = m.groupValues[3] in ERROR_WORDS,
                    ),
                )
            }
        }
        return hits
    }

    /**
     * 解析不出位置时，把最可疑的那行原样带回 tooltip：格式对不上就一眼看出差在哪，别再猜一轮。
     * 优先带**含数字**的那行（`Main.cpp: 在函数…` 这类噪音行带回去没有信息量），
     * 一行数字都没有才退回首行。
     */
    private fun firstLineSample(text: String?): String {
        val lines = text?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList()
            ?: return ""
        if (lines.isEmpty()) return ""
        val line = lines.firstOrNull { candidate -> candidate.any(Char::isDigit) } ?: lines.first()
        return if (line.length <= SAMPLE_LEN) line else line.take(SAMPLE_LEN) + "…"
    }

    /**
     * 只看文件名（Windows 的反斜杠也当分隔符），判断是不是「自己那份提交」。
     *
     * 白名单已经足够严：标准库那一路径的 basename（`vector`、`stl_vector.h`、`string`…）
     * 都过不了 [SUBMISSION_NAME]，不需要再维护一份系统目录黑名单。
     */
    @JvmStatic
    fun isSubmissionFile(path: String): Boolean {
        if (path.isBlank()) return false
        val basename = path.replace('\\', '/').substringAfterLast('/').lowercase()
        return basename in BARE_SUBMISSION_NAMES || SUBMISSION_NAME.matches(basename)
    }

    /**
     * 挑一条用来跳的，并说清为什么挑不成。[pickJumpTarget] 的每一项闸门都在这里带上原因。
     *
     * [submittedLineCount] 为 null 表示行数未知（不卡上界）；超出上界说明诊断指向的是
     * 宏展开或模板实例化产生的行，本地那份文件里根本没有。
     * [hasLocalSource] 为 false（本地 `Pxxx.cpp` 已经不在了，或者被改名/挪走）时不给跳转目标。
     */
    @JvmStatic
    fun choose(text: String?, submittedLineCount: Int?, hasLocalSource: Boolean): JumpChoice {
        if (!hasLocalSource) return JumpChoice(null, JumpMiss.NO_LOCAL_FILE, "")
        if (text.isNullOrEmpty()) return JumpChoice(null, JumpMiss.NO_DIAGNOSTIC, "")
        val all = parseAll(text)
        if (all.isEmpty()) return JumpChoice(null, JumpMiss.NO_LOCATION, firstLineSample(text))
        val ours = all.filter { isSubmissionFile(it.path) }
        if (ours.isEmpty()) {
            val first = all.first()
            val basename = first.path.replace('\\', '/').substringAfterLast('/')
            return JumpChoice(null, JumpMiss.FOREIGN_FILE, "$basename:${first.line}")
        }
        val inRange = ours.filter { submittedLineCount == null || it.line <= submittedLineCount }
        if (inRange.isEmpty()) {
            val smallest = requireNotNull(ours.minByOrNull { it.line })
            return JumpChoice(null, JumpMiss.LINE_OUT_OF_RANGE, "${smallest.line}/$submittedLineCount")
        }
        return JumpChoice(inRange.firstOrNull { it.isError } ?: inRange.first(), JumpMiss.NONE, "")
    }

    /** 挑一条用来跳的；判据全见 [choose]，这里只取结果。 */
    @JvmStatic
    fun pickJumpTarget(text: String?, submittedLineCount: Int?, hasLocalSource: Boolean): CompileHit? =
        choose(text, submittedLineCount, hasLocalSource).hit

    /** 按钮为什么是灰的——每一道闸门各说一句，别让人去猜（尤其别说成「没有错误」）。 */
    @JvmStatic
    fun missText(choice: JumpChoice, pid: String): String = when (choice.miss) {
        JumpMiss.NONE -> ""
        JumpMiss.NO_LOCAL_FILE -> "项目根下没有 $pid.cpp（改过名或挪进子目录的话，插件不会去猜）"
        JumpMiss.NO_DIAGNOSTIC ->
            "这条记录里没有编译错误详情（1.7.2 之前没存这个字段，重新提交一次即可；洛谷偶尔也不返回编译器输出）"
        JumpMiss.NO_LOCATION -> "诊断里没解析出「文件:行:列: 错误:」这样的位置信息" +
            if (choice.detail.isEmpty()) "" else "（首行：${choice.detail}）"
        JumpMiss.FOREIGN_FILE -> "诊断指向的是 ${choice.detail}，不是你自己那份提交"
        JumpMiss.LINE_OUT_OF_RANGE -> "诊断的行号 ${choice.detail} 超出当次提交的代码长度（多半是宏展开/模板实例化）"
    }

    /** 按钮文案；没有可跳的目标时返回空串（调用方据此禁用）。 */
    @JvmStatic
    fun jumpLabel(hit: CompileHit?): String = hit?.let { "跳到第 ${it.line} 行" }.orEmpty()
}
