package com.user.clionluogu.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Color
import javax.swing.JLabel
import javax.swing.text.JTextComponent

/**
 * 状态行的唯一着色规则。
 *
 * 以前「出错就染错误色」这一条在拉取 / 搜索 / 登录 / 提交 / 自测 五个面板各写了一遍 ——
 * 同一句判据散在多处，早晚有一处写反（1.7.2 就出过「状态栏写对、详情区判反」：文件明明存在却报找不到）。
 * 收成一个纯函数，四个面板都调它，也能在没起 IDE 的探针里直接断言。
 *
 * 规则本身：
 * - 出错 → 错误前景色，并把整句话放进 tooltip（状态行常常只显示短的一句，完整原因得能悬停看到）；
 * - 正常 → 默认前景色，tooltip 清掉（不给成功信息挂一条残留的错误说明）。
 */
object StatusRow {

    /**
     * [tooltip] 为 null 时：出错就用 [text] 当 tooltip，正常时清空。
     * 传了值（比如搜索页那种「短句进标签、长句进 tooltip」）就原样用。
     */
    @JvmStatic
    @JvmOverloads
    fun apply(label: JLabel, text: String, error: Boolean, tooltip: String? = null) {
        label.text = text
        label.foreground = if (error) UIUtil.getErrorForeground() else UIUtil.getLabelForeground()
        label.toolTipText = tooltip ?: text.takeIf { error }
    }

    /**
     * 多行文本的第一条非空行 —— 状态行只有一行高度，长说明留给 tooltip。
     * 全空白时返回单个空格：`JLabel` 空文本会把高度塌下去，布局跟着跳。
     */
    @JvmStatic
    fun firstLine(text: String): String =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: " "

    /**
     * 文本区版本的同一条规则（拉取页的结果区是 `JBTextArea`，不是标签）。
     *
     * 单独一个重载而不是让调用方各写一遍颜色判断：规则只有一份才不会再出现「一处染色一处没染」。
     */
    @JvmStatic
    @JvmOverloads
    fun apply(area: JTextComponent, text: String, error: Boolean, tooltip: String? = null) {
        area.text = text
        area.foreground = if (error) UIUtil.getErrorForeground() else UIUtil.getLabelForeground()
        area.toolTipText = tooltip ?: text.takeIf { error }
    }

    /**
     * 警告（「还能跑，但有话要说」）：比错误柔和，比默认显眼。
     *
     * 明暗两套各给一个值 —— 裸 `Color(0xE0,0x80,0x00)` 在浅色主题下偏亮、白底上发虚，
     * 这正是 1.7.3 修过的那类问题，别再犯一次。
     */
    private val WARNING = JBColor(Color(0xB2, 0x6A, 0x00), Color(0xE0, 0x80, 0x00))

    @JvmStatic
    fun warn(label: JLabel, text: String) {
        label.text = text
        label.toolTipText = text
        label.foreground = WARNING
    }

    /** 回到普通状态：文案清空、颜色复原、残留的 tooltip 一并抹掉。 */
    @JvmStatic
    fun clear(label: JLabel) {
        label.text = " "
        label.toolTipText = null
        label.foreground = UIUtil.getLabelForeground()
    }

    /** 这一句是不是错误色（探针断言用，避免反过来读 UIUtil）。 */
    @JvmStatic
    fun isErrorColor(label: JLabel): Boolean = label.foreground == UIUtil.getErrorForeground()
}
