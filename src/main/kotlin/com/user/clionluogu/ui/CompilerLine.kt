package com.user.clionluogu.ui

import com.user.clionluogu.service.CompilerService

/**
 * 「编译器」那一行的写法，对拍页与自测页共用。
 *
 * 抽出来的理由和 `LimitFields` 一样：两处各写一遍的话，一边说了「用的是 CLion 选的那个」、
 * 另一边没说，他就有理由问「到底哪个在编我的代码」。
 * 纯函数（只吃数据、不碰 Swing），所以能离线断言。
 */
object CompilerLine {

    /** 出处那句短话；`NONE` 时不追加，免得写成「编译器：未找到 · 」。 */
    @JvmStatic
    fun originText(origin: CompilerService.Origin): String? = when (origin) {
        CompilerService.Origin.SETTINGS -> "设置里填的"
        CompilerService.Origin.CLION -> "CLion 选的"
        CompilerService.Origin.PATH -> "PATH 上找的"
        CompilerService.Origin.NONE -> null
    }

    /**
     * 头部那一行：谁在编 + 从哪来的 +（该提醒时）一句「建议装 GCC」。
     *
     * 提醒做成这一行的**后缀**而不是另加一个控件：底部窗口只有一行高的余量，
     * 多摆一个标签就要么挤掉编译器名、要么折出第二行（1.7.1 与 1.7.2 两次被裁掉的都是第二行）。
     * 后缀只有 7 个字，全文在 tooltip 里。
     */
    @JvmStatic
    @JvmOverloads
    fun text(
        compiler: CompilerService.Compiler?,
        origin: CompilerService.Origin,
        advice: String? = null,
    ): String {
        if (compiler == null) return "编译器：未找到"
        val parts = mutableListOf("编译器：${compiler.display()}")
        originText(origin)?.let { parts.add(it) }
        if (advice != null) parts.add("建议装 GCC")
        return parts.joinToString(" · ")
    }

    /**
     * 悬停全文：绝对路径 + 版本首行 + 出处的完整说法 + （ mac 上该装 GCC 时）那段建议。
     *
     * 「PATH 上找的」要说**为什么**没用 CLion 的那个：项目还没配置过 CMake（没有 `CMakeCache.txt`）
     * 是这里最常见的情况，不说清就成了「插件随便挑了一个编译器」。
     */
    @JvmStatic
    fun tooltip(compiler: CompilerService.Compiler?, origin: CompilerService.Origin, advice: String?): String? {
        if (compiler == null) return advice
        val reason = when (origin) {
            CompilerService.Origin.PATH ->
                "\n\n这个编译器是在 PATH 上找的：项目还没有 CMakeCache.txt（没配置过 CMake），" +
                    "或 CLion 记的那个当前不可用。"
            CompilerService.Origin.CLION -> "\n\n这是 CLion 项目实际在用的编译器（读自 CMakeCache.txt）。"
            CompilerService.Origin.SETTINGS -> "\n\n这是插件设置里填的那个，优先级最高。"
            CompilerService.Origin.NONE -> null
        }
        return listOfNotNull(
            "${compiler.file.absolutePath}\n${compiler.versionLine.orEmpty()}",
            reason,
            advice?.let { "\n$it" },
        ).joinToString("")
    }

    /**
     * 常驻那行警告该写什么；返回 null 表示清空。
     *
     * 「设置里的编译器不可用，已回落」优先于「该装 GCC」—— 前者是**这次跑的东西跟他以为的不一样**，
     * 后者只是建议。
     */
    @JvmStatic
    fun warnText(notice: String?, advice: String?): String? = notice ?: advice?.let {
        "这台 Mac 用的不是 GCC：clang 没有 <bits/stdc++.h>，与评测机也不同（悬停看怎么换）"
    }
}
