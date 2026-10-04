package com.user.clionluogu.service

import com.intellij.openapi.project.Project
import com.user.clionluogu.api.SubmissionStatus
import com.user.clionluogu.storage.SubmissionHistoryService
import com.user.clionluogu.ui.LuoguTabs

/**
 * 「提交之后」这条流水线的唯一负责人：**落盘 + 起轮询 + 状态跃迁**，
 * 顺便在评测页存在时叫它刷新界面。
 *
 * 为什么要单独一个 object：提交页 1.8.0 起住在**底部**运行窗口，而评测页在**左侧**主窗口。
 * 原来 `LuoguToolWindow.trackSubmission` 把「写持久化」和「刷 UI」绑在一起，
 * 于是「从底部提交、但左侧窗口从没打开过」= `evalWindow()` 为 null = **这条记录整个没落盘**，
 * 连重启后的补轮询都找不到它。记录不能因为某个界面没被创建过就丢，所以：
 * **持久化无条件做，UI 能找到就更新，找不到就算了。**
 */
object SubmissionTracker {

    /**
     * 记账一次新提交：写入项目级历史，并启动轮询。
     *
     * 可在 EDT 调用（提交页的回调就是这么进来的）；轮询回调在后台线程，由 [applyStatus] 自己切。
     */
    fun track(project: Project, pid: String, rid: String, lang: String, code: String) {
        val record = SubmissionHistoryService.Record().apply {
            this.pid = pid
            this.rid = rid
            this.submitTime = System.currentTimeMillis()
            this.lang = lang
            this.code = code
        }
        SubmissionHistoryService.getInstance(project).add(record)

        // 评测页此刻可能还不存在（左侧窗口没点开过），所以先刷 UI 再落盘也无所谓——
        // 但顺序固定为「落盘 → UI」，这样 UI 里看到的状态和磁盘上的一定是同一份。
        LuoguTabs.evalWindow(project)?.addSubmission(pid, rid, lang, code)

        // 同一 rid 的旧轮询会被 startPolling 取消，不同 rid 可并发轮询。
        JudgePollingService.startPolling(
            rid = rid,
            onUpdate = { st -> applyStatus(project, rid, st) },
            onDone = { st -> applyStatus(project, rid, st) },
            onError = { t -> LuoguTabs.evalWindow(project)?.markPollError(rid, t) },
        )
    }

    /**
     * 一次状态跃迁：无条件写持久化，再刷新评测页。
     *
     * [quiet] = true 用于重启后补拉的老记录：那是**过期的跃迁**，
     * 弹清理模态框或失败通知都莫名其妙，只把数据带回来。
     */
    fun applyStatus(project: Project, rid: String, status: SubmissionStatus, quiet: Boolean = false) {
        SubmissionHistoryService.getInstance(project).update(rid, status)
        LuoguTabs.evalWindow(project)?.updateSubmission(rid, status, quiet)
    }
}
