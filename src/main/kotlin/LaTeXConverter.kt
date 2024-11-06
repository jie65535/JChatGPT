package top.jie65535.mirai

import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula
import java.awt.Color
import java.awt.Insets
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.swing.JLabel


object LaTeXConverter {
    /**
     * 转换LaTeX到图片字节数组
     */
    fun convertToImage(latexString: String, format: String = "png"): ByteArray {
        val formula = TeXFormula(latexString)
        val icon = formula.TeXIconBuilder().setStyle(TeXConstants.STYLE_DISPLAY).setSize(20f).build()
        icon.insets = Insets(5, 5, 5, 5)
        val image = BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
        val g2 = image.createGraphics()
        g2.color = Color.white
        g2.fillRect(0, 0, icon.iconWidth, icon.iconHeight)
        val jl = JLabel()
        jl.setForeground(Color(0, 0, 0))
        icon.paintIcon(jl, g2, 0, 0)
        val stream = ByteArrayOutputStream()
        ImageIO.write(image, format, stream)
        return stream.toByteArray()
    }
}