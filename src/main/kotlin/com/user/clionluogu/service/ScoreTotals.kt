package com.user.clionluogu.service

import com.user.clionluogu.api.SubmissionStatus
import com.user.clionluogu.api.SubtaskResult
import com.user.clionluogu.storage.SubmissionHistoryService

/**
 * 各子任务得分的合计。一条口径：**拿不准就什么都不显示**。
 *
 * 空列表、或任一子任务没有 `score`（评测进行中、洛谷没展开子任务）都返回 null，
 * 因为「0 分」和「还不知道多少分」是两件不同的事——显示错的 0 分比不显示糟得多。
 * 全 WA 时合计确实是 0，那是合法值，会照常显示。
 *
 * 不带 `/满分`：`fullScore` 只在题目 DTO（[com.user.clionluogu.api.ProblemDetail]）上，
 * 提交记录里没有这个字段，硬凑只会显示成 0。
 */
object ScoreTotals {

    /** 任一为 null 或列表为空 → null；否则求和（0 是合法结果）。 */
    @JvmStatic
    fun sumOfScores(scores: List<Int?>): Int? {
        if (scores.isEmpty()) return null
        var total = 0
        for (s in scores) {
            if (s == null) return null
            total += s
        }
        return total
    }

    /** 实时评测结果（带逐子任务分数）的合计。 */
    @JvmStatic
    fun totalScoreOf(subtasks: List<SubtaskResult>): Int? = sumOfScores(subtasks.map { it.score })

    /**
     * 一次评测状态的合计：有逐子任务明细就按明细算，只回了得分文本就从文本兜底。
     * 评测页、未通过通知都走这一个函数，别各自再写一遍取值顺序。
     */
    @JvmStatic
    fun totalScoreOf(status: SubmissionStatus?): Int? {
        if (status == null) return null
        return if (status.subtaskResults.isNotEmpty()) {
            totalScoreOf(status.subtaskResults)
        } else {
            totalScoreFromInfoText(status.subtaskInfo)
        }
    }

    /** 重启后从持久化快照算：`Record.subtasks` 与 [SubtaskResult] 结构等价，分数口径要一致。 */
    @JvmStatic
    fun totalScoreOfRecords(subtasks: List<SubmissionHistoryService.SubtaskRecord>): Int? =
        sumOfScores(subtasks.map { it.score })

    /**
     * 只有子任务得分文本时的兜底（`SubmissionStatus.subtaskInfo`，形如 `#1: 40分`）。
     *
     * 取最后一个冒号（半角或全角）之后的部分，剥掉结尾的「分」再解析；
     * 任何一行不合这个式子就整体返回 null——半截合计比没有合计更容易骗人。
     */
    @JvmStatic
    fun totalScoreFromInfoText(info: List<String>): Int? =
        sumOfScores(info.map { parseInfoLine(it) })

    private fun parseInfoLine(line: String): Int? {
        val cut = maxOf(line.lastIndexOf(':'), line.lastIndexOf('：'))
        if (cut < 0) return null
        val tail = line.substring(cut + 1).trim().removeSuffix("分").trim()
        return tail.toIntOrNull()
    }

    /** 详情页要显示的那一小段；null 时返回空串，调用方直接拼即可。前导空格是文案的一部分。 */
    @JvmStatic
    fun totalScoreText(total: Int?): String = if (total == null) "" else " $total 分"
}
