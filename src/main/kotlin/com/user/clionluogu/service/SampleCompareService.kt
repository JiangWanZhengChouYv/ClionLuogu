package com.user.clionluogu.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.user.clionluogu.service.SampleSetService.Sample
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地样例对拍：把 `Pxxx_N.in` 喂给编译产物，与 `Pxxx_N.out` 逐行比对。
 *
 * **本文件禁止引入 CMake / CLion 专有 API**（它们以 bundled module 形式存在，
 * 一旦 `<depends>` 就把插件绑死在 CLion 上）。起进程那部分在 [ProcessRunner]（对拍与自测共用），
 * 这里只做「跑 + 比对」：产物由 [CompilerService] 现场编译出来，差异由 [SampleDiff] 算。
 *
 * 两个刻意的设计：
 * - stdin 走 [ProcessRunner] 的文件重定向，**不用管道** —— 省掉「写完要关」和
 *   「大输入把 64KB 管道塞死、父子互等」两类坑；
 * - 输出由 [ProcessRunner] 用 [BoundedCapture] 收，**不用 `CapturingProcessHandler`**：那是无界缓冲，
 *   一个死循环打印的本地程序能把 IDE 内存吃爆。超限即刻 `destroyProcess()`。
 */
object SampleCompareService {

    /** 每组样例的固定时间上限（毫秒）。 */
    const val DEFAULT_TIMEOUT_MS = 5_000

    /** 单组判定结果。 */
    enum class Verdict { PASS, MISMATCH, RUNTIME_ERROR, TIMEOUT, MEMORY_LIMIT, OUTPUT_TOO_MUCH, NOT_RUN, ERROR }

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
        /** 内存上限（MB）；null = 不比内存。超了判 [Verdict.MEMORY_LIMIT]。 */
        val memoryLimitMb: Int? = null,
        /** 峰值内存测量器；null = 这台机器量不到，[memoryLimitMb] 也就不生效（界面要明说）。 */
        val meter: ResourceMeter.Meter? = null,
    )

    /** 跑之前先编译：`<compiler> <args> -o <Request.exe> <source>`。 */
    data class Compile(val compiler: File, val source: File, val args: List<String>)

    /**
     * 单组结果。[expectedPreview] / [actualPreview] 只在失败时非空（首个差异 + 局部上下文）；
     * [actualOutput] 是完整 stdout，同样只在失败时留 —— 「存反例」要用它，通过的组不留，免得几 MB 白占内存。
     * [peakMemoryMb] 只有这台机器量得到峰值内存才非空。
     */
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
        val actualOutput: String? = null,
        val peakMemoryMb: Int? = null,
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
            // 平台的 indicator 默认是 indeterminate，此时 setFraction 会直接抛 IllegalStateException
            // （真 IDE 里第一次跑就死在这，离线探针跑不出来）——要报进度就得先关掉不确定态。
            indicator.isIndeterminate = false
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

    /** 跑单组样例：进程部分交给 [ProcessRunner]，这里只做「比对 + 生成差异上下文」。 */
    fun runOne(
        request: Request,
        sample: Sample,
        indicator: ProgressIndicator,
        stopRequested: AtomicBoolean,
    ): Result {
        val outcome = ProcessRunner.run(
            ProcessRunner.Run(
                exe = request.exe,
                stdinFile = sample.inFile,
                workDir = request.workDir,
                timeoutMs = request.timeoutMs,
                pathEntries = request.pathEntries,
                meter = request.meter,
            ),
            indicator,
            stopRequested,
        )
        val elapsed = outcome.elapsedMs
        val stdout = outcome.stdout
        // 有测量器时报表和程序的 stderr 是同一条流，展示必须用剥过报表的那份，
        // 否则「stderr」那一节里会夹着 peak memory footprint 这种插件内部的东西
        val stderr = SampleDiff.clipBytes(outcome.programStderr ?: outcome.stderr).ifBlank { null }
        val peakMb = ResourceMeter.mb(outcome.peakMemoryBytes)

        fun result(
            verdict: Verdict,
            issue: SampleDiff.Issue? = null,
            note: String? = null,
            expectedPreview: String? = null,
            actualPreview: String? = null,
        ) = Result(
            sample = sample,
            verdict = verdict,
            elapsedMs = elapsed,
            exitCode = outcome.exitCode,
            stderr = stderr,
            issue = issue,
            expectedPreview = expectedPreview,
            actualPreview = actualPreview,
            note = note,
            actualOutput = if (verdict == Verdict.PASS) null else stdout.takeIf { it.isNotEmpty() },
            peakMemoryMb = peakMb,
        )

        outcome.startError?.let {
            return result(Verdict.ERROR, note = "进程未能启动：$it")
        }
        if (outcome.cancelled) {
            return result(Verdict.NOT_RUN, note = "已取消")
        }
        // 超限优先于超时：BoundedCapture 一超限就 destroyProcess，那种情况下 timedOut 也可能是真，
        // 但「输出爆量」才是用户要看的原因（判成 OLE 而不是 TLE）。
        if (outcome.overLimit) {
            return result(
                Verdict.OUTPUT_TOO_MUCH,
                note = "输出超过 ${SampleDiff.MAX_READ_BYTES} 字节即被终止（本地近似 OLE）",
            )
        }
        if (outcome.timedOut) {
            return result(Verdict.TIMEOUT, note = "超过 ${request.timeoutMs} ms 未结束，已终止 (TLE?)")
        }
        // 内存排在退出码之前：超内存的程序常常是被系统/OOM 杀掉的，那时退出码也难看，
        // 但「超内存」才是原因（量不到峰值时这一条整个跳过，绝不拿 0 当「没超」）
        val limitBytes = ResourceMeter.bytesOfMb(request.memoryLimitMb)
        if (limitBytes != null && outcome.peakMemoryBytes != null && outcome.peakMemoryBytes > limitBytes) {
            return result(
                Verdict.MEMORY_LIMIT,
                note = "峰值内存 ${peakMb ?: "?"} MB 超过上限 ${request.memoryLimitMb} MB（本地近似 MLE）",
            )
        }
        val exitCode = outcome.exitCode
        if (exitCode == null) {
            return result(Verdict.ERROR, note = "进程未正常退出（可能已被终止）")
        }
        if (exitCode != 0) {
            return result(Verdict.RUNTIME_ERROR, note = ProcessRunner.exitCodeHint(exitCode))
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
}
