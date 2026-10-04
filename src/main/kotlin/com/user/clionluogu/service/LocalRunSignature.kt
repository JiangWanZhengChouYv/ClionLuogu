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

    /**
     * 源文件、样例目录，以及 **CMakeCache** 的现况签名。三处都拿不到时是稳定串（不会每秒触发重探）。
     *
     * 第三个是 1.8.2 加的：对拍与自测现在用的是 CLion 选的编译器，而那个选择只写在 `CMakeCache.txt` 里。
     * 他在 CMake 配置里从 clang 换成 `g++-16` 之后，源码与样例一个字都没动 —— 若签名不看 cache，
     * 界面就继续挂着旧编译器的探测结果，等他改一行代码才刷新，
     * 症状跟他报过两次的「改了没用」一模一样。
     */
    @JvmStatic
    @JvmOverloads
    fun of(sourceFile: File?, samplesDir: File?, cacheFile: File? = null): String {
        val src = sourceFile?.let { if (it.isFile) "src:${it.length()}" else "src:0" } ?: "src:none"
        val dir = samplesDir?.let { d ->
            if (!d.isDirectory) "dir:none"
            else {
                // listFiles 走异常兜底：权限问题下当作「数不出来」，别让每秒的探测抛出去
                val count = runCatching { d.listFiles()?.size ?: -1 }.getOrDefault(-1)
                "dir:$count:${d.lastModified()}"
            }
        } ?: "dir:none"
        // cache 只 stat 修改时间（这一串每秒都要算，绝不读内容）
        val cache = cacheFile?.let { if (it.isFile) "cache:${it.lastModified()}" else "cache:none" } ?: "cache:none"
        return "$src|$dir|$cache"
    }

    /** 面板每秒用的那一个入口：项目根 + 题号 → 三项签名。 */
    @JvmStatic
    fun ofProject(basePath: String?, pid: String): String = of(
        sourceFile(basePath, pid),
        samplesDir(basePath, pid),
        ClionToolchain.cacheFile(basePath?.takeIf { it.isNotBlank() }?.let(::File)),
    )

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
