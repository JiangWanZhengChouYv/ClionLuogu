package com.user.clionluogu.service

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.user.clionluogu.storage.SelfTestInputService
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地自测：**编一份现的，喂一段自己打的输入，把输出原样摊开**。
 *
 * 对拍页要求 `Pxxx_samples/` 里有成对的 `.in`/`.out`，没有就直接「啥也干不了」；
 * 而调 WA 的第一步往往就是「我给它造个输入看看它打印什么」。这里不比期望输出，只看结果。
 *
 * 三条刻意的选择：
 * - **每次先重编**：不给「用上次产物」的开关 —— 那要靠 mtime 猜产物新旧，正是被否掉的那类猜测。
 *   重编一次一两秒，换「看到的输出一定来自现在这份代码」。
 * - **stdin 不落盘到项目里**：写进系统临时目录，与产物同处；输入文本按题号存进
 *   [SelfTestInputService]（项目级 XML），所以既不新增目录名（删除口径不用改），
 *   也不会重启后一片空白。
 * - **进程部分完全复用 [ProcessRunner]**：文件重定向 stdin、有界捕获、超时与取消都在那边，
 *   两份循环必然两份腐烂。
 */
object SelfTestService {

    /** 与对拍同一档，不另设超时（同一份代码在两个地方超时值不同会很奇怪）。 */
    const val DEFAULT_TIMEOUT_MS = SampleCompareService.DEFAULT_TIMEOUT_MS

    /** 一次自测的输入。[buildDir] 是系统临时目录，产物与 stdin 都写在那儿。 */
    data class Request(
        val pid: String,
        val source: File,
        val compiler: CompilerService.Compiler,
        val stdin: String,
        val workDir: File,
        val buildDir: File,
        val args: List<String>,
        val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        /** 内存上限（MB），null = 不比。默认从题面来，面板上可以自己改。 */
        val memoryLimitMb: Int? = null,
        /** 峰值内存测量器；null = 这台机器量不到，[memoryLimitMb] 不生效（界面要明说）。 */
        val meter: ResourceMeter.Meter? = null,
    )

    /**
     * 一次自测的结果。[compile] 非 null 且 `!ok` 时 [run] 为 null（没产物可跑）；
     * [startError] 非 null 表示连 stdin 都没写出来（磁盘 / 权限）。
     */
    data class Report(
        val pid: String,
        val exePath: String,
        val compile: CompilerService.Outcome?,
        val run: ProcessRunner.Outcome?,
        val startError: String? = null,
    )

    /** 产物与 stdin 的落点。纯函数，面板与探针共用同一份命名。 */
    @JvmStatic
    fun paths(pid: String, buildDir: File): Pair<File, File> =
        Pair(File(buildDir, CompilerService.executableName(pid)), File(buildDir, "$pid.in"))

    /**
     * 一行运行结果（进状态行与详情区的「概览」）。
     *
     * 优先级和 [ProcessRunner.Outcome] 的语义一致：**超限 > 超时 > 取消 > 退出码**——
     * 超限与超时都是被杀的，但「输出爆量」才是他要看的原因。
     */
    /**
     * 一行运行结果（进状态行与详情区的「概览」）。
     *
     * 优先级与 [SampleCompareService.runOne] 完全一致：**超限 > 超时 > 取消 > 超内存 > 退出码**——
     * 前三种都是被杀的，但原因各不同；超内存排在退出码之前，因为超内存的程序常常本来就带一个难看的退出码，
     * 而「超内存」才是他要看的那句话。量不到峰值时 MLE 这一条整个跳过，绝不拿 0 当「没超」。
     */
    @JvmStatic
    @JvmOverloads
    fun summaryText(o: ProcessRunner.Outcome, timeoutMs: Int, memoryLimitMb: Int? = null): String {
        o.startError?.let { return "进程未能启动：$it" }
        if (o.overLimit) return "输出超过 ${SampleDiff.MAX_READ_BYTES} 字节即被终止（本地近似 OLE）"
        if (o.timedOut) return "超过 $timeoutMs ms 未结束，已终止（本地近似 TLE）"
        if (o.cancelled) return "已取消"
        val limitBytes = ResourceMeter.bytesOfMb(memoryLimitMb)
        val peak = o.peakMemoryBytes
        if (limitBytes != null && peak != null && peak > limitBytes) {
            val mb = ResourceMeter.mb(peak)
            return "峰值内存 ${mb ?: "?"} MB 超过上限 $memoryLimitMb MB（本地近似 MLE）"
        }
        val exit = o.exitCode
        if (exit == null) return "进程未正常退出（可能已被终止）"
        if (exit != 0) return "退出码非 0：${ProcessRunner.exitCodeHint(exit)}"
        return "正常结束 · 退出码 0 · 用时 ${o.elapsedMs} ms"
    }

    /** 空输出要写清楚，不能留一块白——留白看起来像「面板坏了」。 */
    @JvmStatic
    fun stdoutBlock(o: ProcessRunner.Outcome): String =
        if (o.stdout.isEmpty()) "（程序没有输出）" else o.stdout

    /**
     * 展示用的 stderr：**剥掉测量器报表**那几行（报表和程序 stderr 是同一条流）。
     * 空白返回 null，让调用方决定「不留一节空壳」。
     */
    @JvmStatic
    fun stderrBlock(o: ProcessRunner.Outcome): String? {
        val text = (o.programStderr ?: o.stderr).trim()
        return text.takeIf { it.isNotEmpty() }
    }

    /** 峰值内存那一行；这台机器量不到（或没跑成）就不写这一行。 */
    @JvmStatic
    fun peakMemoryText(o: ProcessRunner.Outcome): String? =
        ResourceMeter.mb(o.peakMemoryBytes)?.let { "$it MB" }

    /**
     * 在 **EDT** 调用：起后台任务，不阻塞。[onFinished] 在 EDT 回调。
     *
     * 调用方必须**先把编辑器里那份存掉**（[LuoguActions.saveIfModified]）——
     * 编译读的是磁盘，不保存就会拿旧内容编出一份不是他现在看到的代码。
     */
    fun run(project: Project, request: Request, onFinished: (Report) -> Unit): RunTask =
        RunTask(project, request, onFinished).also { it.queue() }

    /** 后台任务：写法与 [SampleCompareService.CompareTask] 对齐（可取消、幂等 report）。 */
    class RunTask internal constructor(
        project: Project,
        private val request: Request,
        private val onReport: (Report) -> Unit,
    ) : Task.Backgroundable(project, "洛谷自测 ${request.pid}", /* interactive = */ false) {

        private val stopRequested = AtomicBoolean(false)
        private val reported = AtomicBoolean(false)

        @Volatile
        private var compileOutcome: CompilerService.Outcome? = null

        @Volatile
        private var runOutcome: ProcessRunner.Outcome? = null

        @Volatile
        private var writeError: String? = null

        fun requestStop() {
            stopRequested.set(true)
        }

        override fun run(indicator: ProgressIndicator) {
            // 平台的 indicator 默认 indeterminate，此时给 fraction 赋值直接抛 IllegalStateException
            // （1.7.0 在真 IDE 里炸过，离线探针跑不出来）
            indicator.isIndeterminate = false
            val (exe, stdinFile) = paths(request.pid, request.buildDir)

            indicator.text = "写入自测输入…"
            indicator.fraction = 0.05
            // stdin 哪怕空也要写出文件：程序读 stdin 时立刻拿到 EOF，比「没有重定向」更接近评测机
            val written = runCatching { stdinFile.writeText(request.stdin) }
            if (written.isFailure) {
                writeError = written.exceptionOrNull()?.message ?: "写不进 ${stdinFile.path}"
                return
            }

            indicator.text = "编译 ${request.source.name}…"
            indicator.fraction = 0.2
            val outcome = CompilerService.compile(
                compilerExe = request.compiler.file,
                source = request.source,
                output = exe,
                extraArgs = request.args,
                stopRequested = stopRequested,
            )
            compileOutcome = outcome
            // 编译没过就绝对不跑：跑旧产物会给出完全错误的结论
            if (!outcome.ok) {
                indicator.fraction = 1.0
                return
            }

            indicator.text = "运行 ${request.pid}…"
            indicator.fraction = 0.5
            runOutcome = ProcessRunner.run(
                ProcessRunner.Run(
                    exe = exe,
                    stdinFile = stdinFile,
                    workDir = request.workDir,
                    timeoutMs = request.timeoutMs,
                    pathEntries = CompilerService.runtimePathEntries(request.compiler.file),
                    meter = request.meter,
                ),
                indicator,
                stopRequested,
            )
            indicator.fraction = 1.0
        }

        override fun onSuccess() = report()

        /** 兜底：`run` 抛异常或中途被取消时不走 [onSuccess]，少了这句面板会永远停在「运行中」。 */
        override fun onFinished() = report()

        private fun report() {
            if (!reported.compareAndSet(false, true)) return
            val (exe, _) = paths(request.pid, request.buildDir)
            onReport(Report(request.pid, exe.path, compileOutcome, runOutcome, writeError))
        }
    }

    /** 输入内容按题号存/取（面板用，边界处才碰 project 级服务）。清空就是删条目，不会留旧值。 */
    fun loadInput(project: Project, pid: String): String =
        SelfTestInputService.getInstance(project).load(pid)

    fun saveInput(project: Project, pid: String, text: String) {
        if (project.isDisposed) return
        SelfTestInputService.getInstance(project).save(pid, text)
    }
}
