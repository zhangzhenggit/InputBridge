package com.tools.inputbridge.ui.history

import com.intellij.openapi.ui.popup.PopupChooserBuilder
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.tools.inputbridge.history.InputHistoryItem
import com.tools.inputbridge.history.InputHistoryRules
import java.awt.Point
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel

internal object InputHistoryPopup {
    fun show(
        anchor: JComponent,
        items: List<InputHistoryItem>,
        onItemSelected: (InputHistoryItem) -> Unit,
    ) {
        val list = JBList(items).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            visibleRowCount = items.size.coerceIn(1, MAX_VISIBLE_ROWS)
            fixedCellHeight = JBUI.scale(ROW_HEIGHT)
            fixedCellWidth = JBUI.scale(POPUP_WIDTH)
            cellRenderer = HistoryRenderer()
            emptyText.text = "No input history yet"
        }
        val popup = PopupChooserBuilder(list)
            .setItemChosenCallback(onItemSelected)
            .setVisibleRowCount(items.size.coerceIn(1, MAX_VISIBLE_ROWS))
            .setAutoselectOnMouseMove(true)
            .setAutoSelectIfEmpty(false)
            .setNamerForFiltering(InputHistoryItem::content)
            .setCancelOnClickOutside(true)
            .setResizable(false)
            .setMovable(false)
            .createPopup()

        val popupHeight = popup.content.preferredSize.height
        popup.show(RelativePoint(anchor, Point(0, -popupHeight - JBUI.scale(POPUP_GAP))))
    }

    private class HistoryRenderer : ColoredListCellRenderer<InputHistoryItem>() {
        override fun customizeCellRenderer(
            list: JList<out InputHistoryItem>,
            value: InputHistoryItem?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            border = JBUI.Borders.empty(2, 9)
            font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
            append(value?.let { InputHistoryRules.preview(it.content) }.orEmpty())
        }
    }

    private const val MAX_VISIBLE_ROWS = 8
    private const val POPUP_WIDTH = 320
    private const val ROW_HEIGHT = 26
    private const val POPUP_GAP = 4
}
