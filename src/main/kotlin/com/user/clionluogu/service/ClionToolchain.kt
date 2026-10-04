package com.user.clionluogu.service

import java.io.File

/**
 * CLion 这个项目**实际用的哪个编译器**：读 CMake 自己落盘的 `CMakeCache.txt`。
 *
 * 为什么读文件而不是调 CLion 的 API：那些配置类在 CLion 自己的 bundled module 里
 * （`com.jetbrains.cmake.*`），依赖它就得再往 `<depends>` 上加一层，插件被绑死在某个 CLion 版本上；
 * 而 `CMakeCache.txt` 是 CMake 公开写的产物，并且是**最终生效的那一份** ——
 * profile 里选的编译器、`-D` 参数、工具链设置，最后都落到 `CMAKE_CXX_COMPILER` 这一行。
 *
 * 只在**后台线程**调用（要列目录、读文件）。
 */
object ClionToolchain {

    private const val CACHE_NAME = "CMakeCache.txt"

    /** 只看项目根与它的一层子目录：CLion 的产物目录就是 `<项目根>/cmake-build-<profile>/`。 */
    private const val MAX_CHILD_DIRS = 80

    /**
     * 项目里**最近一次配置**留下的 cache；一份都没有（还没配置过 CMake）返回 null。
     *
     * 多 profile（`cmake-build-debug` + `cmake-build-release`）时按修改时间取最近的那个 ——
     * 「他用哪个」问的就是他最后一次构建用的那个，不是字典序第一个。
     *
     * 分两段找、**命中就早退**：这个函数被每秒的磁盘签名调用（见
     * [LocalRunSignature.ofProject]），不能每次都把项目根的子目录全 `stat` 一遍。
     */
    @JvmStatic
    fun cacheFile(baseDir: File?): File? {
        if (baseDir == null || !baseDir.isDirectory) return null
        val children = runCatching { baseDir.listFiles { f -> f.isDirectory }?.toList() }.getOrNull()
            .orEmpty()
            .filter { !it.name.startsWith(".") }
        // 常去的那两处先查（CLion 的默认产物目录 + 极少见的 in-source 配置），仍然按修改时间取最近的那份
        val usual = children.filter { it.name.startsWith("cmake-build", ignoreCase = true) }
            .map { File(it, CACHE_NAME) } + File(baseDir, CACHE_NAME)
        newest(usual)?.let { return it }
        // 自定的构建目录名千奇百怪，最后再广撒一次网，但仍然有上限（不递归）
        return newest(children.take(MAX_CHILD_DIRS).map { File(it, CACHE_NAME) })
    }

    private fun newest(files: List<File>): File? = files
        .filter { runCatching { it.isFile }.getOrDefault(false) }
        .maxByOrNull { it.lastModified() }

    /**
     * 从 cache 文本里取 `CMAKE_CXX_COMPILER` 的值。
     *
     * cache 里同名前缀的行有一堆，全部要避开：注释行（`//…`）、`CMAKE_CXX_COMPILER-ADVANCED`、
     * `CMAKE_CXX_COMPILER_ARG1`，以及配置失败时那个骗人的 `CMAKE_CXX_COMPILER-NOTFOUND=`。
     */
    @JvmStatic
    fun compilerPath(cacheText: String): String? {
        for (raw in cacheText.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            if (line.substring(0, eq).substringBefore(':') != "CMAKE_CXX_COMPILER") continue
            val value = line.substring(eq + 1).trim()
            if (value.isEmpty() || value.endsWith("-NOTFOUND")) continue
            return value
        }
        return null
    }

    /** 项目实际用的编译器可执行文件；没 cache、或那一条路径不可执行时返回 null（由调用方回落）。 */
    @JvmStatic
    fun compiler(baseDir: File?): File? {
        val cache = cacheFile(baseDir) ?: return null
        val text = runCatching { cache.readText() }.getOrNull() ?: return null
        val path = compilerPath(text) ?: return null
        val file = File(path)
        return runCatching { if (file.isFile && file.canExecute()) file else null }.getOrNull()
    }
}
