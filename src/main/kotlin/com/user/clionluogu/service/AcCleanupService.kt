package com.user.clionluogu.service

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.user.clionluogu.settings.LuoguSettings

/**
 * AC 之后询问是否删掉本题在本地生成的三类文件：`{pid}.cpp`、`{pid}.md`、`{pid}_samples/`。
 *
 * 删除不可逆，所以只认**项目根下这三个精确名字**——不递归搜索、不按内容匹配，
 * 改过名或放进子目录的文件一律不碰。提交记录（`SubmissionHistoryService`）不受影响。
 */
object AcCleanupService {

    private const val GROUP_ID = "ClionLuogu.Notifications"

    /** 必须在 EDT 调用（内部要弹模态框）。无可删文件或提醒已关闭时静默返回。 */
    fun promptAndCleanup(project: Project, pid: String) {
        if (!LuoguSettings.getInstance().acCleanupEnabled) return
        val baseDir = project.basePath
            ?.let { LocalFileSystem.getInstance().findFileByPath(it) }
            ?.takeIf { it.isDirectory }
            ?: return

        val cpp = baseDir.findChild("$pid.cpp")?.takeIf { !it.isDirectory }
        val md = baseDir.findChild("$pid.md")?.takeIf { !it.isDirectory }
        val samples = baseDir.findChild("${pid}_samples")?.takeIf { it.isDirectory }
        val targets = listOfNotNull(cpp, md, samples)
        if (targets.isEmpty()) return

        val sampleCount = runCatching { samples?.children?.count { !it.isDirectory } ?: 0 }.getOrDefault(0)
        val listing = buildString {
            cpp?.let { appendLine("· ${it.name}") }
            md?.let { appendLine("· ${it.name}") }
            samples?.let { appendLine("· ${it.name}/（$sampleCount 个样例文件）") }
        }
        val answer = Messages.showYesNoDialog(
            project,
            "$pid 已 AC。删除本题生成的文件？\n\n$listing\n" +
                "样例目录下的全部 .in/.out 会一并删除；编辑器中未保存的修改也会一并丢弃。",
            "洛谷：本题已 AC",
            "删除",
            "保留",
            Messages.getQuestionIcon(),
        )
        if (answer != Messages.YES) return

        deleteLater(project, baseDir, pid, targets)
    }

    /**
     * 先关编辑器、再在写动作里删除：先删子文件后删目录（`VirtualFile.delete()` 不允许删非空目录）。
     * 单个文件失败（只读、被占用）不中断其余，结果如实计数并通知。
     */
    private fun deleteLater(
        project: Project,
        baseDir: VirtualFile,
        pid: String,
        targets: List<VirtualFile>,
    ) {
        // 目录展开成「子文件在前、目录在后」，保证删除顺序合法
        val flat = targets.flatMap { target ->
            if (target.isDirectory) {
                runCatching { target.children.filter { !it.isDirectory } }.getOrDefault(emptyList()) + target
            } else {
                listOf(target)
            }
        }
        ApplicationManager.getApplication().invokeLater {
            val editorManager = FileEditorManager.getInstance(project)
            flat.forEach { editorManager.closeFile(it) }

            var deleted = 0
            var failed = 0
            ApplicationManager.getApplication().runWriteAction {
                flat.forEach { file ->
                    val ok = runCatching { if (file.isValid) file.delete(null) }.isSuccess
                    if (ok) deleted++ else failed++
                }
            }
            VfsUtil.markDirtyAndRefresh(false, true, true, baseDir)

            val text = "已删除 $deleted 项" + if (failed > 0) "，$failed 项失败（只读或被占用）" else ""
            NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP_ID)
                .createNotification(
                    "$pid 文件清理",
                    text,
                    if (failed > 0) NotificationType.WARNING else NotificationType.INFORMATION,
                )
                .notify(project)
        }
    }
}
