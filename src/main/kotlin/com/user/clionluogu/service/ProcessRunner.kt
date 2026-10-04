package com.user.clionluogu.service

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.progress.ProgressIndicator
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 跑一个本地可执行文件、喂 stdin、收 stdout/stderr —— **对拍与自测共用的进程内核**。
 *
 * 抽出来的理由只有一个：[SampleCompareService.runOne] 原来把「起进程 + 超时 + 取消 + 有界捕获」
 * 和「读期望输出 + 比对 + 生成差异上下文」揉在一个函数里，而自测只要前半段。
 * 复制那 60 行就是两份会各自腐烂的代码（1.7.x 那次「状态栏判对、详情区判反」就是两处重复同一条条件的下场）。
 *
 * 三条纪律原样保留，都是踩过的：
 * - stdin 用 [GeneralCommandLine.withInput] **文件重定向**，不走管道 ——
 *   省掉「写完要关」和「大输入把 64KB 管道塞死、父子互等」两类坑；
 * - `ParentEnvironmentType.CONSOLE` + 把编译器同目录注入 `PATH`：`NONE` 会连 SystemRoot 都丢掉，
 *   Windows 上 MinGW 的 DLL 就在编译器旁边，不注入必然 `0xC00007B`；
 * - 输出用 [BoundedCapture] 自己收，**不用 `CapturingProcessHandler`**（无界缓冲，
 *   一个死循环打印的程序能把 IDE 内存吃满）。
 *
 * 只在后台线程调用；不碰 Swing。
 */
object ProcessRunner {

    /**
     * 一次运行。[timeoutMs] 到点 `destroyProcess()`，[stopRequested] 被置起同样终止并标 `cancelled`。
     *
     * [meter] 非 null 时命令被包一层外部 `time`（测峰值内存用）。这时**必须递归杀进程树**：
     * 直接子进程是 `time`，只端掉它，底下那个跑飞的程序会变成孤儿继续吃满一核。
     */
    data class Run(
        val exe: File,
        val stdinFile: File,
        val workDir: File,
        val timeoutMs: Int,
        /** 追加进子进程 `PATH` 的目录（产物自己所在目录之外，还要编译器旁边那份）。 */
        val pathEntries: List<File> = emptyList(),
        val meter: ResourceMeter.Meter? = null,
    )

    /**
     * 运行结果。判定语义留给调用方：这里只报「跑成了什么样」，不报「对不对」。
     *
     * [cancelled] 与 [timedOut] 都意味着进程是被杀的，[exitCode] 在那两种情况下不可信；
     * [startError] 非 null 表示进程压根没起来（这时其余字段都是空/零）。
     * [peakMemoryBytes] 只有包了测量器才可能非 null；[programStderr] 是**剥掉测量器报表之后**的
     * stderr —— 报表和程序的 stderr 是同一条流，不剥就会把 `peak memory footprint` 当成程序输出。
     */
    data class Outcome(
        val stdout: String,
        val stderr: String,
        val exitCode: Int?,
        val elapsedMs: Long,
        val timedOut: Boolean,
        val overLimit: Boolean,
        val cancelled: Boolean,
        val startError: String?,
        val peakMemoryBytes: Long? = null,
        val programStderr: String? = null,
    )

    @JvmStatic
    fun run(run: Run, indicator: ProgressIndicator, stopRequested: AtomicBoolean): Outcome {
        val startedAt = System.currentTimeMillis()
        val commandLine = try {
            GeneralCommandLine(ResourceMeter.commandFor(run.meter, run.exe)).apply {
                withWorkDirectory(run.workDir.absolutePath)
                withCharset(StandardCharsets.UTF_8)
                // CONSOLE = 系统环境 + IDE 控制台目录；NONE 会连 PATH/SystemRoot 都丢掉，Windows 上必然起不来
                withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                withInput(run.stdinFile)
                withRedirectErrorStream(false)
                val injectDirs = (listOfNotNull(run.exe.parentFile) + run.pathEntries)
                    .filter { it.isDirectory }
                    .distinct()
                if (injectDirs.isNotEmpty()) {
                    val path = (injectDirs.map { it.absolutePath } + System.getenv("PATH").orEmpty())
                        .filter { it.isNotBlank() }
                        .joinToString(File.pathSeparator)
                    withEnvironment("PATH", path)
                }
            }
        } catch (e: Exception) {
            // 命令行本身构造失败（工作目录不存在之类）也算「没能启动」，别让它冒到任务外面
            return Outcome("", "", null, System.currentTimeMillis() - startedAt, false, false, false, e.message ?: e.javaClass.simpleName)
        }

        val handler = try {
            OSProcessHandler(commandLine)
        } catch (e: Exception) {
            return Outcome("", "", null, System.currentTimeMillis() - startedAt, false, false, false, e.message ?: e.javaClass.simpleName)
        }
        // 包了外部 time 之后，直接子进程是测量器：只端掉它会留下跑飞的孤儿，必须连树一起杀
        if (run.meter != null) handler.setShouldDestroyProcessRecursively(true)
        val capture = BoundedCapture(SampleDiff.MAX_READ_BYTES)
        capture.attach(handler)
        handler.addProcessListener(capture)
        handler.startNotify()

        var timedOut = false
        var cancelled = false
        val deadline = startedAt + run.timeoutMs
        while (!handler.isProcessTerminated) {
            if (stopRequested.get() || indicator.isCanceled) {
                cancelled = true
                handler.destroyProcess()
                break
            }
            if (System.currentTimeMillis() >= deadline) {
                timedOut = true
                handler.destroyProcess()
                break
            }
            handler.waitFor(100L)
        }
        // 给终止一点收尾时间，避免留下孤儿进程
        capture.awaitTerminated(2_000L)

        val elapsed = System.currentTimeMillis() - startedAt
        val rawStderr = capture.stderrText()
        return Outcome(
            stdout = capture.stdoutText(),
            // 不在这里 clipBytes：对拍页要 null 表示「没 stderr」，自测面板要的是原文，谁用谁裁
            stderr = rawStderr,
            exitCode = handler.exitCode ?: capture.exitCode,
            elapsedMs = elapsed,
            timedOut = timedOut,
            overLimit = capture.overLimit,
            cancelled = cancelled,
            startError = null,
            // 超限被截断时可能压根没写完报表 → 解析不出来就是 null，不编数字
            peakMemoryBytes = run.meter?.let { ResourceMeter.parsePeak(rawStderr, it.flavor) },
            programStderr = if (run.meter == null) rawStderr else ResourceMeter.stripReport(rawStderr),
        )
    }

    /**
     * Windows 上两类常见非零退出码的成因不同，文案必须分开，否则用户以为是自己代码错了。
     *
     * 从 [SampleCompareService] 原样搬过来（对拍页的 RE 说明和自测面板共用一份）。
     */
    @JvmStatic
    fun exitCodeHint(exitCode: Int): String = when (exitCode) {
        -1073741515 -> "退出码 $exitCode (0xC00007B)：缺少运行时 DLL（MinGW 的 libstdc++/libwinpthread 不在 PATH 上）"
        -1073741819 -> "退出码 $exitCode (0xC0000005)：访问违例（段错误一类）"
        139 -> "退出码 139：SIGSEGV"
        134 -> "退出码 134：SIGABRT（断言失败 / 堆破坏）"
        else -> "退出码 $exitCode"
    }
}
