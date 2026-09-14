package com.user.clionluogu.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.user.clionluogu.api.LuoguProblemDto
import com.user.clionluogu.settings.LuoguSettings

/**
 * 为指定题目在项目根目录生成 `Pxxxx.cpp` 以及专用样例文件夹 `Pxxxx_samples/`，
 * 并返回生成结果（文件句柄 + 路径信息 + 提示），供动作层展示。
 */
object ProblemFileGenService {

    /** 生成结果：携带 cpp / 样例夹句柄、样例目录与 md 路径、样例文件列表、组数、提示等。 */
    data class GenResult(
        /** 生成的 `.cpp` 文件句柄（已存在时复用其句柄，不再覆盖）。 */
        val cpp: VirtualFile?,
        /** 样例文件夹句柄（样例为空时为 null）。 */
        val samplesDir: VirtualFile?,
        /** 样例文件夹绝对路径（样例为空时为 null）。 */
        val samplesDirPath: String?,
        /** 样例文件名列表（如 `P1001_1.in`），按下标从 1 起，样例为空时为空列表。 */
        val sampleFileNames: List<String> = emptyList(),
        /** 样例组数，即 problem.samples 的元素个数。 */
        val sampleCount: Int = 0,
        /** 生成的 md 路径（当前不生成，预留为 null）。 */
        val mdPath: String? = null,
        /** 提示信息（正常 / 无样例 / 未覆盖 .cpp 等）。 */
        val notices: List<String> = emptyList(),
    )

    /**
     * 在 project 基目录生成 `.cpp` 与 `Pxxxx_samples/` 样例文件夹并返回句柄与提示。
     *
     * - .cpp 内容取 [LuoguSettings.codeTemplate]，已存在同名时**不覆盖**，复用其句柄。
     * - 把 problem.samples 的**全部**样例写入 `Pxxxx_samples/`，命名为 `Pxxxx_N.in/.out`（N 从 1 起），
     *   内容逐字节保真、UTF-8 无 BOM；已存在同名样例夹时内部文件被重写覆盖。
     * - 样例为空时不创建样例文件夹，仅生成 .cpp 并提示「该题无样例」。
     */
    fun generate(project: Project, pid: String, problem: LuoguProblemDto): GenResult {
        val base = project.basePath
            ?: return GenResult(null, null, null, sampleCount = 0, notices = listOf("无法获取项目基目录"))
        val baseDir = LocalFileSystem.getInstance().findFileByPath(base)
        if (baseDir == null || !baseDir.isDirectory) {
            return GenResult(null, null, null, sampleCount = 0, notices = listOf("项目基目录不存在：$base"))
        }

        val samples = problem.samples
        val sampleCount = samples.size
        val sampleFolderName = "${pid}_samples"

        var cpp: VirtualFile? = null
        var samplesDir: VirtualFile? = null
        var samplesDirPath: String? = null
        val sampleFileNames = mutableListOf<String>()
        val notices = mutableListOf<String>()

        ApplicationManager.getApplication().runWriteAction {
            val code = LuoguSettings.getInstance().codeTemplate

            // .cpp：已存在则不覆盖，复用其句柄
            val existingCpp = baseDir.findChild("$pid.cpp")
            if (existingCpp != null && !existingCpp.isDirectory) {
                cpp = existingCpp
                notices.add("已存在 $pid.cpp，未覆盖")
            } else {
                cpp = writeTextFile(project, baseDir, "$pid.cpp", code)
            }

            if (samples.isEmpty()) {
                notices.add("该题无样例")
            } else {
                // 创建或复用样例文件夹
                val dir = baseDir.findChild(sampleFolderName)
                    ?.takeIf { it.isDirectory }
                    ?: baseDir.createChildDirectory(project, sampleFolderName)
                samplesDir = dir
                samplesDirPath = dir.path
                samples.forEachIndexed { index, sample ->
                    val n = index + 1
                    val input = sample.getOrNull(0) ?: ""
                    val output = sample.getOrNull(1) ?: ""
                    writeTextFile(project, dir, "${pid}_${n}.in", input)
                    writeTextFile(project, dir, "${pid}_${n}.out", output)
                    sampleFileNames.add("${pid}_${n}.in")
                    sampleFileNames.add("${pid}_${n}.out")
                }
            }
        }

        if (samplesDir != null) {
            VfsUtil.markDirtyAndRefresh(false, false, true, samplesDir)
        } else {
            VfsUtil.markDirtyAndRefresh(false, false, true, baseDir)
        }
        return GenResult(
            cpp = cpp,
            samplesDir = samplesDir,
            samplesDirPath = samplesDirPath,
            sampleFileNames = sampleFileNames,
            sampleCount = sampleCount,
            mdPath = null,
            notices = notices,
        )
    }

    /** 创建或复用同名文件并写入 UTF-8（无 BOM）文本。 */
    private fun writeTextFile(project: Project, dir: VirtualFile, name: String, content: String): VirtualFile {
        val file = dir.findChild(name) ?: dir.createChildData(project, name)
        VfsUtil.saveText(file, content)
        return file
    }
}