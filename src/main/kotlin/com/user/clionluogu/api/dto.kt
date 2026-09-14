package com.user.clionluogu.api

import kotlinx.serialization.Serializable

/** 题目耗时/内存限制。time 为毫秒数组，memory 为 KB 数组。 */
@Serializable
data class LuoguLimitsDto(
    val time: List<Long> = emptyList(),
    val memory: List<Long> = emptyList(),
)

/**
 * 洛谷题目数据（对应 JSON 中 data.problem）。
 * sample 为二维数组 `[["20 30\n","50\n"]]`，即 List<List<String>>，MVP 只取第 0 个。
 */
@Serializable
data class LuoguProblemDto(
    val pid: String = "",
    val type: String = "",
    val name: String = "",
    val difficulty: Int = 0,
    val tags: List<Int> = emptyList(),
    val samples: List<List<String>> = emptyList(),
    val limits: LuoguLimitsDto = LuoguLimitsDto(),
    val fullScore: Int = 0,
    val totalSubmit: Int = 0,
    val totalAccepted: Int = 0,
    val flag: Int? = null,
    val showScore: Boolean = false,
    val acceptSolution: Boolean = false,
)

/** 顶级信封，承载 data.problem 与 status 等。 */
@Serializable
data class LuoguEnvelopeDto(
    val data: LuoguEnvelopeDataDto? = null,
    val status: Int = 0,
)

/** envelope.data，内含 problem。 */
@Serializable
data class LuoguEnvelopeDataDto(
    val problem: LuoguProblemDto? = null,
)