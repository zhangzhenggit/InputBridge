package com.tools.inputbridge.ui.favorites

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.tools.inputbridge.favorites.FavoriteItem
import com.tools.inputbridge.favorites.FavoriteRules
import com.tools.inputbridge.favorites.FavoriteValidationException
import com.tools.inputbridge.favorites.FavoritesJsonCodec
import com.tools.inputbridge.favorites.FavoritesService
import com.tools.inputbridge.favorites.FavoritesUpdateStatus
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.UUID
import javax.swing.Action
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal class FavoritesManagerDialog(
    private val project: Project,
    private val service: FavoritesService,
) : DialogWrapper(project, true) {
    private val initialSnapshot = service.snapshot()
    private val drafts = initialSnapshot.favorites.mapTo(mutableListOf(), Draft::fromFavorite)
    private val listModel = DefaultListModel<Draft>()
    private val favoritesList = JBList(listModel)
    private val searchField = SearchTextField(false)
    private val contentArea = JBTextArea()
    private var updatingContent = false

    init {
        title = "Manage favorites"
        setOKButtonText("Save")
        configureComponents()
        rebuildList(drafts.firstOrNull()?.id)
        init()
    }

    override fun createCenterPanel(): JComponent {
        val listPanel = ToolbarDecorator.createDecorator(favoritesList)
            .setAddAction { addFavorite() }
            .setRemoveAction { removeSelected() }
            .setMoveUpAction { moveSelected(-1) }
            .setMoveDownAction { moveSelected(1) }
            .setMoveUpActionUpdater { canMove(-1) }
            .setMoveDownActionUpdater { canMove(1) }
            .setPanelBorder(JBUI.Borders.customLine(com.intellij.ui.JBColor.border()))
            .createPanel()
        val left = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            border = JBUI.Borders.emptyRight(8)
            add(searchField, BorderLayout.NORTH)
            add(listPanel, BorderLayout.CENTER)
        }
        val right = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            border = JBUI.Borders.emptyLeft(8)
            add(JBLabel("Content"), BorderLayout.NORTH)
            add(JBScrollPane(contentArea), BorderLayout.CENTER)
        }
        return JBSplitter(false, 0.34f).apply {
            preferredSize = Dimension(JBUI.scale(800), JBUI.scale(460))
            firstComponent = left
            secondComponent = right
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = searchField

    override fun createLeftSideActions(): Array<Action> = arrayOf(
        object : DialogWrapperAction("Import…") {
            override fun doAction(event: ActionEvent) = importFavorites()
        },
        object : DialogWrapperAction("Export…") {
            override fun doAction(event: ActionEvent) = exportFavorites()
        },
    )

    override fun doOKAction() {
        try {
            when (service.replaceAll(drafts.map(Draft::toFavorite), initialSnapshot.revision)) {
                FavoritesUpdateStatus.UPDATED -> super.doOKAction()
                FavoritesUpdateStatus.STALE -> Messages.showErrorDialog(
                    contentPanel,
                    "Favorites changed in another window. Reopen the manager to edit the latest list.",
                    "Favorites changed",
                )
            }
        } catch (error: FavoriteValidationException) {
            Messages.showErrorDialog(contentPanel, error.message, "Unable to save favorites")
        }
    }

    private fun configureComponents() {
        searchField.textEditor.emptyText.text = "Search favorites"
        searchField.addDocumentListener(documentListener { rebuildList(favoritesList.selectedValue?.id) })
        favoritesList.cellRenderer = DraftRenderer()
        favoritesList.addListSelectionListener {
            if (!it.valueIsAdjusting) loadSelected()
        }
        contentArea.apply {
            lineWrap = true
            wrapStyleWord = true
            font = JBFont.create(
                UIUtil.getLabelFont(UIUtil.FontSize.NORMAL).deriveFont(JBUIScale.scale(14f)),
                false,
            )
            document.addDocumentListener(documentListener(::updateSelectedDraft))
        }
    }

    private fun addFavorite() {
        val now = System.currentTimeMillis()
        val draft = Draft(
            id = UUID.randomUUID().toString(),
            content = "",
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        drafts += draft
        searchField.text = ""
        rebuildList(draft.id)
        contentArea.requestFocusInWindow()
    }

    private fun removeSelected() {
        val selected = favoritesList.selectedValue ?: return
        val index = drafts.indexOfFirst { it.id == selected.id }
        if (index < 0) return
        drafts.removeAt(index)
        rebuildList(drafts.getOrNull(index.coerceAtMost(drafts.lastIndex))?.id)
    }

    private fun moveSelected(offset: Int) {
        if (!canMove(offset)) return
        val selected = favoritesList.selectedValue ?: return
        val index = drafts.indexOfFirst { it.id == selected.id }
        val moved = drafts.removeAt(index)
        drafts.add(index + offset, moved)
        rebuildList(moved.id)
    }

    private fun canMove(offset: Int): Boolean {
        if (searchField.text.isNotBlank()) return false
        val selected = favoritesList.selectedValue ?: return false
        val index = drafts.indexOfFirst { it.id == selected.id }
        return index >= 0 && index + offset in drafts.indices
    }

    private fun rebuildList(preferredId: String?) {
        val query = searchField.text.trim()
        val visible = drafts.filter { query.isEmpty() || it.content.contains(query, ignoreCase = true) }
        listModel.clear()
        visible.forEach(listModel::addElement)
        favoritesList.selectedIndex = visible.indexOfFirst { it.id == preferredId }
            .takeIf { it >= 0 }
            ?: visible.indices.firstOrNull()
            ?: -1
        if (favoritesList.selectedIndex < 0) loadSelected()
    }

    private fun loadSelected() {
        updatingContent = true
        val selected = favoritesList.selectedValue
        contentArea.text = selected?.content.orEmpty()
        contentArea.isEnabled = selected != null
        updatingContent = false
    }

    private fun updateSelectedDraft() {
        if (updatingContent) return
        val selected = favoritesList.selectedValue ?: return
        selected.content = contentArea.text
        favoritesList.repaint()
    }

    private fun importFavorites() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
            .withTitle("Import InputBridge favorites")
            .withDescription("Select an InputBridge favorites JSON file")
        val selected = FileChooser.chooseFile(descriptor, project, null) ?: return
        try {
            if (selected.length > FavoritesJsonCodec.MAX_IMPORT_FILE_BYTES) {
                throw FavoriteValidationException("The selected file exceeds the supported import limit")
            }
            val current = drafts.map(Draft::toFavorite)
            val result = runWithProgress("Importing favorites") {
                val json = String(selected.contentsToByteArray(), StandardCharsets.UTF_8)
                FavoritesJsonCodec.merge(current, FavoritesJsonCodec.decode(json))
            }
            drafts.clear()
            drafts += result.favorites.map(Draft::fromFavorite)
            searchField.text = ""
            rebuildList(drafts.lastOrNull()?.id)
            Messages.showInfoMessage(
                contentPanel,
                "Added ${result.added} favorite(s) and skipped ${result.skipped} duplicate(s). " +
                    "Select Save to keep the changes.",
                "Favorites imported",
            )
        } catch (error: Exception) {
            val message = if (error is FavoriteValidationException) error.message else "Unable to read the selected file"
            Messages.showErrorDialog(contentPanel, message, "Unable to import favorites")
        }
    }

    private fun exportFavorites() {
        try {
            val current = drafts.map(Draft::toFavorite)
            val json = runWithProgress("Preparing favorites export") {
                FavoritesJsonCodec.encode(current)
            }
            val descriptor = FileSaverDescriptor(
                "Export InputBridge favorites",
                "Choose where to save the favorites backup",
                "json",
            )
            val target = FileChooserFactory.getInstance()
                .createSaveFileDialog(descriptor, contentPanel)
                .save("InputBridgeFavorites.json")
                ?: return
            runWithProgress("Exporting favorites") {
                Files.writeString(target.file.toPath(), json, StandardCharsets.UTF_8)
            }
        } catch (error: Exception) {
            val message = if (error is FavoriteValidationException) error.message else "Unable to write the selected file"
            Messages.showErrorDialog(contentPanel, message, "Unable to export favorites")
        }
    }

    private fun <T> runWithProgress(title: String, operation: () -> T): T =
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            ThrowableComputable<T, Exception> { operation() },
            title,
            false,
            project,
        )

    private fun documentListener(onChange: () -> Unit): DocumentListener = object : DocumentListener {
        override fun insertUpdate(event: DocumentEvent) = onChange()

        override fun removeUpdate(event: DocumentEvent) = onChange()

        override fun changedUpdate(event: DocumentEvent) = onChange()
    }

    private class DraftRenderer : ColoredListCellRenderer<Draft>() {
        override fun customizeCellRenderer(
            list: JList<out Draft>,
            value: Draft?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            border = JBUI.Borders.empty(4, 8)
            append(value?.let { FavoriteRules.preview(it.content) }.orEmpty())
        }
    }

    private data class Draft(
        val id: String,
        var content: String,
        val createdAtMillis: Long,
        val updatedAtMillis: Long,
    ) {
        fun toFavorite(): FavoriteItem = FavoriteItem(id, content, createdAtMillis, updatedAtMillis)

        companion object {
            fun fromFavorite(favorite: FavoriteItem): Draft = Draft(
                favorite.id,
                favorite.content,
                favorite.createdAtMillis,
                favorite.updatedAtMillis,
            )
        }
    }
}
