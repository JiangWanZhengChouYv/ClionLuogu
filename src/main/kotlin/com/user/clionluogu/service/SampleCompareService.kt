package com.user.clionluogu.service

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.user.clionluogu.service.SampleSetService.Sample
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地样例对拍：把 `Pxxx_N.in` 喂给编译产物，与 `Pxxx_N.out` 逐行比对。
 *
 * **本文件禁止引入 CMake / CLion 专有 API**（它们以 bundled module 形式存在，
 * 一旦 `<depends>` 就把插件绑死在 CLion 上）。这里只用 platform 核心的
 * `GeneralCommandLine` + `OSProcessHandler`；要跑的产物由 [CompilerService] 现场编译出来。
 *
 * 两个刻意的设计：
 * - stdin 用 [GeneralCommandLine.withInput] 直接把 `.in` 文件重定向进去，
 *   不走管道 —— 省掉「写完要关」和「大输入把 64KB 管道塞死、父子互等」两类坑；
 * - 输出用 [BoundedCapture] 自己收，**不用 `CapturingProcessHandler`**：它是无界缓冲，
 *   一个死循环打印的本地程序能把 IDE 内存吃爆。超限即刻 `destroyProcess()`。
 */
object SampleCompareService {

    /** 每组样例的固定时间上限（毫秒）。 */
    const val DEFAULT_TIMEOUT_MS = 5_000

    /** 单组判定结果。 */
    enum class Verdict { PASS, MISMATCH, RUNTIME_ERROR, TIMEOUT, OUTPUT_TOO_MUCH, NOT_RUN, ERROR }

    /** 一次对拍的输入。 */
    data class Request(
        val pid: String,
        val exe: File,
        val workDir: File,
        val samples: List<Sample>,
        val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        /** 额外注入子进程 `PATH` 的目录：产物运行时要找的 DLL（MinGW 的 bin 就在编译器旁边）。 */
        val pathEntries: List<File> = emptyList(),
        /** 非空表示**先编译**（对拍页的正常路径），产物写到 [exe]。 */
        val compile: Compile? = null,
    )

    /** 跑之前先编译：`<compiler> <args> -o <Request.exe> <source>`。 */
    data class Compile(val compiler: File, val source: File, val args: List<String>)

    /** 单组结果。[expectedPreview] / [actualPreview] 只在失败时非空（首个差异 + 局部上下文）。 */
    data class Result(
        val sample: Sample,
        val verdict: Verdict,
        val elapsedMs: Long,
        val exitCode: Int? = null,
        val stderr: String? = null,
        val issue: SampleDiff.Issue? = null,
        val expectedPreview: String? = null,
        val actualPreview: String? = null,
        val note: String? = null,
    )

    /** 一轮对拍的汇总。[compile] 非空表示这一轮是先编译再跑的（失败时 [results] 为空）。 */
    data class Report(
        val pid: String,
        val exePath: String,
        val results: List<Result>,
        val compile: CompilerService.Outcome?,
    ) {
        val passedCount: Int get() = results.count { it.verdict == Verdict.PASS }
        val ranCount: Int get() = results.count { it.verdict != Verdict.NOT_RUN }
        val allPassed: Boolean get() = results.isNotEmpty() && passedCount == results.size
    }

    /**
     * 在 **EDT** 调用：只负责把后台任务挂上去，不阻塞。
     * [onProgress] 与 [onFinished] 都会在 EDT 回调。
     */
    fun run(
        project: Project,
        request: Request,
        onProgress: (Result) -> Unit,
        onFinished: (Report) -> Unit,
    ): CompareTask = CompareTask(project, request, onProgress, onFinished).also {
        it.queue()
    }

    /**
     * 一次对拍的后台任务。用 [Task.Backgroundable] 而不是裸 `executeOnPooledThread`，
     * 因为要能**取消**（裸线程被中断也杀不掉子进程，会留下吃满一核的孤儿）、要有进度、
     * 并且回调由平台保证在 EDT 派发。
     */
    class CompareTask internal constructor(
        project: Project,
        private val request: Request,
        private val onProgress: (Result) -> Unit,
        private val onReport: (Report) -> Unit,
    ) : Task.Backgroundable(project, "洛谷样例对拍 ${request.pid}", /* interactive = */ false) {

        private val stopRequested = AtomicBoolean(false)
        private val reported = AtomicBoolean(false)
        private val results: MutableList<Result> = Collections.synchronizedList(mutableListOf())

        @Volatile
        private var compileOutcome: CompilerService.Outcome? = null

        /** 面板的「停止」按钮调用；真正的 kill 发生在后台线程的等待循环里。 */
        fun requestStop() {
            stopRequested.set(true)
        }

        override fun run(indicator: ProgressIndicator) {
            val samples = request.samples
            val compile = request.compile
            if (compile != null) {
                indicator.text = "编译 ${compile.source.name}…"
                indicator.fraction = 0.05
                val outcome = CompilerService.compile(
                    compilerExe = compile.compiler,
                    source = compile.source,
                    output = request.exe,
                    extraArgs = compile.args,
                    stopRequested = stopRequested,
                )
                compileOutcome = outcome
                // 编译没过就一组都别跑：跑旧产物会给出完全错误的结论
                if (!outcome.ok) {
                    indicator.fraction = 1.0
                    return
                }
            }
            samples.forEachIndexed { index, sample ->
                if (stopRequested.get() || indicator.isCanceled) return
                indicator.text = "对拍 ${request.pid} · 第 ${index + 1}/${samples.size} 组"
                indicator.fraction = index.toDouble() / samples.size.coerceAtLeast(1)
                val result = runOne(request, sample, indicator, stopRequested)
                results.add(result)
                ApplicationManager.getApplication().invokeLater { onProgress(result) }
            }
            indicator.fraction = 1.0
        }

        override fun onSuccess() = report()

        /**
         * 兜底：`run` 抛异常或中途被取消时不会走 [onSuccess]，少了这一句面板会永远停在
         * 「对拍中」（开始按钮禁用、停止按钮也已禁用）。[report] 自身幂等。
         */
        override fun onFinished() = report()

        private fun report() {
            if (!reported.compareAndSet(false, true)) return
            onReport(Report(request.pid, request.exe.path, results.toList(), compileOutcome))
        }
    }

    /** 跑单组样例。只在后台线程调用。 */
    fun runOne(
        request: Request,
        sample: Sample,
        indicator: ProgressIndicator,
        stopRequested: AtomicBoolean,
    ): Result {
        val startedAt = System.currentTimeMillis()
        val commandLine = GeneralCommandLine(request.exe.absolutePath).apply {
            withWorkDirectory(request.workDir.absolutePath)
            withCharset(StandardCharsets.UTF_8)
            // CONSOLE = 系统环境 + IDE 控制台目录；NONE 会连 PATH/SystemRoot 都丢掉，Windows 上必然起不来
            withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            withInput(sample.inFile)
            withRedirectErrorStream(false)
            val injectDirs = (listOfNotNull(request.exe.parentFile) + request.pathEntries)
                .filter { it.isDirectory }
                .distinct()
            if (injectDirs.isNotEmpty()) {
                val path = (injectDirs.map { it.absolutePath } + System.getenv("PATH").orEmpty())
                    .filter { it.isNotBlank() }
                    .joinToString(File.pathSeparator)
                withEnvironment("PATH", path)
            }
        }

        val handler = try {
            OSProcessHandler(commandLine)
        } catch (e: Exception) {
            return Result(sample, Verdict.ERROR, System.currentTimeMillis() - startedAt, note = "进程未能启动：${e.message}")
        }
        val capture = BoundedCapture(SampleDiff.MAX_READ_BYTES)
        capture.attach(handler)
        handler.addProcessListener(capture)
        handler.startNotify()

        var timedOut = false
        val deadline = startedAt + request.timeoutMs
        while (!handler.isProcessTerminated) {
            if (stopRequested.get() || indicator.isCanceled) {
                handler.destroyProcess()
                return Result(sample, Verdict.NOT_RUN, System.currentTimeMillis() - startedAt, note = "已取消")
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
        val exitCode = handler.exitCode ?: capture.exitCode
        val stdout = capture.stdoutText()
        val stderr = SampleDiff.clipBytes(capture.stderrText()).ifBlank { null }

        fun result(
            verdict: Verdict,
            issue: SampleDiff.Issue? = null,
            note: String? = null,
            expectedPreview: String? = null,
            actualPreview: String? = null,
        ) = Result(sample, verdict, elapsed, exitCode, stderr, issue, expectedPreview, actualPreview, note)

        if (capture.overLimit) {
            return result(
                Verdict.OUTPUT_TOO_MUCH,
                note = "输出超过 ${SampleDiff.MAX_READ_BYTES} 字节即被终止（本地近似 OLE）",
            )
        }
        if (timedOut) {
            return result(Verdict.TIMEOUT, note = "超过 ${request.timeoutMs} ms 未结束，已终止 (TLE?)")
        }
        if (exitCode == null) {
            return result(Verdict.ERROR, note = "进程未正常退出（可能已被终止）")
        }
        if (exitCode != 0) {
            return result(Verdict.RUNTIME_ERROR, note = exitCodeHint(exitCode))
        }

        val expectedText = SampleDiff.readCapped(sample.expectedFile)
            ?: return result(Verdict.ERROR, note = "期望输出超过 ${SampleDiff.MAX_READ_BYTES} 字节，跳过比对")
        val expectedLines = SampleDiff.normalize(expectedText)
        val actualLines = SampleDiff.normalize(stdout)
        val issue = SampleDiff.firstDifference(expectedLines, actualLines) ?: return result(Verdict.PASS)

        return result(
            Verdict.MISMATCH,
            issue = issue,
            expectedPreview = SampleDiff.contextBlock(
                "期望 ${sample.expectedFile.name}", expectedLines, issue.line, "共 ${expectedLines.size} 行",
            ),
            actualPreview = SampleDiff.contextBlock(
                "实际 stdout", actualLines, issue.line, "共 ${actualLines.size} 行",
            ),
        )
    }

    /** Windows 上两类常见非零退出码的成因不同，文案必须分开，否则用户以为是自己代码错了。 */
    private fun exitCodeHint(exitCode: Int): String = when (exitCode) {
        -1073741515 -> "退出码 $exitCode (0xC00007B)：缺少运行时 DLL（MinGW 的 libstdc++/libwinpthread 不在 PATH 上）"
        -1073741819 -> "退出码 $exitCode (0xC0000005)：访问违例（段错误一类）"
        139 -> "退出码 139：SIGSEGV"
        134 -> "退出码 134：SIGABRT（断言失败 / 堆破坏）"
        else -> "退出码 $exitCode"
    }
}
