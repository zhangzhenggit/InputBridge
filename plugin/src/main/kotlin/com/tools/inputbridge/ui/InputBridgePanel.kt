package com.tools.inputbridge.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.ui.components.labels.LinkListener
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.tools.inputbridge.core.ClipboardTextMerger
import com.tools.inputbridge.core.ClipboardUpdate
import com.tools.inputbridge.core.ConnectionState
import com.tools.inputbridge.core.ConnectionStatus
import com.tools.inputbridge.core.DeviceInfo
import com.tools.inputbridge.core.InputResult
import com.tools.inputbridge.service.InputBridgeProjectService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.DefaultEditorKit

internal class InputBridgePanel(project: Project) : JPanel(BorderLayout()), Disposable, InputBridgeProjectService.Listener {
    private val service = project.service<InputBridgeProjectService>()
    private val deviceModel = DefaultComboBoxModel<DeviceInfo>()
    private val deviceCombo = JComboBox(deviceModel)
    private val refreshButton = JButton("Refresh")
    private val connectButton = JButton("Connect")
    private val statusLabel = JBLabel("Disconnected")
    private val inputArea = JBTextArea(12, 48)
    private val syncClipboardCheckBox = JBCheckBox("Update automatically")
    private val replaceEditorCheckBox = JBCheckBox("Replace editor text")
    private val fetchClipboardButton = JButton("Get current")
    private val replaceOnDeviceCheckBox = JBCheckBox("Replace text on device")
    private val clearButton = editorAction("Clear editor", AllIcons.Actions.GC, ::clearEditor)
    private val copyButton = editorAction("Copy editor text", AllIcons.Actions.Copy, ::copyEditorText)
    private val sendButton = JButton("Send")

    private var connectionState = ConnectionState.DISCONNECTED
    private var connectedSerial: String? = null
    private var updatingDeviceModel = false
    private var refreshInProgress = false
    private var lastPreparedSelection: String? = null
    private var fetchRequested = false
    private var lastAppliedClipboard: Pair<String, Long>? = null
    private var reportedVisible = false

    init {
        border = JBUI.Borders.empty(12)
        preferredSize = Dimension(JBUI.scale(680), JBUI.scale(380))
        add(buildContent(), BorderLayout.CENTER)
        configureComponents()
        bindActions()
        service.addListener(this, this)
    }

    val preferredFocusComponent: JComponent
        get() = inputArea

    override fun dispose() {
        setWindowVisible(false)
    }

    fun activate() {
        setWindowVisible(true)
        refreshDevices()
    }

    private fun applyDevices(devices: List<DeviceInfo>) {
        val selectedSerial = selectedDevice()?.serial ?: connectedSerial
        updatingDeviceModel = true
        deviceModel.removeAllElements()
        devices.forEach(deviceModel::addElement)
        selectSerial(selectedSerial)
        if (deviceCombo.selectedItem == null) {
            devices.firstOrNull(DeviceInfo::online)?.let(deviceCombo::setSelectedItem)
        }
        updatingDeviceModel = false
        if (devices.isEmpty() && connectionState == ConnectionState.DISCONNECTED) {
            showStatus("No ADB devices detected", StatusTone.ERROR)
        } else if (devices.none(DeviceInfo::online) && connectionState == ConnectionState.DISCONNECTED) {
            showStatus("No available ADB device", StatusTone.ERROR)
        }
        updateControls()
        prepareSelectedDeviceIfNeeded()
    }

    override fun onConnectionChanged(status: ConnectionStatus) {
        connectionState = status.state
        connectedSerial = status.serial
        showStatus(
            status.message,
            when (status.state) {
                ConnectionState.READY -> StatusTone.SUCCESS
                ConnectionState.ERROR -> StatusTone.ERROR
                else -> StatusTone.NEUTRAL
            },
        )
        selectSerial(status.serial)
        updateControls()
    }

    override fun onClipboardChanged(update: ClipboardUpdate) {
        if (!syncClipboardCheckBox.isSelected && !fetchRequested) return
        val identity = update.serial to update.sequence
        if (lastAppliedClipboard == identity) {
            fetchRequested = false
            return
        }
        lastAppliedClipboard = identity
        fetchRequested = false
        applyClipboardText(update.text.orEmpty())
    }

    override fun onClipboardCleared(serial: String?) {
        lastAppliedClipboard = null
    }

    override fun onInputResult(result: InputResult) {
        showStatus(result.message, if (result.success) StatusTone.SUCCESS else StatusTone.ERROR)
    }

    private fun buildContent(): JComponent = JPanel(BorderLayout(0, JBUI.scale(10))).apply {
        isOpaque = false
        add(buildDeviceHeader(), BorderLayout.NORTH)
        add(buildEditorPanel(), BorderLayout.CENTER)
    }

    private fun buildDeviceHeader(): JComponent {
        val deviceRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(JBLabel("Device:"), BorderLayout.WEST)
            add(deviceCombo, BorderLayout.CENTER)
            add(horizontalPanel(refreshButton, connectButton), BorderLayout.EAST)
        }
        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(2)
            add(deviceRow, BorderLayout.NORTH)
            add(statusLabel, BorderLayout.SOUTH)
        }
    }

    private fun buildEditorPanel(): JComponent {
        val editorTitle = JBLabel("Text to send").apply {
            font = JBFont.h3()
        }
        val clipboardControls = horizontalPanel(
            JBLabel("Device clipboard:"),
            syncClipboardCheckBox,
            replaceEditorCheckBox,
            fetchClipboardButton,
        )
        val editorHeader = JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
            isOpaque = false
            add(editorTitle, BorderLayout.WEST)
            add(clipboardControls, BorderLayout.EAST)
        }
        val footer = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(JBLabel("Enter to send · Shift+Enter for a new line"), BorderLayout.WEST)
            add(horizontalPanel(replaceOnDeviceCheckBox, sendButton), BorderLayout.EAST)
        }
        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            isOpaque = false
            add(editorHeader, BorderLayout.NORTH)
            add(buildEditorSurface(), BorderLayout.CENTER)
            add(footer, BorderLayout.SOUTH)
        }
    }

    private fun buildEditorSurface(): JComponent {
        val editorToolbar = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            background = inputArea.background
            border = JBUI.Borders.empty(4, 8, 6, 8)
            add(clearButton)
            add(copyButton)
        }
        val scrollPane = JBScrollPane(inputArea).apply {
            border = JBUI.Borders.empty()
            viewportBorder = JBUI.Borders.empty()
        }
        return JPanel(BorderLayout()).apply {
            background = inputArea.background
            border = JBUI.Borders.customLine(JBColor.border())
            add(scrollPane, BorderLayout.CENTER)
            add(editorToolbar, BorderLayout.SOUTH)
        }
    }

    private fun configureComponents() {
        inputArea.apply {
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(10)
            font = JBFont.create(
                UIUtil.getLabelFont(UIUtil.FontSize.NORMAL).deriveFont(JBUIScale.scale(14f)),
                false,
            )
        }
        syncClipboardCheckBox.apply {
            isSelected = false
            toolTipText = "Apply future device clipboard changes while this dialog is open"
        }
        replaceEditorCheckBox.apply {
            isSelected = false
            toolTipText = "Replace the editor instead of appending imported clipboard text"
        }
        replaceOnDeviceCheckBox.apply {
            isSelected = false
            toolTipText = "Select all text in the focused device control before sending"
        }
        fetchClipboardButton.toolTipText = "Fetch the current device clipboard once"
        updateEditorActions()
        updateControls()
    }

    private fun bindActions() {
        refreshButton.addActionListener { refreshDevices() }
        connectButton.addActionListener {
            if (connectionState in ACTIVE_STATES) {
                service.disconnect()
            } else {
                selectedDevice()?.takeIf(DeviceInfo::online)?.let {
                    lastPreparedSelection = it.serial
                    service.connect(it.serial)
                }
            }
        }
        deviceCombo.addActionListener {
            updateControls()
            prepareSelectedDeviceIfNeeded()
        }
        fetchClipboardButton.addActionListener {
            fetchRequested = true
            requestClipboard()
        }
        sendButton.addActionListener { send() }

        inputArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = updateEditorActions()

            override fun removeUpdate(event: DocumentEvent) = updateEditorActions()

            override fun changedUpdate(event: DocumentEvent) = updateEditorActions()
        })
        inputArea.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), SEND_ACTION)
        inputArea.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), DefaultEditorKit.insertBreakAction)
        inputArea.actionMap.put(SEND_ACTION, object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) = send()
        })
    }

    private fun refreshDevices() {
        if (refreshInProgress) return
        refreshInProgress = true
        showStatus("Detecting ADB devices…", StatusTone.NEUTRAL)
        updateControls()
        service.refreshDevices { result ->
            refreshInProgress = false
            result.fold(
                onSuccess = ::applyDevices,
                onFailure = {
                    showStatus(it.message ?: "Unable to detect ADB devices", StatusTone.ERROR)
                    updateControls()
                },
            )
        }
    }

    private fun requestClipboard() {
        if (connectionState == ConnectionState.READY) service.requestClipboardSnapshot()
    }

    private fun applyClipboardText(text: String) {
        inputArea.text = ClipboardTextMerger.merge(
            current = inputArea.text,
            incoming = text,
            replace = replaceEditorCheckBox.isSelected,
        )
        inputArea.caretPosition = inputArea.document.length
    }

    private fun clearEditor() {
        inputArea.text = ""
        inputArea.requestFocusInWindow()
    }

    private fun copyEditorText() {
        val text = inputArea.selectedText?.takeIf(String::isNotEmpty) ?: inputArea.text
        if (text.isNotEmpty()) {
            CopyPasteManager.getInstance().setContents(StringSelection(text))
        }
        inputArea.requestFocusInWindow()
    }

    private fun updateEditorActions() {
        val hasText = inputArea.document.length > 0
        clearButton.isEnabled = hasText
        copyButton.isEnabled = hasText
    }

    private fun send() {
        service.sendText(
            inputArea.text,
            appendEnter = false,
            replaceExisting = replaceOnDeviceCheckBox.isSelected,
        )
    }

    private fun setWindowVisible(visible: Boolean) {
        if (reportedVisible == visible) return
        reportedVisible = visible
        service.setWindowVisible(visible)
    }

    private fun updateControls() {
        val active = connectionState in ACTIVE_STATES
        val ready = connectionState == ConnectionState.READY
        val selectedOnline = selectedDevice()?.online == true
        deviceCombo.isEnabled = !active
        refreshButton.isEnabled = !refreshInProgress &&
            connectionState !in setOf(ConnectionState.CONNECTING, ConnectionState.RECONNECTING)
        connectButton.text = if (active) "Disconnect" else "Connect"
        connectButton.isEnabled = active || selectedOnline
        syncClipboardCheckBox.isEnabled = ready
        replaceEditorCheckBox.isEnabled = ready
        fetchClipboardButton.isEnabled = ready
        replaceOnDeviceCheckBox.isEnabled = ready
        sendButton.isEnabled = ready
        if (!ready) fetchRequested = false
    }

    private fun selectedDevice(): DeviceInfo? = deviceCombo.selectedItem as? DeviceInfo

    private fun prepareSelectedDeviceIfNeeded() {
        if (updatingDeviceModel || connectionState != ConnectionState.DISCONNECTED) return
        val device = selectedDevice()?.takeIf(DeviceInfo::online) ?: return
        if (lastPreparedSelection == device.serial) return
        lastPreparedSelection = device.serial
        service.connect(device.serial)
    }

    private fun selectSerial(serial: String?) {
        if (serial == null) return
        (0 until deviceModel.size)
            .map(deviceModel::getElementAt)
            .firstOrNull { it.serial == serial }
            ?.let(deviceCombo::setSelectedItem)
    }

    private fun showStatus(message: String, tone: StatusTone) {
        statusLabel.text = message
        statusLabel.foreground = when (tone) {
            StatusTone.SUCCESS -> JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
            StatusTone.ERROR -> JBColor(Color(0xB7, 0x1C, 0x1C), Color(0xEF, 0x9A, 0x9A))
            StatusTone.NEUTRAL -> JBColor.foreground()
        }
    }

    private fun horizontalPanel(vararg components: JComponent): JPanel = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        components.forEachIndexed { index, component ->
            if (index > 0) add(Box.createHorizontalStrut(JBUI.scale(6)))
            add(component)
        }
    }

    private fun editorAction(toolTip: String, icon: javax.swing.Icon, action: () -> Unit): LinkLabel<Void> =
        LinkLabel<Void>(null, icon, LinkListener { _, _ -> action() }).apply {
            setPaintUnderline(false)
            setHoveringIcon(IconUtil.darker(icon, 3))
            disabledIcon = IconLoader.getDisabledIcon(icon)
            preferredSize = Dimension(JBUI.scale(24), JBUI.scale(24))
            minimumSize = preferredSize
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
            toolTipText = toolTip
        }

    private companion object {
        const val SEND_ACTION = "inputbridge.send"
        val ACTIVE_STATES = setOf(
            ConnectionState.CONNECTING,
            ConnectionState.READY,
            ConnectionState.RECONNECTING,
            ConnectionState.SUSPENDED,
        )
    }

    private enum class StatusTone {
        NEUTRAL,
        SUCCESS,
        ERROR,
    }
}
