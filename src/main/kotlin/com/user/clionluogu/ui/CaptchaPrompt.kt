package com.user.clionluogu.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.awt.Image
import javax.imageio.ImageIO
import javax.swing.Icon
import javax.swing.ImageIcon

/** 提交时的图形验证码弹窗（必要弹窗，保留）。 */
object CaptchaPrompt {

    /**
     * 在 EDT 上展示验证码图片并让用户输入；非 EDT 调用时阻塞切回 EDT。
     * 返回 null 表示用户取消。
     */
    fun promptCaptcha(project: Project, png: ByteArray): String? {
        val app = ApplicationManager.getApplication()
        var result: String? = null
        if (!app.isDispatchThread) {
            app.invokeAndWait { result = showCaptchaDialog(project, png) }
        } else {
            result = showCaptchaDialog(project, png)
        }
        return result
    }

    private fun showCaptchaDialog(project: Project, png: ByteArray): String? {
        val image: Image = ImageIO.read(png.inputStream()) ?: return null
        val icon: Icon = ImageIcon(image)
        return Messages.showInputDialog(
            "请识别并输入下方验证码（提交需要）:",
            "洛谷验证码",
            icon,
            "",
            null,
        )?.takeIf { it.isNotBlank() }
    }
}
