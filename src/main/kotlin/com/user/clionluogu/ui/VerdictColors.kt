package com.user.clionluogu.ui

import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/** 一档判定的两套色值（浅色主题用 [light]，深色主题用 [dark]）。 */
data class VerdictPalette(val light: Color, val dark: Color)

/**
 * 判定 → 颜色。**每一档都分明暗两套给值**。
 *
 * 以前这里是 13 个裸 `java.awt.Color`，值是从洛谷抄的深色主题那一套：切到浅色主题时
 * `#001277`、`#0e1d69`、`#262626` 和白色列表背景糊在一起；反过来在深色主题下它们同样几乎看不见
 * （对比度 1.1~1.8，探针量出来的）—— 也就是说**两套主题下都有一部分方块是废的**。
 *
 * 表在这里、不在 `when` 里散着，是为了给探针一个取值口子（[verdictPalette]）：
 * JBColor 到底解析成哪一套取决于运行时主题，探针拿不到那个上下文，只能把两套分别验。
 */
private val PALETTES: Map<Int?, VerdictPalette> = mapOf(
    12 to VerdictPalette(Color(56, 158, 13), Color(82, 196, 26)),           // AC 绿
    21 to VerdictPalette(Color(56, 158, 13), Color(82, 196, 26)),           // Hack 成功
    6 to VerdictPalette(Color(196, 42, 30), Color(0xFB, 0x63, 0x40)),       // WA 红
    14 to VerdictPalette(Color(186, 44, 34), Color(231, 76, 60)),           // 未通过
    22 to VerdictPalette(Color(186, 44, 34), Color(231, 76, 60)),           // Hack 失败
    3 to VerdictPalette(Color(21, 66, 138), Color(74, 134, 199)),           // OLE
    4 to VerdictPalette(Color(21, 66, 138), Color(74, 134, 199)),           // MLE
    5 to VerdictPalette(Color(21, 66, 138), Color(74, 134, 199)),           // TLE
    23 to VerdictPalette(Color(21, 66, 138), Color(74, 134, 199)),          // Hack 跳过
    7 to VerdictPalette(Color(122, 42, 153), Color(163, 94, 196)),          // RE 紫
    2 to VerdictPalette(Color(196, 158, 8), Color(250, 219, 20)),           // CE 黄
    0 to VerdictPalette(Color(20, 78, 132), Color(61, 123, 184)),           // 等待
    1 to VerdictPalette(Color(38, 116, 172), Color(52, 152, 219)),          // 评测中
    11 to VerdictPalette(Color(28, 46, 116), Color(108, 123, 196)),         // 系统错误
    -1 to VerdictPalette(Color(112, 112, 112), Color(138, 138, 138)),       // 未显示
)

private val DEFAULT_PALETTE = VerdictPalette(Color(150, 150, 150), Color(158, 158, 158))

fun verdictColorOf(statusCode: Int?): Color {
    val palette = verdictPalette(statusCode)
    return JBColor(palette.light, palette.dark)
}

/** 某一档的两套色值（未知码返回灰色那套）。探针按这两套分别验对比度。 */
fun verdictPalette(statusCode: Int?): VerdictPalette = PALETTES[statusCode] ?: DEFAULT_PALETTE

/**
 * 列表与方块区共用的判定标记。
 *
 * 圆角 + 一圈深色描边（原来是生硬的 `fillRect` 方块）；[hollow] 用于「还没有结果」的行
 * （等待中、未提交、待对拍）——只描边不填色，避免灰实心块看起来像一个真的判定。
 */
class VerdictSquareIcon(
    private val color: Color,
    private val size: Int = 10,
    private val hollow: Boolean = false,
) : Icon {

    override fun getIconWidth(): Int = size

    override fun getIconHeight(): Int = size

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val previous = g.color
        val g2 = g as? Graphics2D
        g2?.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val arc = size / 3
        if (hollow) {
            g2?.stroke = BasicStroke(1f)
            g.color = color
            g2?.drawRoundRect(x, y, size - 1, size - 1, arc, arc)
        } else {
            g.color = color
            g.fillRoundRect(x, y, size, size, arc, arc)
            g.color = ColorUtil.darker(color, 16)
            g2?.stroke = BasicStroke(1f)
            g2?.drawRoundRect(x, y, size - 1, size - 1, arc, arc)
        }
        g.color = previous
    }
}
