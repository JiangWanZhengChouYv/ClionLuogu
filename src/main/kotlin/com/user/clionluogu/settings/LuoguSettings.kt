package com.user.clionluogu.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.application.ApplicationManager
import com.user.clionluogu.service.CompilerService

/**
 * 应用级持久化设置：生成 .cpp 用的 C++ 代码模板，以及洛谷打卡提醒的开关与「今天是否已提醒」。
 */
@State(name = "ClionLuoguSettings", storages = [Storage("clionluogu.xml")])
@Service
class LuoguSettings : PersistentStateComponent<LuoguSettings> {

    companion object {
        @Volatile
        private var instance: LuoguSettings? = null

        fun getInstance(): LuoguSettings =
            instance ?: synchronized(this) {
                instance ?: ApplicationManager.getApplication()
                    .getService(LuoguSettings::class.java).also { instance = it }
            }

        /**
         * 内置默认 C++ 竞赛模板（**clang / macOS 那一套**）。
         *
         * 不用 `#include <bits/stdc++.h>`：那是 GNU/libstdc++ 专有的，macOS 的 clang + libc++
         * 直接 `file not found`（实测：`fatal error: 'bits/stdc++.h' file not found`）。
         * 只留最常用的三个头，用到别的自己在设置里加。
         */
        const val DEFAULT_CODE_TEMPLATE: String = (
            "#include <iostream>\n" +
                "#include <vector>\n" +
                "#include <algorithm>\n" +
                "using namespace std;\n" +
                "\n" +
                "int main() {\n" +
                "    ios::sync_with_stdio(false);\n" +
                "    cin.tie(nullptr);\n" +
                "\n" +
                "    return 0;\n" +
                "}"
            )

        /** GCC 那套（Linux、Windows 的 MinGW，以及 mac 上装了 brew gcc 并把 CLion 指过去之后）：一个头全包。 */
        const val BITS_CODE_TEMPLATE: String = (
            "#include <bits/stdc++.h>\n" +
                "using namespace std;\n" +
                "\n" +
                "int main() {\n" +
                "    ios::sync_with_stdio(false);\n" +
                "    cin.tie(nullptr);\n" +
                "\n" +
                "    return 0;\n" +
                "}"
            )

        /**
         * 默认模板按**编译器**给：mac 上是 clang 就留真实标准头，否则用 bits。
         *
         * 判据里带上 mac 是因为「不是 clang」这件事在别处没什么好挑的：Linux / MinGW 的 GCC 就是
         * 评测机那一套，bits 正是他要的。而 `UNKNOWN`（`--version` 没跑通）在 mac 上退回标准头 ——
         * **两个方向错的成本不一样**：标准头在 GCC 上照样编得过，bits 在 clang 上第一行就炸。
         */
        @JvmStatic
        fun defaultCodeTemplate(isMac: Boolean, flavor: CompilerService.Flavor): String =
            if (isMac && flavor != CompilerService.Flavor.GCC) DEFAULT_CODE_TEMPLATE else BITS_CODE_TEMPLATE

        /** 对拍时默认用的编译参数；留空即取这个。 */
        const val DEFAULT_COMPILER_ARGS: String = "-std=c++17 -O2 -w"
    }

    /** 用户自定义模板；为 null/空表示用内置默认（默认本身按编译器给，见 [codeTemplate]）。 */
    private var template: String? = null

    /** 他有没有自己改过模板：改过就一字不改地用他那份，不按编译器换头文件。 */
    val hasCustomCodeTemplate: Boolean
        get() = !template.isNullOrBlank()

    /**
     * 拉题用的模板，**也是设置页显示的那一份** —— 两处必须同一个来源，
     * 否则设置里写着标准头、生成的文件里是 bits（或者反过来），isModified 还会误判。
     *
     * 没自定义时按当前编译器给（[CompilerService.lastFlavor] 只是读一个 volatile 字段，
     * 不起进程，所以 EDT 上调用安全；还没探测过就是 [CompilerService.Flavor.UNKNOWN]，退回标准头）。
     */
    var codeTemplate: String
        get() = if (hasCustomCodeTemplate) template!!
        else defaultCodeTemplate(CompilerService.isMac(), CompilerService.lastFlavor)
        /**
         * 存下来；但**内容等于任一份内置默认时不算「自定义」**（记成 null）。
         *
         * 不这么处理会有一种很难查的锁死：设置页打开时显示的是当时那套默认（比如标准头），
         * 期间后台探测把 [CompilerService.lastFlavor] 换成了 GCC → `isModified()` 以为他改过 →
         * 他顺手点 Apply → 那份「旧默认」被当成自定义模板存下来，从此编译器再怎么变都不换头文件了。
         */
        set(value) {
            template = value.takeUnless { it == DEFAULT_CODE_TEMPLATE || it == BITS_CODE_TEMPLATE }
        }

    /** 未显式设置时默认开启启动打卡提醒。 */
    private var punchReminder: Boolean? = null

    private var punchDay: String? = null

    var punchReminderEnabled: Boolean
        get() = punchReminder ?: true
        set(value) {
            punchReminder = value
        }

    /**
     * 本地记住「今天已经提醒过 / 已打卡」的日期（ISO `yyyy-MM-dd`）。
     *
     * 洛谷没有查询打卡状态的接口（状态只体现在首页服务端渲染的 HTML 里），靠这个字段避免
     * 每天重复请求首页、重复弹通知。
     */
    var lastPunchDate: String?
        get() = punchDay
        set(value) {
            punchDay = value
        }

    /** 未显式设置时默认在 AC 后询问是否清理本题文件。 */
    private var acCleanup: Boolean? = null

    var acCleanupEnabled: Boolean
        get() = acCleanup ?: true
        set(value) {
            acCleanup = value
        }

    /** 未显式设置时默认在评测判成非 AC 终态时发通知。 */
    private var judgeNotify: Boolean? = null

    var judgeNotifyEnabled: Boolean
        get() = judgeNotify ?: true
        set(value) {
            judgeNotify = value
        }

    /**
     * 我们有没有替他打开过 IDE 的「宽屏工具窗口布局」（见
     * [com.user.clionluogu.ui.WideLayout]）。记一次就够：
     * 他之后自己在 Appearance 里关掉，插件不该再打开回来 —— 那是跟用户抢方向盘。
     */
    private var wideScreenAdopted: Boolean? = null

    var wideScreenLayoutAdopted: Boolean
        get() = wideScreenAdopted ?: false
        set(value) {
            wideScreenAdopted = value
        }

    /**
     * 对拍用的编译器**绝对路径**。留空 = 让插件按 PATH 找 `clang++`/`g++`，
     * 再退到 IDE 发行包里自带的 MinGW（Windows）。编译器是机器级的事，所以放应用级设置。
     */
    var compareCompilerPath: String? = null

    private var compilerArgs: String? = null

    /** 编译参数；留空取 [DEFAULT_COMPILER_ARGS]。 */
    var compareCompilerArgs: String
        get() = compilerArgs?.takeIf { it.isNotBlank() } ?: DEFAULT_COMPILER_ARGS
        set(value) {
            compilerArgs = value
        }

    /**
     * [compareCompilerArgs] 拆成的参数列表。
     *
     * 对拍与自测共用这一条拆分规则（空格分隔、丢空串）——两处各写一遍 `split(' ')`
     * 的话，改天一边支持引号、另一边不支持，就会出现「同一个设置，两边编出来的东西不一样」。
     */
    fun compareCompilerArgList(): List<String> = compareCompilerArgs.split(' ').filter { it.isNotBlank() }

    /**
     * 「装 GCC」那条建议最后一次是针对**哪个编译器路径**发出去的。
     *
     * 按路径去重而不是记日期：他把 CLion 的编译器从 clang 换成 brew 的 g++-16 之后这件事就不成立了，
     * 不该再弹；而万一他换回 clang（或 brew 升了版本号），那是**新情况**，值得再提醒一次。
     */
    private var gccAdvicePath: String? = null

    var gccAdviceShownFor: String?
        get() = gccAdvicePath
        set(value) {
            gccAdvicePath = value
        }

    override fun getState(): LuoguSettings = this

    /**
     * 读回来的时候**每个持久化字段都要在这里镜像一遍** —— 少一行就是「重启之后设置丢了」。
     *
     * 1.8.1 的 `wideScreenAdopted` 就漏在这一环：写出去的 XML 是对的
     * （`options/clionluogu.xml` 里能看到 `wideScreenLayoutAdopted=true`），
     * 但读回来时没 copy，于是重启后变成 false。当时看不出来是因为 IDE 自己把宽屏布局也持久化了
     * （`options/ui.lnf.xml` 里的 `WIDESCREEN_SUPPORT`），判据 `!currentlyWidescreen` 恰好还是 false；
     * 可只要他**自己关掉宽屏布局**，重启后插件就会再打开一次 —— 正是那段代码承诺过不干的事。
     * 现在由 CoreProbe 的 `settingsRoundTrip` 逐个字段断言（写出去 + 读回来必须还一样）。
     */
    override fun loadState(state: LuoguSettings) {
        this.template = state.template
        this.punchReminder = state.punchReminder
        this.punchDay = state.punchDay
        this.acCleanup = state.acCleanup
        this.judgeNotify = state.judgeNotify
        this.wideScreenAdopted = state.wideScreenAdopted
        this.gccAdvicePath = state.gccAdvicePath
        this.compareCompilerPath = state.compareCompilerPath
        this.compilerArgs = state.compilerArgs
    }
}