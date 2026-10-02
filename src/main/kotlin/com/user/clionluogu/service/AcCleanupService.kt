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
 * 删掉某题在本地生成的四类文件：`{pid}.cpp`、`{pid}.md`、`{pid}_samples/`、`{pid}_cases/`。
 *
 * 两个入口共用同一套内核：
 * - [promptAndCleanup]：评测刚变 AC 时主动问一句（受设置里的开关控制）；
 * - [confirmAndDelete]：「题目」页签里点了「删除本题文件」（不看那个开关）。
 *
 * 删除不可逆，所以只认**项目根下这四个精确名字**——不递归搜索、不按内容匹配，
 * 改过名或放进子目录的文件一律不碰；索引页列出来的东西与此处能删的东西完全同源
 * （口径分叉会出现「列出来却删不掉」）。
 *
 * **提交记录（[com.user.clionluogu.storage.SubmissionHistoryService]）永远不碰**：
 * 这里删的是磁盘上的工作文件，评测历史是另一回事，删了就找不回来了。
 */
object AcCleanupService {

    private const val GROUP_ID = "ClionLuogu.Notifications"

    /** 递归深度上限：真出事了（软链成环、目录套了几十层）也别一路删到底。 */
    private const val MAX_DEPTH = 8

    /** 本题的四类产物名，与 [ProblemIndexService] 的后缀口径一致。 */
    @JvmStatic
    fun targetNames(pid: String): List<String> =
        listOf("$pid.cpp", "$pid.md", "${pid}_samples", "${pid}_cases")

    /** 项目根下**实际存在**的那几个（`.cpp`/`.md` 必须是文件，两个目录必须是目录）。 */
    @JvmStatic
    fun targetsOf(baseDir: VirtualFile, pid: String): List<VirtualFile> {
        val names = targetNames(pid)
        val isDirWanted = listOf(false, false, true, true)
        return names.mapIndexedNotNull { i, name ->
            baseDir.findChild(name)?.takeIf {
                it.isValid && it.isDirectory == isDirWanted[i]
            }
        }
    }

    /** 确认框里那份待删清单（每行一个 `· 名字`，目录带文件数）。 */
    @JvmStatic
    fun listingOf(targets: List<VirtualFile>): String = buildString {
        targets.forEach { target ->
            if (target.isDirectory) {
                val n = runCatching { target.children.count { !it.isDirectory } }.getOrDefault(0)
                appendLine("· ${target.name}/（$n 个文件）")
            } else {
                appendLine("· ${target.name}")
            }
        }
    }

    /**
     * 展开成合法的删除顺序：**递归后序**，子项在前、目录在后
     * （`VirtualFile.delete()` 删不掉非空目录）。真正的排序逻辑在 [DeleteOrder]，
     * 那样才能在没起 IDE 的探针里验；超过 [maxDepth] 的目录不深入，删除时会如实失败。
     */
    @JvmStatic
    fun deleteOrder(targets: List<VirtualFile>, maxDepth: Int = MAX_DEPTH): List<VirtualFile> =
        DeleteOrder.orderOf(
            targets = targets,
            isDir = { it.isDirectory },
            childrenOf = { f -> runCatching { f.children.toList() }.getOrDefault(emptyList()) },
            maxDepth = maxDepth,
        )

    /** 必须在 EDT 调用（内部要弹模态框）。无可删文件或提醒已关闭时静默返回。 */
    fun promptAndCleanup(project: Project, pid: String) {
        if (!LuoguSettings.getInstance().acCleanupEnabled) return
        val baseDir = projectBaseDir(project) ?: return

        val targets = targetsOf(baseDir, pid)
        if (targets.isEmpty()) return

        val answer = Messages.showYesNoDialog(
            project,
            "$pid 已 AC。删除本题生成的文件？\n\n${listingOf(targets)}\n" +
                "样例与反例目录下的全部文件会一并删除；编辑器中未保存的修改也会一并丢弃。",
            "洛谷：本题已 AC",
            "删除",
            "保留",
            Messages.getQuestionIcon(),
        )
        if (answer != Messages.YES) return

        deleteLater(project, baseDir, pid, targets)
    }

    /**
     * 「题目」页签的删除入口：**必须有人点过按钮才会走到这里**，且只弹一次确认框，
     * 框里列出实际存在的待删项。确认框是唯一闸门，所以没有开关可以绕过它。
     *
     * [onDone] 在删除与刷新之后回调（用于让索引页重扫）。必须在 EDT 调用。
     */
    fun confirmAndDelete(project: Project, pid: String, onDone: () -> Unit = {}) {
        val baseDir = projectBaseDir(project) ?: run {
            notify(project, pid, "项目还没落盘目录，删不了", warning = true)
            return
        }
        val targets = targetsOf(baseDir, pid)
        if (targets.isEmpty()) {
            notify(project, pid, "项目根下没有本题的 .cpp / .md / 样例 / 反例目录", warning = true)
            onDone()
            return
        }

        val answer = Messages.showYesNoDialog(
            project,
            "删除 $pid 在本地的文件？删除后无法撤销。\n\n${listingOf(targets)}\n" +
                "只删项目根下这四个名字；提交记录与评测历史保留；编辑器里未保存的修改一并丢弃。",
            "洛谷：删除本题文件",
            "删除",
            "取消",
            Messages.getWarningIcon(),
        )
        if (answer != Messages.YES) return

        deleteLater(project, baseDir, pid, targets, onDone)
    }

    /**
     * 先关编辑器、再在写动作里按 [deleteOrder] 的顺序删除。
     * 单个文件失败（只读、被占用、超出深度）不中断其余，结果如实计数并通知。
     */
    fun deleteLater(
        project: Project,
        baseDir: VirtualFile,
        pid: String,
        targets: List<VirtualFile>,
        onDone: () -> Unit = {},
    ) {
        val flat = deleteOrder(targets)
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

            val text = "已删除 $deleted 项" + if (failed > 0) "，$failed 项失败（只读、被占用或超出深度）" else ""
            notify(project, pid, text, warning = failed > 0)
            onDone()
        }
    }

    private fun projectBaseDir(project: Project): VirtualFile? =
        project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
            ?.takeIf { it.isDirectory }

    private fun notify(project: Project, pid: String, text: String, warning: Boolean) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(
                "$pid 文件清理",
                text,
                if (warning) NotificationType.WARNING else NotificationType.INFORMATION,
            )
            .notify(project)
    }
}
