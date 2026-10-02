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

    /** 一个可用的编译器。[versionLine] 为 null 表示 `--version` 没跑通（仍然可以试着编译）。 */
    data class Compiler(val file: File, val versionLine: String?) {
        /** 侧边栏那一行的短文案：优先版本首行的简短部分。 */
        fun display(): String {
            val v = versionLine?.takeIf { it.isNotBlank() }
            return if (v == null) file.name else "${file.name}（${shorten(v)}）"
        }

        private fun shorten(line: String): String =
            if (line.length <= 60) line else line.take(60) + "…"
    }

    /** 探测结果：要能区分「设置的覆盖路径不可用」和「这台机器真没编译器」。 */
    data class Detected(val compiler: Compiler?, val overrideIgnored: String?)

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
     * 找编译器：[overridePath]（设置页填的绝对路径）可用就用它，否则按 PATH + IDE 自带 MinGW 找。
     * 覆盖路径不可用时会回落并在 [Detected.overrideIgnored] 里给出该显示给用户的一句话。
     */
    fun detect(overridePath: String?): Detected {
        val override = overridePath?.trim().orEmpty()
        if (override.isEmpty()) return Detected(autoDetect(), null)

        val file = File(override)
        if (isExecutable(file)) return Detected(toCompiler(file), null)

        val fallback = autoDetect()
        val message = if (fallback == null) {
            "设置里的编译器不可用：$override（本机也没自动找到别的）"
        } else {
            "设置里的编译器不可用（$override），已回落到 ${fallback.file.name}"
        }
        return Detected(fallback, message)
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
            "\n把那一行换成真实存在的标准头就行，例如 <iostream> <vector> <algorithm> <string> <cmath> <queue> <map> <set>；" +
            "\n新拉的题目模板已经改成这种写法了。"

    /** 产物运行时需要额外挂到 `PATH` 的目录：编译器所在目录（Windows 的 MinGW DLL 就在旁边）。 */
    fun runtimePathEntries(compilerExe: File): List<File> = listOfNotNull(compilerExe.parentFile)

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
