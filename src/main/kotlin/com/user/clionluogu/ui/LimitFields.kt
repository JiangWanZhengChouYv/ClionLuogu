package com.user.clionluogu.ui

import javax.swing.text.JTextComponent

/**
 * 时空上限那两个输入框的共用规则（对拍页与自测页一字不差地相同，所以放一处）。
 *
 * 两条都是被他抓出来的：
 * - **「改了没用」**：原来只在**失焦**时标记「他改过」。但他在字段里打字的同时，编辑器那边存一次盘
 *   就改了磁盘签名 → 每秒那次自动重探回来 → 用题面值把字段盖回去。所以**一敲字就算改过**。
 * - **「改不了」**：内存字段原本在测量器不可用时直接禁用。一个灰掉的输入框读起来像「插件坏了」，
 *   而现在测量器的探测已经修好（`/bin/true` 在他机器上不存在那件事）。
 *   与其禁掉，不如让他填、并在结果里明说这一栏**没生效**。
 */
object LimitFields {

    /** 该不该用题面限制覆盖这两个字段：他一动过就不动。 */
    @JvmStatic
    fun shouldFillFromProblem(touched: Boolean): Boolean = !touched

    /** 装「他一敲字就算改过」的监听。 */
    @JvmStatic
    fun markWhenTyped(field: JTextComponent, onTouch: () -> Unit) {
        field.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = onTouch()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = onTouch()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = onTouch()
        })
    }

    /** 量不到峰值时那句人话：字段能填、值要显示，但得说清它没参与判定。 */
    @JvmStatic
    fun unenforcedText(memoryMb: Int): String = "$memoryMb MB（这台机器量不到子进程内存，不生效）"
}
