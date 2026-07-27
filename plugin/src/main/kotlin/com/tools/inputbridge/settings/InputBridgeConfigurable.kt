package com.tools.inputbridge.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.tools.inputbridge.history.InputHistoryRules
import com.tools.inputbridge.history.InputHistoryService
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

class InputBridgeConfigurable : Configurable {
    private val historyService: InputHistoryService
        get() = ApplicationManager.getApplication().service()

    private var component: JComponent? = null
    private var recordHistoryCheckBox: JBCheckBox? = null
    private var maxEntriesComboBox: JComboBox<Int>? = null
    private var storedEntriesLabel: JBLabel? = null
    private var clearHistoryButton: JButton? = null

    override fun getDisplayName(): String = "InputBridge"

    override fun createComponent(): JComponent {
        val recordHistory = JBCheckBox("Record successfully sent text")
        val maxEntries = JComboBox(InputHistoryRules.ENTRY_LIMIT_OPTIONS)
        val entryCount = JBLabel()
        val clearButton = JButton("Clear history").apply {
            addActionListener { clearHistory() }
        }
        val storedEntries = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(entryCount)
            add(clearButton)
        }
        val root = FormBuilder.createFormBuilder()
            .addComponent(TitledSeparator("History"))
            .addComponent(recordHistory)
            .addLabeledComponent("Maximum entries:", maxEntries)
            .addLabeledComponent("Stored entries:", storedEntries)
            .addComponent(
                JBLabel("History is stored locally in plain text. Avoid sending passwords, tokens, or private keys."),
            )
            .addComponentFillVertically(JPanel(), 0)
            .panel

        recordHistoryCheckBox = recordHistory
        maxEntriesComboBox = maxEntries
        storedEntriesLabel = entryCount
        clearHistoryButton = clearButton
        component = root
        reset()
        return root
    }

    override fun isModified(): Boolean {
        val recordHistory = recordHistoryCheckBox ?: return false
        val maxEntries = maxEntriesComboBox ?: return false
        val preferences = historyService.preferences()
        return recordHistory.isSelected != preferences.enabled ||
            maxEntries.selectedItem != preferences.maxEntries
    }

    override fun apply() {
        val enabled = recordHistoryCheckBox?.isSelected ?: return
        val maxEntries = maxEntriesComboBox?.selectedItem as? Int ?: return
        historyService.updatePreferences(enabled, maxEntries)
        updateEntryCount()
    }

    override fun reset() {
        val preferences = historyService.preferences()
        recordHistoryCheckBox?.isSelected = preferences.enabled
        maxEntriesComboBox?.selectedItem = preferences.maxEntries
        updateEntryCount()
    }

    override fun disposeUIResources() {
        component = null
        recordHistoryCheckBox = null
        maxEntriesComboBox = null
        storedEntriesLabel = null
        clearHistoryButton = null
    }

    private fun clearHistory() {
        if (historyService.size() == 0) return
        val parent = component ?: return
        val confirmed = Messages.showYesNoDialog(
            parent,
            "Clear all InputBridge input history?",
            "Clear input history",
            Messages.getQuestionIcon(),
        ) == Messages.YES
        if (confirmed) {
            historyService.clear()
            updateEntryCount()
        }
    }

    private fun updateEntryCount() {
        val size = historyService.size()
        storedEntriesLabel?.text = size.toString()
        clearHistoryButton?.isEnabled = size > 0
    }
}
