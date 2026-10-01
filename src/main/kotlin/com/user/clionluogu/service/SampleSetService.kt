package com.user.clionluogu.service

import java.io.File

/**
 * 发现某题的本地样例（`Pxxx_samples/Pxxx_N.in` 与 `.out`）。
 *
 * 目录与文件命名必须与 [ProblemFileGenService] 的落盘约定逐字一致：
 * 目录名 `"${pid}_samples"`，文件名 `"${pid}_N.in" / "${pid}_N.out"`（N 从 1 起）。
 * 后台线程调用。
 */
object SampleSetService {

    /** 一组样例（成对存在才算一组）。[index] 是文件名里的 N，不是列表下标。 */
    data class Sample(val index: Int, val inFile: File, val expectedFile: File)

    /** 发现结果。 */
    data class Found(
        val dirExists: Boolean,
        val dirPath: String,
        val samples: List<Sample>,
        /** 只有 `.in` 没有 `.out` 的编号（多半是用户自己删过或没写完期望输出）。 */
        val orphanInputIndexes: List<Int>,
    )

    /** 与 ProblemFileGenService 的样例夹命名同源。 */
    fun samplesDirName(pid: String): String = "${pid}_samples"

    fun samplesDir(projectBase: File, pid: String): File = File(projectBase, samplesDirName(pid))

    private val SAMPLE_FILE_REGEX = Regex("""^([A-Za-z]{1,4}\d{1,5})_(\d+)\.(in|out)$""", RegexOption.IGNORE_CASE)

    fun discover(projectBase: File, pid: String): Found {
        val dir = samplesDir(projectBase, pid)
        if (!dir.isDirectory) return Found(dirExists = false, dirPath = dir.path, samples = emptyList(), orphanInputIndexes = emptyList())

        val inputs = sortedMapOf<Int, File>()
        val outputs = sortedMapOf<Int, File>()
        runCatching { dir.listFiles() }.getOrNull()?.forEach { f ->
            if (!f.isFile) return@forEach
            val match = SAMPLE_FILE_REGEX.matchEntire(f.name) ?: return@forEach
            if (!match.groupValues[1].equals(pid, ignoreCase = true)) return@forEach
            val n = match.groupValues[2].toIntOrNull() ?: return@forEach
            if (match.groupValues[3].equals("in", ignoreCase = true)) inputs[n] = f else outputs[n] = f
        }

        // 按编号数值排序：字典序会把 P1001_10 排到 P1001_2 前面；编号也直接沿用文件名里的 N，
        // 用户删过 _2 时"样例 3"仍然对得上 P1001_3.out。
        val samples = inputs.keys.intersect(outputs.keys).sorted()
            .map { Sample(it, requireNotNull(inputs[it]), requireNotNull(outputs[it])) }
        return Found(
            dirExists = true,
            dirPath = dir.path,
            samples = samples,
            orphanInputIndexes = (inputs.keys - outputs.keys).sorted(),
        )
    }
}
