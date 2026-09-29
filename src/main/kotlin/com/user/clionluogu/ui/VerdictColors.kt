package com.user.clionluogu.ui

import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import javax.swing.Icon

fun verdictColorOf(statusCode: Int?): Color = when (statusCode) {
    12 -> Color(82, 196, 26)
    6 -> Color(0xfb, 0x63, 0x40)
    3, 4, 5 -> Color(0x00, 0x12, 0x77)
    7 -> Color(0x8e, 0x44, 0xad)
    2 -> Color(250, 219, 20)
    14 -> Color(231, 76, 60)
    0 -> Color(20, 85, 143)
    1 -> Color(52, 152, 219)
    11 -> Color(14, 29, 105)
    -1 -> Color(38, 38, 38)
    21 -> Color(82, 196, 26)
    22 -> Color(231, 76, 60)
    23 -> Color(0x00, 0x12, 0x77)
    else -> Color(0x9E, 0x9E, 0x9E)
}

class VerdictSquareIcon(private val color: Color, private val size: Int = 10) : Icon {

    override fun getIconWidth(): Int = size

    override fun getIconHeight(): Int = size

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val previous = g.color
        g.color = color
        g.fillRect(x, y, size, size)
        g.color = previous
    }
}
