package com.user.clionluogu.ui

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.user.clionluogu.api.LuoguPidValidator

/**
 * 「当前编辑器打开的是哪道题」的唯一推断入口：提交页与对拍页共用，避免两处规则分叉。
 *
 * 三个方法都要在 EDT 调用（读 `FileEditorManager` 的选中文件）。
 */
object CurrentFilePid {

    /** 当前选中的文件；没有打开任何文件时为 null。 */
    fun currentFile(project: Project): VirtualFile? =
        FileEditorManager.getInstance(project).selectedFiles.firstOrNull()

    /** 当前文件的题号；文件名不像题号（如 `main.cpp`）时返回 null。 */
    fun guess(project: Project): String? =
        currentFile(project)?.let { LuoguPidValidator.pidFromFileName(it.name) }
}
