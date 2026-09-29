package com.user.clionluogu.service

import com.user.clionluogu.api.LuoguApiService
import com.user.clionluogu.api.SubmissionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 评测记录轮询调度。以 IO 协程后台轮询 `LuoguApiService.getSubmissionStatus(rid)`，
 * 状态变化回调 [startPolling.onUpdate]，到达终态或连续失败或取消时回调
 * [startPolling.onDone] / [startPolling.onError]。
 *
 * 所有回调均在 IO 线程执行，UI 侧需要自行切换到 EDT（本服务不依赖 IntelliJ 线程模型）。
 */
object JudgePollingService {

    /** 轮询句柄，可取消；调用方可持有并用于停止轮询。 */
    interface Cancellable {
        fun cancel()
        val isCancelled: Boolean
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 轮询间隔。 */
    private const val POLL_INTERVAL_MS = 1500L

    /** 单次失败连续重试上限，超过则视为失败终止。 */
    private const val MAX_CONSECUTIVE_FAILURES = 5

    /**
     * 终态状态码（已出评测结果，不再变化）。依据 vscode-luogu 的 `RecordStatus`：
     * 除 0（Waiting）/1（Judging）外的已知码均为终态，含 CE/OLE/MLE/TLE/WA/RE/UKE/AC/Unaccepted/Hack 与 -1。
     */
    private val TERMINAL_CODES = setOf(-1, 2, 3, 4, 5, 6, 7, 11, 12, 14, 21, 22, 23)

    private val jobs = ConcurrentHashMap<String, Job>()

    /** 是否终态。 */
    fun isTerminal(code: Int?): Boolean = code != null && code in TERMINAL_CODES

    /**
     * 开始轮询 [rid]。
     * - 每次成功查询后回调 [onUpdate]；到达终态后回调 [onDone]。
     * - 连续失败达到上限回调 [onError] 并停止；[cancelAll] 或句柄 [Cancellable.cancel] 触发取消，
     *   取消时若已有可用的最近状态也会回调 [onDone]（携带最近状态，表示「中断」）。
     * - 同一 rid 的旧轮询会被取消；不同 rid 的轮询可并发运行。
     */
    fun startPolling(
        rid: String,
        onUpdate: (SubmissionStatus) -> Unit,
        onDone: (SubmissionStatus) -> Unit,
        onError: (Throwable) -> Unit,
    ): Cancellable {
        jobs.remove(rid)?.cancel()

        val finished = AtomicBoolean(false)
        val lastRef = AtomicReference<SubmissionStatus?>(null)

        fun finish(s: SubmissionStatus) {
            if (!finished.compareAndSet(false, true)) return
            onDone(s)
        }

        val job = scope.launch {
            var failures = 0
            while (isActive) {
                try {
                    val st = LuoguApiService.getSubmissionStatus(rid)
                    failures = 0
                    lastRef.set(st)
                    onUpdate(st)
                    if (isTerminal(st.statusCode)) {
                        finish(st)
                        break
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    if (failures >= MAX_CONSECUTIVE_FAILURES) {
                        onError(e)
                        finished.set(true)
                        break
                    }
                }
                if (isActive) delay(POLL_INTERVAL_MS)
            }
        }
        jobs[rid] = job
        job.invokeOnCompletion { cause ->
            jobs.remove(rid, job)
            // 因取消而终止（非正常终态）时，回调 onDone 携带最近一次已知状态。
            if (cause is CancellationException) lastRef.get()?.let { finish(it) }
        }

        return object : Cancellable {
            override fun cancel() {
                job.cancel()
            }

            override val isCancelled: Boolean get() = !job.isActive
        }
    }

    /** 取消全部正在进行的轮询。 */
    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }
}