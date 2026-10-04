package com.user.clionluogu.service

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.application.PathManager
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地编译：找编译器，把项目根的 `Pxxx.cpp` 编成可执行文件供对拍运行。
 *
 * 为什么插件自己编，而不去找 CLion 的产物：产物名取决于 target 名，而且要求他**先手动 Build**，
 * 真实项目里经常找不到。直接调 `clang++`/`g++` 只用 platform 核心的进程 API，
 * **不碰 CMake / CLion 工具链 API**（那些是 bundled module，`<depends>` 一写插件就被绑死在 CLion 上），
 * 代价是不复刻项目的编译选项 —— 这条写进 README 的已知限制。
 *
 * 所有方法都在**后台线程**调用（会起子进程）。
 */
object CompilerService {

    /** 编译时间上限：标准库头 + `-O2` 在大文件上也得几秒，给足余量。 */
    const val COMPILE_TIMEOUT_MS = 120_000

    /** 诊断文本上限：模板错误能刷出几 MB，超了 [BoundedCapture] 直接杀进程。 */
    const val MAX_DIAGNOSTIC_BYTES = 512 * 1024

    /** PATH 上的候选：clang++ 放最前（macOS 上 g++ 也只是 clang 的马甲，顺序不影响结果）。 */
    private val COMPILER_NAMES = listOf("clang++", "g++", "c++", "clang")

    /** IDE 发行包里自带的 MinGW —— Windows 用户零配置就能用的那一个。 */
    private val BUNDLED_RELATIVE = listOf(
        "bin/mingw/bin/g++.exe",
        "bin/mingw64/bin/g++.exe",
        "bin/mingw/bin/gcc.exe",
    )

    /**
     * 编译器是谁：**只看 `--version` 的首行，不看文件名**。
     *
     * 文件名在这台 mac 上真的会骗：`/usr/bin/g++` 打印出来是
     * `Apple clang version 21.0.0 (clang-2100.3.34.2)` —— 按名字认就把 clang 当成了 GCC，
     * 于是拉题塞 `<bits/stdc++.h>`，本地第一行就 fatal error。
     * 反方向同理：brew 的 GCC 叫 `g++-16 (Homebrew GCC 16.2.0) 16.2.0`，带版本号、没有裸 `g++`。
     */
    enum class Flavor { GCC, CLANG, UNKNOWN }

    /** 这份编译器是哪来的，给用户看的那句话得区分（「CLion 选的那个」≠「PATH 上随便抓的」）。 */
    enum class Origin { SETTINGS, CLION, PATH, NONE }

    /** 一个可用的编译器。[versionLine] 为 null 表示 `--version` 没跑通（仍然可以试着编译）。 */
    data class Compiler(val file: File, val versionLine: String?) {
        /** 判出来的身份；探测失败是 [Flavor.UNKNOWN]，那时**什么都不该猜**（模板退回标准头）。 */
        val flavor: Flavor get() = flavorOf(versionLine)

        /** 侧边栏那一行的短文案：优先版本首行的简短部分。 */
        fun display(): String {
            val v = versionLine?.takeIf { it.isNotBlank() }
            return if (v == null) file.name else "${file.name}（${shorten(v)}）"
        }

        private fun shorten(line: String): String =
            if (line.length <= 60) line else line.take(60) + "…"
    }

    /** 探测结果：要能区分「设置的覆盖路径不可用」和「这台机器真没编译器」。 */
    data class Detected(
        val compiler: Compiler?,
        val overrideIgnored: String?,
        val origin: Origin = Origin.NONE,
    )

    /** 编译器身份：先认 clang（Apple 那行不含 gcc），再认 GCC，都不认就是未知。 */
    @JvmStatic
    fun flavorOf(versionLine: String?): Flavor {
        val line = versionLine?.trim().orEmpty().lowercase()
        if (line.isEmpty()) return Flavor.UNKNOWN
        if (line.contains("clang")) return Flavor.CLANG
        if (line.contains("gcc") || line.contains("g++")) return Flavor.GCC
        return Flavor.UNKNOWN
    }

    /** 这台机器是 macOS？（只有 mac 才有「系统自带的是 clang、GCC 得自己装」这件事。） */
    @JvmStatic
    fun isMac(): Boolean = System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true)

    /**
     * 该不该提醒装 GCC：**mac 且现在用的不是 GCC**。
     *
     * [Flavor.UNKNOWN]（`--version` 没跑通）也算「不是 GCC」—— mac 上探测失败的基本就是系统那套 clang，
     * 而这条只是建议、不拦路，宁可多提一次也别在这种机器上默认塞 bits。
     * 非 mac（Linux、Windows 的 MinGW）不问：那边评测机同款的 GCC 本来就在。
     */
    @JvmStatic
    fun shouldRecommendGcc(isMac: Boolean, flavor: Flavor): Boolean = isMac && flavor != Flavor.GCC

    /**
     * 提醒的正文，一处写完（通知与面板 tooltip 共用，改文案不会两边不一致）。
     *
     * 「装完是带版本号的名字、没有裸 `g++`」这句必须写：不然他装完了插件仍旧只能找到那个 clang 马甲，
     * 回头只会说「装了没用」。
     */
    @JvmStatic
    fun gccAdvice(compiler: Compiler?): String {
        val who = compiler?.let { "${it.file.path}（${it.display()}）" } ?: "还没找到可用的编译器"
        return "这台 Mac 上现在用的是 $who，不是 GCC。\n\n" +
            "洛谷评测机是 GCC：clang 没有 <bits/stdc++.h>，标准库行为也与 GCC 有差别，" +
            "「本地过了」不等于「评测机过」。\n\n" +
            "装 GNU 编译器：brew install gcc\n" +
            "装完的名字带版本号（例如 /opt/homebrew/bin/g++-16，brew 不给裸 g++），两条路任选：\n" +
            "1. 让 CLion 用它：CMake 配置里加 -DCMAKE_CXX_COMPILER=/opt/homebrew/bin/g++-16，" +
            "再重新配置一次项目（对拍与自测跟着 CLion 走）；\n" +
            "2. 只在插件里用：设置「洛谷拉题 → 编译器」填上面那个绝对路径。\n\n" +
            "不装也能用：拉题的头文件按编译器给（clang 给真实标准头，GCC 给 bits）。"
    }

    /**
     * 最近一次探测出的身份，供**不能在后台等**的调用方读（拉题时决定塞哪套头文件）。
     *
     * 只有 [detect] 写它，而 [detect] 起子进程，所以只在后台线程调；拉题那条路在 EDT 上，
     * 读这个 volatile 字段就够了。没探测过时是 [Flavor.UNKNOWN]，模板于是退回标准头 ——
     * **这个方向错了也能编过**（标准头在 GCC 上照样能用），反过来（clang 上塞 bits）必炸。
     */
    @Volatile
    var lastFlavor: Flavor = Flavor.UNKNOWN
        private set

    /** 一次编译的结果。[diagnostic] 是合并了 stdout/stderr 的编译器原文。 */
    data class Outcome(
        val ok: Boolean,
        val exitCode: Int?,
        val diagnostic: String,
        val elapsedMs: Long,
        val commandLine: String,
        val timedOut: Boolean,
        val overLimit: Boolean,
    )

    private val versionCache = ConcurrentHashMap<String, String>()

    /**
     * 找编译器，优先级按**他实际在用的那一个**排：
     * 1. [overridePath]（插件设置里填的绝对路径）—— 显式选择最大；
     * 2. [clionPath]（[ClionToolchain] 从 `CMakeCache.txt` 读的 CLion 选的编译器）——
     *    对拍与自测要跟他手写的构建一致，不然「IDE 里过、插件里炸」；
     * 3. PATH 上的 clang++/g++/c++/clang 与 IDE 自带 MinGW —— 项目还没配置过 CMake 时的兜底。
     *
     * 覆盖路径不可用时会回落，并在 [Detected.overrideIgnored] 里给出该显示给用户的一句话。
     */
    fun detect(overridePath: String?, clionPath: String? = null): Detected {
        val override = overridePath?.trim().orEmpty()
        if (override.isNotEmpty()) {
            val file = File(override)
            if (isExecutable(file)) return finish(Compiler(file, versionOf(file)), Origin.SETTINGS)
        }
        // 「设置里填的那个不可用」这句话在**任何**回落路上都要说：只换成 CLion 的那一个而不说，
        // 他看到的就是「我填了没用、也没人告诉我为什么」。
        val why = if (override.isEmpty()) null else "设置里的编译器不可用：$override"

        val ide = clionPath?.trim().orEmpty().takeIf { it.isNotEmpty() }?.let(::File)
        if (ide != null && isExecutable(ide)) {
            return finish(toCompiler(ide), Origin.CLION, why?.let { "$it，已改用 CLion 项目实际用的那一个" })
        }

        val auto = autoDetect()
        if (auto == null) {
            return Detected(null, why?.let { "$it（CLion 那边没有可用的编译器，PATH 上也还没找到）" }, Origin.NONE)
        }
        return finish(auto, Origin.PATH, why?.let { "$it，已回落到 ${auto.file.name}" })
    }

    /** 统一在这里记账：[lastFlavor] 是拉题模板唯一会在 EDT 上读的字段。 */
    private fun finish(compiler: Compiler, origin: Origin, notice: String? = null): Detected {
        lastFlavor = compiler.flavor
        return Detected(compiler, notice, origin)
    }

    private fun autoDetect(): Compiler? {
        val candidates = mutableListOf<File>()
        System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .forEach { dir ->
                COMPILER_NAMES.forEach { name ->
                    candidates.add(File(dir, name))
                    if (isWindows()) candidates.add(File(dir, "$name.exe"))
                }
            }
        val home = runCatching { PathManager.getHomePath() }.getOrNull()
        if (home != null) BUNDLED_RELATIVE.forEach { candidates.add(File(home, it)) }

        val hit = candidates.firstOrNull { isExecutable(it) } ?: return null
        return toCompiler(hit)
    }

    private fun toCompiler(file: File): Compiler = Compiler(file, versionOf(file))

    /** `--version` 首行；按绝对路径缓存（同一路径反复探测不值钱的事也别重复做）。 */
    private fun versionOf(file: File): String? {
        val cached = versionCache.getOrPut(file.absolutePath) {
            runProcess(
                command(file, listOf("--version")),
                timeoutMs = 8_000,
                limitBytes = 64 * 1024,
                stopRequested = null,
            ).let { run ->
                run.output.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            }
        }
        return cached.takeIf { it.isNotBlank() }
    }

    /**
     * 编译 [source] 到 [output]。[extraArgs] 一般来自设置（`-std=c++17 -O2 -w`）。
     *
     * 先删掉旧产物：万一上一次编译失败留下一个旧二进制，接着跑它就是在**用旧代码出结论**。
     */
    fun compile(
        compilerExe: File,
        source: File,
        output: File,
        extraArgs: List<String>,
        stopRequested: AtomicBoolean?,
    ): Outcome {
        val args = mutableListOf<String>()
        args.addAll(extraArgs)
        args.add("-o")
        args.add(output.absolutePath)
        args.add(source.absolutePath)

        runCatching { if (output.exists()) output.delete() }
        val startedAt = System.currentTimeMillis()
        val commandLine = command(compilerExe, args, workDir = output.parentFile)
        val result = try {
            runProcess(commandLine, COMPILE_TIMEOUT_MS, MAX_DIAGNOSTIC_BYTES, stopRequested)
        } catch (t: Throwable) {
            return Outcome(
                ok = false,
                exitCode = null,
                diagnostic = "编译器没能启动：${t.message ?: t.javaClass.simpleName}\n命令：${commandLine.commandLineString}",
                elapsedMs = System.currentTimeMillis() - startedAt,
                commandLine = commandLine.commandLineString,
                timedOut = false,
                overLimit = false,
            )
        }
        val produced = output.isFile
        val diagnostic = when {
            result.overLimit -> SampleDiff.clipBytes(result.output) +
                "\n… 诊断超过 $MAX_DIAGNOSTIC_BYTES 字节已被截断（进程被终止）"
            result.timedOut -> result.output + "\n超过 $COMPILE_TIMEOUT_MS ms 未编完，已终止"
            else -> result.output.ifBlank { if (result.exitCode == 0) "" else "编译失败，但没有任何输出" }
        }
        return Outcome(
            ok = result.exitCode == 0 && produced && !result.timedOut && !result.overLimit,
            exitCode = result.exitCode,
            diagnostic = SampleDiff.clipBytes(withBitsHint(diagnostic), MAX_DIAGNOSTIC_BYTES / 16),
            elapsedMs = System.currentTimeMillis() - startedAt,
            commandLine = commandLine.commandLineString,
            timedOut = result.timedOut,
            overLimit = result.overLimit,
        )
    }

    /**
     * 老文件里那句 `#include <bits/stdc++.h>` 在 libc++（macOS 的 clang）上必然失败。
     * 插件不再塞兼容头，所以这种情况要当场说清怎么改，否则诊断只有一行 file not found、很没指向性。
     */
    private fun withBitsHint(text: String): String =
        if (!text.contains("bits/stdc++.h")) text else text +
            "\n\n提示：这台机器的编译器没有 bits/stdc++.h（那是 GNU/libstdc++ 的东西）。" +
            "\n把那一行换成真实存在的标准头就行，例如 <iostream> <vector> <algorithm>，用到别的再补；" +
            "\n新拉的题目模板只有这三个头。"

    /** 产物运行时需要额外挂到 `PATH` 的目录：编译器所在目录（Windows 的 MinGW DLL 就在旁边）。 */
    fun runtimePathEntries(compilerExe: File): List<File> = listOfNotNull(compilerExe.parentFile)

    /**
     * 产物文件名：Windows 必须带 `.exe`，其余原样。
     *
     * **对拍与自测共用这一条规则**（两边各写一份的话，产物名会算出两个不同的文件，
     * 「刚编的那份」和「正要跑的那份」就对不上了）。
     */
    @JvmStatic
    fun executableName(pid: String): String = if (isWindows()) "$pid.exe" else pid

    private fun command(
        exe: File,
        args: List<String>,
        workDir: File? = null,
    ): GeneralCommandLine = GeneralCommandLine(exe.absolutePath).apply {
        withParameters(args)
        workDir?.let { withWorkDirectory(it.absolutePath) }
        withCharset(StandardCharsets.UTF_8)
        // CONSOLE = 系统环境 + IDE 控制台目录；NONE 会连 PATH/SystemRoot 都丢掉，Windows 上必然起不来
        withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        // 编译诊断本来就在 stderr，合并成一份给用户看，不用区分先后
        withRedirectErrorStream(true)
    }

    private data class ProcessResult(
        val exitCode: Int?,
        val output: String,
        val timedOut: Boolean,
        val overLimit: Boolean,
    )

    /** 起进程、有界收输出、到点或被要求停止就杀。只在后台线程调用。 */
    private fun runProcess(
        commandLine: GeneralCommandLine,
        timeoutMs: Int,
        limitBytes: Int,
        stopRequested: AtomicBoolean?,
    ): ProcessResult {
        val handler = OSProcessHandler(commandLine)
        val capture = BoundedCapture(limitBytes)
        capture.attach(handler)
        handler.addProcessListener(capture)
        handler.startNotify()

        val deadline = System.currentTimeMillis() + timeoutMs
        var timedOut = false
        while (!handler.isProcessTerminated) {
            if (stopRequested?.get() == true) {
                handler.destroyProcess()
                capture.awaitTerminated(2_000L)
                return ProcessResult(handler.exitCode ?: capture.exitCode, capture.combinedText(), false, capture.overLimit)
            }
            if (System.currentTimeMillis() >= deadline) {
                timedOut = true
                handler.destroyProcess()
                break
            }
            handler.waitFor(100L)
        }
        capture.awaitTerminated(2_000L)
        return ProcessResult(handler.exitCode ?: capture.exitCode, capture.combinedText(), timedOut, capture.overLimit)
    }

    private fun isExecutable(f: File): Boolean =
        runCatching { f.isFile && f.canExecute() }.getOrDefault(false)

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)
}
