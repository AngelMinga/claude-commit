package com.angelminga.iconifypreview

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D
import javax.swing.Icon
import kotlin.math.roundToInt

/** Draws [image] right before the quoted icon name, sized to the editor's line height. */
internal class IconInlayRenderer(val match: IconMatch, val image: Icon) : EditorCustomElementRenderer {

    private var scaled: Icon? = null
    private var scaledForLineHeight = -1

    /** True after a font size change or zoom: the inlay's width must be recalculated. */
    fun isStale(editor: Editor): Boolean = scaledForLineHeight != editor.lineHeight

    fun icon(editor: Editor): Icon {
        val lineHeight = editor.lineHeight
        scaled?.takeIf { scaledForLineHeight == lineHeight }?.let { return it }
        val target = lineHeight * LINE_HEIGHT_RATIO
        return IconUtil.scale(image, editor.contentComponent, target / image.iconHeight.coerceAtLeast(1)).also {
            scaled = it
            scaledForLineHeight = lineHeight
        }
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int = icon(inlay.editor).iconWidth + JBUI.scale(GAP)

    override fun paint(inlay: Inlay<*>, g: Graphics2D, targetRegion: Rectangle2D, textAttributes: TextAttributes) {
        val icon = icon(inlay.editor)
        val y = targetRegion.y + (targetRegion.height - icon.iconHeight) / 2
        icon.paintIcon(inlay.editor.contentComponent, g, targetRegion.x.roundToInt(), y.roundToInt())
    }

    private companion object {
        const val LINE_HEIGHT_RATIO = 0.8f
        const val GAP = 2
    }
}
