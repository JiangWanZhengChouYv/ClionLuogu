package com.user.clionluogu.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.user.clionluogu.api.LuoguProblemDto
import com.user.clionluogu.settings.LuoguSettings

/**
 * 为指定题目在项目根目录生成 `Pxxxx.cpp / Pxxxx.in / Pxxxx.out`，
 * 并返回生成结果（文件句柄 + 提示），供动作层展示。
 */
object ProblemFileGenService {

    data class GenResult(
        val cpp: VirtualFile?,
        val inFile: VirtualFile?,
        val outFile: VirtualFile?,
        val notices: List<String> = emptyList(),
    )

    /**
     * 在 project 基目录生成三个文件并返回句柄与提示。
     *
     * - .cpp 内容取 [LuoguSettings.codeTemplate]。
     * - .in/.out 取 problem.samples 第 0 个元素的输入/输出，逐字节保真，UTF-8 无 BOM。
     * - 无样例时仍生成 .cpp，.in/.out 为空文件并提示。
     * - 已存在同名 .cpp 时**不覆盖**，返回其句柄并提示；.in/.out 始终覆盖。
     */
    fun generate(project: Project, pid: String, problem: LuoguProblemDto): GenResult {
        val base = project.basePath ?: return GenResult(null, null, null, listOf("无法获取项目基目录"))
        val baseDir = LocalFileSystem.getInstance().findFileByPath(base)
        if (baseDir == null || !baseDir.isDirectory) {
            return GenResult(null, null, null, listOf("项目基目录不存在：$base"))
        }

        val hasSample = problem.samples.isNotEmpty()
        val input = problem.samples.firstOrNull()?.getOrNull(0) ?: ""
        val output = problem.samples.firstOrNull()?.getOrNull(1) ?: ""

        var cpp: VirtualFile? = null
        var inFile: VirtualFile? = null
        var outFile: VirtualFile? = null
        val notices = mutableListOf<String>()

        ApplicationManager.getApplication().runWriteAction {
            val code = LuoguSettings.getInstance().codeTemplate

            // .cpp：已存在则不覆盖
            val existingCpp = baseDir.findChild("$pid.cpp")
            if (existingCpp != null && !existingCpp.isDirectory) {
                cpp = existingCpp
                notices.add("已存在 $pid.cpp，未覆盖")
            } else {
                cpp = writeTextFile(project, baseDir, "$pid.cpp", code)
            }

            inFile = writeTextFile(project, baseDir, "$pid.in", input)
            outFile = writeTextFile(project, baseDir, "$pid.out", output)

            if (!hasSample) {
                notices.add("该题无样例")
            }
        }

        VfsUtil.markDirtyAndRefresh(false, false, true, baseDir)
        return GenResult(cpp, inFile, outFile, notices)
    }

    /** 创建或复用同名文件并写入 UTF-8（无 BOM）文本。 */
    private fun writeTextFile(project: Project, dir: VirtualFile, name: String, content: String): VirtualFile {
        val file = dir.findChild(name) ?: dir.createChildData(project, name)
        VfsUtil.saveText(file, content)
        return file
    }
}