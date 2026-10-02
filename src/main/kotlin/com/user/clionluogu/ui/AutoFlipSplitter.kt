package com.user.clionluogu.ui

import com.intellij.ui.JBSplitter

import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

/**
 * 侧边栏工具窗口常常只有两三百像素宽：列表和详情左右一排，两边都挤到没法看。
 * 这个分栏在容器窄于 [stackBelowWidth] 时自动改成上下排，拖宽了又换回左右。
 *
 * 平台这版没有「宽度不够就换向」的开关（`Splitter.LackOfSpaceStrategy` 只剩 ratio 三档，
 * 早先那对 `hOnRightWidthValue` / `vOnTopHeightValue` 已经找不到了），所以自己挂 resize 监听。
 * `setOrientation(true)` 是上下排 —— 对照：评测页一直用的 `JBSplitter(false, 0.4f)` 呈现为左右排。
 */
class AutoFlipSplitter(
    proportion: Float,
    private val stackBelowWidth: Int = DEFAULT_STACK_BELOW_WIDTH,
) : JBSplitter(false, proportion) {

    private var stacked = false

    init {
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = applyOrientation()
        })
    }

    /** 首次布局前手动调一次也行（此时宽度还是 0，会保持左右排，等第一次 resize 事件再定）。 */
    fun applyOrientation() {
        val shouldStack = width in 1 until stackBelowWidth
        if (shouldStack == stacked) return
        stacked = shouldStack
        orientation = shouldStack
    }

    companion object {
        /** 经验值：比这窄，一行里放不下「样例 12 + 判定 + 用时」，也不剩给详情区看。 */
        const val DEFAULT_STACK_BELOW_WIDTH = 460
    }
}
