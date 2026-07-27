package com.tools.inputbridge.ui.favorites

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.PopupChooserBuilder
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.ui.components.labels.LinkListener
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.tools.inputbridge.favorites.FavoriteAddStatus
import com.tools.inputbridge.favorites.FavoriteItem
import com.tools.inputbridge.favorites.FavoriteRules
import com.tools.inputbridge.favorites.FavoritesService
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Point
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

internal object FavoritesPopup {
    fun show(
        anchor: JComponent,
        service: FavoritesService,
        saveText: String?,
        onSave: (String) -> Unit,
        onManage: () -> Unit,
        onFavoriteSelected: (FavoriteItem) -> Unit,
    ) {
        val favorites = service.favorites()
        val addition = service.evaluateAddition(saveText.orEmpty())
        val list = JBList(favorites).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            visibleRowCount = favorites.size.coerceIn(1, MAX_VISIBLE_ROWS)
            fixedCellHeight = JBUI.scale(ROW_HEIGHT)
            fixedCellWidth = JBUI.scale(POPUP_WIDTH)
            cellRenderer = FavoriteRenderer()
            emptyText.text = "No favorites yet"
        }
        lateinit var popup: JBPopup
        val footer = createFooter(
            saveEnabled = addition.status == FavoriteAddStatus.READY,
            saveHint = addition.status.saveHint(),
            onSave = {
                addition.content?.let { text ->
                    popup.cancel()
                    onSave(text)
                }
            },
            onManage = {
                popup.cancel()
                onManage()
            },
        )
        val builder = PopupChooserBuilder(list)
        builder.setItemChosenCallback(onFavoriteSelected)
        builder.setSouthComponent(footer)
        builder.setVisibleRowCount(favorites.size.coerceIn(1, MAX_VISIBLE_ROWS))
        builder.setAutoselectOnMouseMove(true)
        builder.setAutoSelectIfEmpty(false)
        builder.setNamerForFiltering { "${it.title}\n${it.content}" }
        builder.setCancelOnClickOutside(true)
        builder.setResizable(false)
        builder.setMovable(false)
        popup = builder.createPopup()

        val popupHeight = popup.content.preferredSize.height
        popup.show(RelativePoint(anchor, Point(0, -popupHeight - JBUI.scale(POPUP_GAP))))
    }

    private fun FavoriteAddStatus.saveHint(): String =
        when (this) {
            FavoriteAddStatus.READY -> "Save the current selection or editor text"
            FavoriteAddStatus.EMPTY -> "Enter text to save"
            FavoriteAddStatus.ITEM_TOO_LARGE -> "Favorite text exceeds 256 KB"
            FavoriteAddStatus.DUPLICATE -> "This text is already saved"
            FavoriteAddStatus.COLLECTION_FULL -> "The favorites limit has been reached"
            FavoriteAddStatus.STORAGE_FULL -> "The favorites storage limit has been reached"
        }

    private fun createFooter(
        saveEnabled: Boolean,
        saveHint: String,
        onSave: () -> Unit,
        onManage: () -> Unit,
    ): JComponent {
        val save = popupAction("Save", AllIcons.Actions.AddList, onSave).apply {
            isEnabled = saveEnabled
            toolTipText = saveHint
        }
        val manage = popupAction("Manage", AllIcons.Actions.Properties, onManage)
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.compound(
                JBUI.Borders.customLineTop(JBColor.border()),
                JBUI.Borders.empty(4, 9),
            )
            add(actionContainer(FlowLayout.LEFT, save), BorderLayout.WEST)
            add(actionContainer(FlowLayout.RIGHT, manage), BorderLayout.EAST)
        }
    }

    private fun actionContainer(alignment: Int, action: JComponent): JComponent =
        JPanel(FlowLayout(alignment, 0, 0)).apply {
            isOpaque = false
            add(action)
        }

    private fun popupAction(
        text: String,
        sourceIcon: Icon,
        action: () -> Unit,
    ): LinkLabel<Void> {
        val label = LinkLabel<Void>(text, null, LinkListener { _, _ -> action() })
        val icon = IconUtil.scale(sourceIcon, label, ICON_SCALE)
        return label.apply {
            setIcon(icon)
            setPaintUnderline(false)
            setHoveringIcon(IconUtil.darker(icon, 3))
            disabledIcon = IconLoader.getDisabledIcon(icon)
            font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
            border = JBUI.Borders.empty(2, 0)
        }
    }

    private class FavoriteRenderer : ColoredListCellRenderer<FavoriteItem>() {
        override fun customizeCellRenderer(
            list: JList<out FavoriteItem>,
            value: FavoriteItem?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            border = JBUI.Borders.empty(2, 9)
            font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
            toolTipText = value?.let { FavoriteRules.preview(it.content) }
            value?.let {
                append(it.title)
            }
        }
    }

    private const val MAX_VISIBLE_ROWS = 8
    private const val POPUP_WIDTH = 320
    private const val ROW_HEIGHT = 26
    private const val POPUP_GAP = 4
    private const val ICON_SCALE = 0.875f
}
