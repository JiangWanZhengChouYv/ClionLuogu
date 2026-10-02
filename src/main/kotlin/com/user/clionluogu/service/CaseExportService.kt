package com.user.clionluogu.service

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.user.clionluogu.service.SampleSetService.Sample
import java.io.File

/**
 * 把对拍**失败**的那一组落成可复现的反例文件，写进项目根的 `{pid}_cases/`。
 *
 * 为什么需要它：详情区的差异上下文只截 ±20 行、单行还截 200 字符，那是用来看的，不是用来调的。
 * 想拿调试器或本地重跑，必须有一字节不变的完整输入与实际输出。
 *
 * 只落地，不改写：`.in` 与期望输出直接从磁盘复制，`.actual.out` 用对拍当场留下的完整 stdout，
 * 文件里不加任何说明性装饰 —— 加了就没法直接 `diff`、没法直接喂给程序。
 */
object CaseExportService {

    private const val GROUP_ID = "ClionLuogu.Notifications"

    /** 目录名跟拉题落盘的 `{pid}_samples` 同一命名法，一眼认得出属于哪道题。 */
    fun casesDirName(pid: String): String = "${pid}_cases"

    /** 一次导出的结果，UI 拿它组文案。 */
    data class Exported(val dir: File, val files: List<String>, val actualWasEmpty: Boolean)

    /**
     * 该写哪些文件、每个文件写什么字节。
     *
     * 只碰 `java.io`，不碰 IDE —— 拆出来才能离线断言「一字节不改」（加了任何说明性装饰就没法直接
     * `diff`、也没法直接喂给程序）。读不到的文件直接不进结果，而不是写个空壳。
     */
    fun plannedFiles(pid: String, sample: Sample, result: SampleCompareService.Result): Map<String, ByteArray> {
        val stem = "${pid}_${sample.index}"
        val out = LinkedHashMap<String, ByteArray>()
        runCatching { sample.inFile.readBytes() }.getOrNull()?.let { out["$stem.in"] = it }
        runCatching { sample.expectedFile.readBytes() }.getOrNull()?.let { out["$stem.expected.out"] = it }
        out["$stem.actual.out"] = result.actualOutput?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        result.stderr?.takeIf { it.isNotBlank() }?.let { out["$stem.stderr.txt"] = it.toByteArray(Charsets.UTF_8) }
        return out
    }

    /**
     * 写一组反例。返回 null = 项目没落盘或目录建不出来（调用方如实说一声，不静默）。
     *
     * 必须在 EDT 调用（末尾要刷新 VFS，让 Project 视图立刻看到新目录）。
     */
    fun export(project: Project, pid: String, sample: Sample, result: SampleCompareService.Result): Exported? {
        val base = runCatching { project.basePath }.getOrNull()?.let { File(it) } ?: return null
        val dir = File(base, casesDirName(pid))
        if (!dir.isDirectory && !runCatching { dir.mkdirs() }.getOrDefault(false)) return null
        if (!dir.isDirectory) return null

        val names = mutableListOf<String>()
        plannedFiles(pid, sample, result).forEach { (name, bytes) ->
            val file = File(dir, name)
            if (runCatching { file.writeBytes(bytes) }.isSuccess) names.add(name)
        }

        runCatching { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(base) }
            .getOrNull()
            ?.let { VfsUtil.markDirtyAndRefresh(false, true, true, it) }

        return Exported(dir, names, result.actualOutput.isNullOrBlank())
    }

    /** 通知文案：报文件数与目录，实际输出为空要说出来（超时 / 崩溃在读取之前的组就是这个样子）。 */
    fun notifyExported(project: Project, pid: String, exported: Exported) {
        val body = buildString {
            append(exported.files.joinToString("、").ifBlank { "（一个都没写成）" }).append('\n')
            append("目录：").append(exported.dir.path)
            if (exported.actualWasEmpty) {
                append("\n实际输出是空的：这一组在产出任何东西之前就超时或崩了，输入仍然可以拿去复现。")
            }
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(
                "$pid 反例已导出（${exported.files.size} 个文件）",
                body,
                if (exported.files.isEmpty()) NotificationType.WARNING else NotificationType.INFORMATION,
            )
            .notify(project)
    }
}
