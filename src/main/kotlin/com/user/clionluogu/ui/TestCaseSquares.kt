package com.user.clionluogu.ui

import com.intellij.ui.ColorUtil
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.user.clionluogu.api.SubtaskResult
import com.user.clionluogu.api.TestCaseResult
import com.user.clionluogu.api.statusTextOf
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Rectangle
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingConstants

/**
 * 详情区的逐测试点可视化：每个子任务一行，行首为子任务编号与得分，
 * 其后是该子任务下各测试点的彩色正方块（底色取自 [verdictColorOf]，悬浮提示为状态/耗时/内存）。
 *
 * 方块区宽度跟随滚动视口宽度（[getScrollableTracksViewportWidth] 返回 true），
 * 因此同一子任务的测试点会随可用宽度自动换行，不再出现横向滚动条。
 *
 * 无逐测试点数据时应调用 [setSubtasks] 传入空列表：组件会隐藏自身，
 * 让详情区回退为纯文字展示，不留空网格。
 */
class TestCaseSquares : JPanel(), Scrollable {

    init {
        layout = GridBagLayout()
        isOpaque = false
        border = JBUI.Borders.empty(6, 6, 0, 6)
        isVisible = false
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        (visibleRect.height / 5).coerceAtLeast(JBUI.scale(16))

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        visibleRect.height

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = false

    /**
     * [totalScore] 为各子任务得分合计（null = 还不知道，一个字符都不显示）。
     * 拿不准时宁可不显示，也不要写个 0 分让人以为评测真给了 0 分。
     */
    fun setSubtasks(subtasks: List<SubtaskResult>, totalScore: Int? = null) {
        removeAll()
        if (subtasks.isEmpty()) {
            isVisible = false
        } else {
            subtasks.forEachIndexed { index, sub ->
                add(buildRow(sub), rowConstraints(index))
            }
            if (totalScore != null) {
                add(
                    JBLabel("各子任务得分合计 $totalScore 分"),
                    rowConstraints(subtasks.size),
                )
            }
            isVisible = true
        }
        revalidate()
        repaint()
    }

    private fun rowConstraints(index: Int): GridBagConstraints = GridBagConstraints().apply {
        gridx = 0
        gridy = index
        weightx = 1.0
        fill = GridBagConstraints.HORIZONTAL
        anchor = GridBagConstraints.NORTHWEST
        insets = Insets(0, 0, JBUI.scale(4), 0)
    }

    private fun buildRow(sub: SubtaskResult): JPanel {
        val row = JPanel(BorderLayout(JBUI.scale(8), 0))
        row.isOpaque = false
        row.add(JBLabel("子任务 #${sub.id + 1}  ${sub.score ?: 0} 分"), BorderLayout.WEST)

        val squares = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4)))
        squares.isOpaque = false
        sub.testCases.forEach { squares.add(buildSquare(it)) }
        row.add(squares, BorderLayout.CENTER)
        return row
    }

    private fun buildSquare(tc: TestCaseResult): JLabel {
        val bg = verdictColorOf(tc.status)
        val square = JLabel("#${tc.id + 1}", SwingConstants.CENTER)
        square.isOpaque = true
        square.background = bg
        // 亮度判断交给平台工具（ColorUtil 用的是 WCAG 那套相对亮度，不是手搓加权和）
        square.foreground = if (ColorUtil.isDark(bg)) Color.WHITE else Color.BLACK
        square.font = square.font.deriveFont(square.font.size2D - 2f)
        square.preferredSize = Dimension(JBUI.scale(22), JBUI.scale(22))
        square.toolTipText = tooltipOf(tc)
        return square
    }

    private fun tooltipOf(tc: TestCaseResult): String {
        val sb = StringBuilder("#${tc.id + 1}  ${statusTextOf(tc.status)}")
        tc.timeMs?.let { sb.append(" · ").append(it).append(" ms") }
        tc.memoryKb?.let { sb.append(" · ").append(it).append(" KB") }
        return sb.toString()
    }
}
