package com.user.clionluogu.ui

import com.user.clionluogu.service.CompareTarget
import com.user.clionluogu.service.SampleSetService
import java.io.File

/**
 * 「本地跑不起来」的那几道闸门，对拍页与自测页共用。
 *
 * 三条原因的文案原先写在 [SampleComparePanel.blockingReason] 里，而自测页需要的是**去掉样例那一关**
 * 的同一条链（自测存在的理由就是「没有成对样例也能跑一把」）。
 * 两处各写一份的话，改一处文案另一处就旧了——所以搬到这里，两个页面按各自的闸门组合调用。
 *
 * 纯函数：只吃 [CompareTarget] 那种数据，不碰 Swing / `Project`，这样能离线断言
 * （判反过一次：文件明明存在却报「找不到源文件」）。
 */
object LocalRunGates {

    /** 项目还没落盘（新建未保存的工程）：这时源文件与样例都不用再查。 */
    @JvmStatic
    fun noProject(t: CompareTarget): SampleComparePanel.Block? =
        if (t.samples != null) null else SampleComparePanel.Block(
            "当前项目没有落盘目录",
            "新建但还没保存到磁盘的项目没有样例，也没有源文件。\n" +
                "先把项目存到磁盘上，再回来点「重新查找样例」。",
        )

    /** 对拍专用：这一题有没有可比对的样例。 */
    @JvmStatic
    fun samplesOf(pid: String, t: CompareTarget): SampleComparePanel.Block? {
        val found: SampleSetService.Found = t.samples ?: return null
        if (!found.dirExists) {
            return SampleComparePanel.Block(
                "没有样例目录",
                "还没拉过 $pid 的样例。\n\n插件认的目录是：\n    ${found.dirPath}\n\n" +
                    "里面需要形如 ${pid}_1.in / ${pid}_1.out 的成对文件（拉题时会自动生成）。\n" +
                    "点下方「去拉取」到「拉取」页生成，回来再点「重新查找样例」。",
            )
        }
        if (found.samples.isEmpty()) {
            return SampleComparePanel.Block(
                "样例目录里没有成对的 .in/.out",
                "目录存在但跑不了：\n    ${found.dirPath}\n\n" +
                    "需要同名成对的 ${pid}_N.in 与 ${pid}_N.out 才算一组（N 是编号）。" +
                    if (found.orphanInputIndexes.isNotEmpty()) {
                        "\n只有 .in 的编号：${found.orphanInputIndexes.joinToString()}"
                    } else {
                        ""
                    },
            )
        }
        return null
    }

    /** 没东西可编：项目根没有 `Pxxx.cpp`。 */
    @JvmStatic
    fun noSource(pid: String, t: CompareTarget, fallbackBase: String?): SampleComparePanel.Block? {
        val path = t.sourcePath ?: return SampleComparePanel.Block(
            "项目根没有 $pid.cpp",
            "没东西可编。\n\n我找的是项目根下的：\n    ${File(t.projectBasePath ?: fallbackBase ?: "", "$pid.cpp").path}\n\n" +
                "改过名或放进子目录的，插件不会去猜——编译哪个文件必须明确（猜错就是在编别的代码）。\n" +
                "把它放回项目根并命名为 $pid.cpp，或者在题号框里填那个文件名对应的题号。",
        )
        return null
    }

    /** 这台机器没找到 C++ 编译器。 */
    @JvmStatic
    fun noCompiler(t: CompareTarget): SampleComparePanel.Block? =
        if (t.compiler != null) null else SampleComparePanel.Block(
            "没找到编译器",
            "这台机器上没找到 C++ 编译器。\n\n插件按这些顺序找：\n" +
                "1. 设置里填的绝对路径\n2. PATH 上的 clang++ / g++ / c++\n" +
                "3. IDE 自带的 MinGW（Windows：bin/mingw/bin/g++.exe）\n\n" +
                "在 `Settings | Tools | 洛谷拉题` 里填一个编译器绝对路径，或点「换编译器…」选一次。",
        )
}
