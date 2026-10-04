package com.user.clionluogu.service

import java.io.File

/**
 * 「本地那份文件变了没有」的**便宜**签名 —— 运行侧两个面板每秒比对它，变了才重探。
 *
 * 为什么需要这个：拉完成题之后，对拍 / 自测页还挂着上一次探测的结果（那时 `Pxxx.cpp` 还不存在），
 * 于是显示「项目根没有 Pxxx.cpp」，除非他手动点「重新查找样例」。工具窗口的 Content 是缓存的，
 * 生命周期回调靠不住（1.7.4 已经证明过一次），而「拉题完成」这件事和运行侧之间没有现成事件。
 *
 * 所以直接盯磁盘：**签名变了就重探**。这条每秒都要跑，因此只 `stat`（长度 + mtime）
 * 和数一下目录里有几个文件，绝不读文件内容。
 */
object LocalRunSignature {

    /** 源文件与样例目录的现况签名。两处都拿不到时是稳定串（不会每秒触发重探）。 */
    @JvmStatic
    fun of(sourceFile: File?, samplesDir: File?): String {
        val src = sourceFile?.let { if (it.isFile) "src:${it.length()}" else "src:0" } ?: "src:none"
        val dir = samplesDir?.let { d ->
            if (!d.isDirectory) "dir:none"
            else {
                // listFiles 走异常兜底：权限问题下当作「数不出来」，别让每秒的探测抛出去
                val count = runCatching { d.listFiles()?.size ?: -1 }.getOrDefault(-1)
                "dir:$count:${d.lastModified()}"
            }
        } ?: "dir:none"
        return "$src|$dir"
    }

    /** 项目根下这道题的源文件（与 [LuoguActions.probeCompareTarget] 认的同一个路径）。 */
    @JvmStatic
    fun sourceFile(basePath: String?, pid: String): File? {
        if (basePath.isNullOrBlank() || pid.isBlank()) return null
        return File(basePath, "$pid.cpp")
    }

    /** 这道题的样例目录（命名口径与 [SampleSetService] 一致）。 */
    @JvmStatic
    fun samplesDir(basePath: String?, pid: String): File? {
        if (basePath.isNullOrBlank() || pid.isBlank()) return null
        return File(basePath, SampleSetService.samplesDirName(pid))
    }
}
