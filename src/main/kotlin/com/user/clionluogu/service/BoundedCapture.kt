package com.user.clionluogu.service

import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.util.Key
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 有界输出捕获：超过 [limitBytes] 立刻杀掉进程并置 [overLimit]。
 *
 * 对拍和编译共用它。**不要用 `CapturingProcessHandler`**：那是无界缓冲，
 * 一个死循环打印的本地程序（或一坨模板错误诊断）能把 IDE 内存吃满 ——
 * 超限即 `destroyProcess()` 也是平台自己超时时的做法。
 *
 * 回调发生在平台的输出线程上，所以内部只碰同步结构，绝不触碰 Swing。
 */
internal class BoundedCapture(private val limitBytes: Int) : ProcessAdapter() {

    private val out = StringBuilder()
    private val err = StringBuilder()
    private val terminated = CountDownLatch(1)

    @Volatile
    var overLimit = false
        private set

    @Volatile
    var exitCode: Int? = null
        private set

    private var handler: ProcessHandler? = null

    fun attach(handler: ProcessHandler) {
        this.handler = handler
    }

    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        val text = event.text
        // 平台除了 stdout/stderr，还会用 SYSTEM 类型额外推一条「命令行回显」。它不是程序输出，
        // 混进比对流就会让每组都多出一行路径，把对判成错。
        val stdout = ProcessOutputType.isStdout(outputType)
        if (!stdout && !ProcessOutputType.isStderr(outputType)) return
        val target = if (stdout) out else err
        synchronized(target) {
            if (target.length + text.length > limitBytes) {
                if (!overLimit) {
                    overLimit = true
                    handler?.destroyProcess()
                }
                return
            }
            if (!overLimit) target.append(text)
        }
    }

    override fun processTerminated(event: ProcessEvent) {
        exitCode = event.exitCode
        terminated.countDown()
    }

    override fun processNotStarted() {
        terminated.countDown()
    }

    /** 等进程收尾（终止后输出线程还会把管道里剩下的字节送完）。 */
    fun awaitTerminated(timeoutMs: Long): Boolean =
        terminated.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun stdoutText(): String = synchronized(out) { out.toString() }

    fun stderrText(): String = synchronized(err) { err.toString() }

    /** 编译用的合并视图（编译器诊断本来就在 stderr，两边顺序又不重要）。 */
    fun combinedText(): String = stdoutText() + stderrText()
}
