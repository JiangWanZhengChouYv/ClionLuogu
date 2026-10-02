package com.user.clionluogu.storage

import com.user.clionluogu.api.SubmissionStatus
import com.user.clionluogu.api.SubtaskResult
import com.user.clionluogu.api.TestCaseResult
import com.user.clionluogu.api.statusTextOf

/**
 * 把持久化记录还原成一次评测状态快照。
 *
 * 单独放一个文件、并且是公开的：[SubmissionHistoryService.Record] 每加一个字段，这里就必须同步
 * 加一次，否则重启 IDE 后详情区会「少一块东西」（编译错误、得分这类现象最难查）。
 * 探针里有一条 round-trip 断言（add → getState → loadState → 这里）专门守这件事。
 *
 * 无任何评测数据时返回 null（视为等待中）。
 */
fun SubmissionHistoryService.Record.toStatus(): SubmissionStatus? {
    if (statusCode == null && timeMs == null && memoryKb == null && compileError == null && subtasks.isEmpty()) {
        return null
    }
    return SubmissionStatus(
        rid = rid,
        statusCode = statusCode,
        statusText = statusTextOf(statusCode),
        timeMs = timeMs,
        memoryKb = memoryKb,
        compileError = compileError,
        subtaskResults = subtasks.map { sub ->
            SubtaskResult(
                id = sub.id,
                status = sub.status,
                score = sub.score,
                testCases = sub.testCases.map { tc ->
                    TestCaseResult(
                        id = tc.id,
                        status = tc.status,
                        timeMs = tc.timeMs,
                        memoryKb = tc.memoryKb,
                        score = tc.score,
                    )
                },
            )
        },
    )
}
