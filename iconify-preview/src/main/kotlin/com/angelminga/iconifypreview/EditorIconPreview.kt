package com.angelminga.iconifypreview

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.Alarm
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Point
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Icon previews for one editor: scans the document for icon names, keeps one inlay per icon found, shows a large
 * preview on hover and opens the icon's Iconify page on click.
 */
internal class EditorIconPreview(private val editor: Editor) : Disposable, EditorMouseListener, EditorMouseMotionListener {

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var popup: JBPopup? = null
    private var popupInlay: Inlay<*>? = null

    init {
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = scheduleUpdate(TYPING_DELAY_MS)
        }, this)
        editor.addEditorMouseListener(this, this)
        editor.addEditorMouseMotionListener(this, this)
        ApplicationManager.getApplication().messageBus.connect(this).apply {
            subscribe(IconifyClient.TOPIC, IconifyListener { scheduleUpdate(LOADED_DELAY_MS) })
            subscribe(EditorColorsManager.TOPIC, EditorColorsListener { scheduleUpdate(0) })
        }
    }

    fun scheduleUpdate(delayMs: Int) {
        alarm.cancelAllRequests()
        alarm.addRequest(::update, delayMs)
    }

    private fun update() {
        if (editor.isDisposed) return
        val text = editor.document.immutableCharSequence
        val wanted = HashMap<Int, IconInlayRenderer>()
        if (IconifyPreviewSettings.getInstance().state.enabled && text.length <= MAX_DOCUMENT_LENGTH) {
            val client = IconifyClient.getInstance()
            val color = editor.colorsScheme.defaultForeground
            for (match in IconNameScanner.find(text)) {
                val image = client.icon(match, color) ?: continue
                wanted[match.offset] = IconInlayRenderer(match, image)
            }
        }

        // Keep inlays that still show the right icon at the right place, so the text doesn't jump while typing.
        for (inlay in editor.inlayModel.getInlineElementsInRange(0, text.length, IconInlayRenderer::class.java)) {
            val renderer = inlay.renderer
            if (wanted[inlay.offset]?.image === renderer.image) {
                wanted.remove(inlay.offset)
                if (renderer.isStale(editor)) inlay.update()
            } else {
                Disposer.dispose(inlay)
            }
        }
        for ((offset, renderer) in wanted) {
            editor.inlayModel.addInlineElement(offset, false, renderer)
        }
    }

    override fun mouseClicked(event: EditorMouseEvent) {
        val renderer = event.inlay?.renderer as? IconInlayRenderer ?: return
        hidePopup()
        BrowserUtil.browse(IconifyClient.pageUrl(renderer.match))
        event.consume()
    }

    override fun mouseMoved(event: EditorMouseEvent) {
        val inlay = event.inlay?.takeIf { it.renderer is IconInlayRenderer }
        if (inlay === popupInlay) return
        hidePopup()
        if (inlay != null) showPopup(inlay)
    }

    override fun mouseExited(event: EditorMouseEvent) = hidePopup()

    private fun showPopup(inlay: Inlay<*>) {
        val renderer = inlay.renderer as IconInlayRenderer
        val bounds = inlay.bounds ?: return
        val image = renderer.image
        val large = IconUtil.scale(image, editor.contentComponent, JBUI.scale(PREVIEW_SIZE).toFloat() / image.iconHeight.coerceAtLeast(1))
        val panel = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(10)
            add(JBLabel(large).apply { horizontalAlignment = SwingConstants.CENTER }, BorderLayout.CENTER)
            add(JBLabel(renderer.match.id).apply { horizontalAlignment = SwingConstants.CENTER }, BorderLayout.NORTH)
            add(JBLabel(IconifyPreviewBundle.message("preview.click.hint"), UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER).apply {
                horizontalAlignment = SwingConstants.CENTER
            }, BorderLayout.SOUTH)
        }
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, null)
            .setRequestFocus(false)
            .setFocusable(false)
            .setCancelOnClickOutside(true)
            .createPopup()
            .also { it.show(RelativePoint(editor.contentComponent, Point(bounds.x, bounds.y + bounds.height + JBUI.scale(2)))) }
        popupInlay = inlay
    }

    private fun hidePopup() {
        popup?.cancel()
        popup = null
        popupInlay = null
    }

    override fun dispose() {
        hidePopup()
        if (!editor.isDisposed) {
            editor.putUserData(KEY, null)
            editor.inlayModel.getInlineElementsInRange(0, editor.document.textLength, IconInlayRenderer::class.java)
                .forEach(Disposer::dispose)
        }
    }

    companion object {
        private val KEY = Key.create<EditorIconPreview>("iconify.preview")

        private const val TYPING_DELAY_MS = 300
        private const val LOADED_DELAY_MS = 100
        private const val PREVIEW_SIZE = 64

        // Bigger files are usually generated or minified; scanning them on every keystroke isn't worth it.
        private const val MAX_DOCUMENT_LENGTH = 1_000_000

        fun attach(editor: Editor) {
            if (editor.isDisposed || editor.editorKind != EditorKind.MAIN_EDITOR || editor.getUserData(KEY) != null) return
            val preview = EditorIconPreview(editor)
            editor.putUserData(KEY, preview)
            Disposer.register(IconifyClient.getInstance(), preview)
            preview.scheduleUpdate(0)
        }

        fun detach(editor: Editor) {
            editor.getUserData(KEY)?.let(Disposer::dispose)
        }

        /** For editors opened before the plugin was installed or enabled. */
        fun attachAll() = EditorFactory.getInstance().allEditors.forEach(::attach)
    }
}
