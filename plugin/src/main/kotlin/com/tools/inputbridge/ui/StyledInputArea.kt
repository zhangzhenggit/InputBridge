package com.tools.inputbridge.ui

import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.tools.inputbridge.core.ClipboardTextMerger
import com.tools.inputbridge.core.StyledText
import com.tools.inputbridge.core.TextStyleKind
import com.tools.inputbridge.core.TextStyleRun
import java.awt.Color
import java.awt.Font
import javax.swing.JTextPane
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

/**
 * Editable surface that renders the formatting the device reported for its clipboard.
 *
 * Styling is presentation only. [plainText] is the single source InputBridge sends to
 * the device, saves as a favorite, and records in history, so the visible characters
 * stay identical to the plain-text value the device published.
 */
internal class StyledInputArea : JTextPane() {
    init {
        border = JBUI.Borders.empty(10)
        font = JBFont.create(
            UIUtil.getLabelFont(UIUtil.FontSize.NORMAL).deriveFont(JBUIScale.scale(14f)),
            false,
        )
        background = UIUtil.getTextFieldBackground()
        foreground = UIUtil.getTextFieldForeground()
        caretColor = UIUtil.getTextFieldForeground()
    }

    val plainText: String
        get() = document.getText(0, document.length)

    fun setPlainText(text: String) {
        val target = styledDocument
        target.remove(0, target.length)
        target.insertString(0, text, null)
        caretPosition = target.length
    }

    /**
     * Applies a device clipboard value, appending it on its own line unless [replace]
     * is selected. The character content follows [ClipboardTextMerger].
     */
    fun applyStyled(styled: StyledText, replace: Boolean) {
        val target = styledDocument
        if (replace) {
            target.remove(0, target.length)
        } else if (styled.text.isEmpty()) {
            return
        }
        val separator = ClipboardTextMerger.separator(plainText)
        val offset = target.length
        target.insertString(offset, separator + styled.text, null)
        applyRuns(target, offset + separator.length, styled)
        caretPosition = target.length
    }

    private fun applyRuns(target: StyledDocument, base: Int, styled: StyledText) {
        if (styled.runs.isEmpty()) return
        val foregrounds = ArrayList<TextStyleRun>()
        for (run in styled.runs) {
            if (run.start < 0 || run.start >= run.end || run.end > styled.text.length) continue
            if (run.kind == TextStyleKind.FOREGROUND) {
                foregrounds += run
                continue
            }
            val attributes = attributesFor(run) ?: continue
            target.setCharacterAttributes(base + run.start, run.end - run.start, attributes, false)
        }
        // Resolved last, so each foreground is measured against the background applied above it.
        for (run in foregrounds) {
            val color = TextStyleAttributes.opaqueColor(run.value)
            if (!TextStyleAttributes.isReadable(color, backdropAt(target, base + run.start))) continue
            val attributes = SimpleAttributeSet()
            StyleConstants.setForeground(attributes, color)
            target.setCharacterAttributes(base + run.start, run.end - run.start, attributes, false)
        }
    }

    private fun attributesFor(run: TextStyleRun): SimpleAttributeSet? {
        val attributes = SimpleAttributeSet()
        when (run.kind) {
            TextStyleKind.BOLD -> StyleConstants.setBold(attributes, true)
            TextStyleKind.ITALIC -> StyleConstants.setItalic(attributes, true)
            TextStyleKind.UNDERLINE -> StyleConstants.setUnderline(attributes, true)
            TextStyleKind.STRIKETHROUGH -> StyleConstants.setStrikeThrough(attributes, true)
            TextStyleKind.BACKGROUND ->
                StyleConstants.setBackground(attributes, TextStyleAttributes.opaqueColor(run.value))
            TextStyleKind.RELATIVE_SIZE ->
                StyleConstants.setFontSize(attributes, TextStyleAttributes.scaledFontSize(font.size, run.value))
            TextStyleKind.MONOSPACE -> StyleConstants.setFontFamily(attributes, Font.MONOSPACED)
            TextStyleKind.LINK -> {
                StyleConstants.setUnderline(attributes, true)
                StyleConstants.setForeground(attributes, LINK_COLOR)
            }
            TextStyleKind.SUPERSCRIPT -> StyleConstants.setSuperscript(attributes, true)
            TextStyleKind.SUBSCRIPT -> StyleConstants.setSubscript(attributes, true)
            TextStyleKind.FOREGROUND -> return null
        }
        return attributes
    }

    private fun backdropAt(target: StyledDocument, offset: Int): Color =
        target.getCharacterElement(offset)?.attributes?.getAttribute(StyleConstants.Background) as? Color
            ?: background
            ?: UIUtil.getTextFieldBackground()

    private companion object {
        val LINK_COLOR: Color =
            JBColor.namedColor("Link.activeForeground", JBColor(Color(0x2470B3), Color(0x589DF6)))
    }
}
