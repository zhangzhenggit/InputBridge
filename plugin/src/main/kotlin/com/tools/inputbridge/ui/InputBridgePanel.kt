package com.tools.inputbridge.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.ui.components.labels.LinkListener
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.tools.inputbridge.core.ClipboardUpdate
import com.tools.inputbridge.core.ConnectionState
import com.tools.inputbridge.core.ConnectionStatus
import com.tools.inputbridge.core.DeviceInfo
import com.tools.inputbridge.core.InputResult
import com.tools.inputbridge.core.StyledText
import com.tools.inputbridge.favorites.FavoriteAddStatus
import com.tools.inputbridge.favorites.FavoriteValidationException
import com.tools.inputbridge.favorites.FavoritesService
import com.tools.inputbridge.history.InputHistoryService
import com.tools.inputbridge.service.InputBridgeProjectService
import com.tools.inputbridge.ui.favorites.FavoritesManagerDialog
import com.tools.inputbridge.ui.favorites.FavoritesPopup
import com.tools.inputbridge.ui.favorites.SaveFavoriteDialog
import com.tools.inputbridge.ui.history.InputHistoryPopup
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
    private val favoritesService = ApplicationManager.getApplication().service<FavoritesService>()
    private val historyService = ApplicationManager.getApplication().service<InputHistoryService>()
    private val owningProject = project
    private val deviceModel = DefaultComboBoxModel<DeviceInfo>()
    private val deviceCombo = JComboBox(deviceModel)
    private val refreshButton = JButton("Refresh")
    private val connectButton = JButton("Connect")
    private val statusLabel = JBLabel("Disconnected")
    private val inputArea = StyledInputArea()
    private val syncClipboardCheckBox = JBCheckBox("Update automatically")
    private val replaceEditorCheckBox = JBCheckBox("Replace editor text")
    private val fetchClipboardButton = JButton("Get current")
    private val replaceOnDeviceCheckBox = JBCheckBox("Replace text on device")
    private val favoritesButton = editorAction(
        "Favorites",
        AllIcons.Nodes.NotFavoriteOnHover,
        ::showFavorites,
    )
    private val historyButton = editorAction(
        "Input history",
        AllIcons.Vcs.History,
        ::showHistory,
    )
    private val clearButton = editorAction("Clear editor", AllIcons.Actions.GC, ::clearEditor)
    private val copyButton = editorAction("Copy editor text", AllIcons.Actions.Copy, ::copyEditorText)
    private val sendButton = JButton("Send")

    private var connectionStatus = ConnectionStatus(ConnectionState.DISCONNECTED)
    private val connectionState: ConnectionState
        get() = connectionStatus.state
    private var devices: List<DeviceInfo> = emptyList()
    private var devicesLoaded = false
    private val deviceModels = HashMap<String, String>()
    private var updatingDeviceModel = false
    private var refreshInProgress = false
    private var userDisconnected = false
    private var deviceNoticeShown = false
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

    override fun onDevicesChanged(devices: List<DeviceInfo>) = applyDevices(devices)

    private fun applyDevices(devices: List<DeviceInfo>) {
        val previousSelection = selectedDevice()?.serial
        this.devices = devices
        devicesLoaded = true
        devices.forEach { device -> device.model?.let { deviceModels[device.serial] = it } }

        renderDevices()
        showDeviceNotice()
        updateControls()
        autoConnect(previousSelection)
    }

    override fun onConnectionChanged(status: ConnectionStatus) {
        val previousSelection = selectedDevice()?.serial
        connectionStatus = status
        showConnectionStatus()
        renderDevices()
        updateControls()
        // The session often reports a lost device after the device list already changed.
        autoConnect(previousSelection)
    }

    private fun autoConnect(previousSelection: String?) {
        if (!devicesLoaded) return
        DeviceChoice.autoConnectTarget(
            devices,
            connectionState,
            targetSerial(),
            previousSelection,
            userDisconnected,
        )?.let(service::connect)
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
        applyClipboard(update)
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
        val textActions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(favoritesButton)
            add(historyButton)
        }
        val editorActions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(clearButton)
            add(copyButton)
        }
        val editorToolbar = JPanel(BorderLayout()).apply {
            background = inputArea.background
            border = JBUI.Borders.empty(4, 8, 6, 8)
            add(textActions, BorderLayout.WEST)
            add(editorActions, BorderLayout.EAST)
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
                userDisconnected = true
                service.disconnect()
            } else {
                selectedDevice()?.takeIf(DeviceInfo::online)?.let {
                    userDisconnected = false
                    service.connect(it.serial)
                }
            }
        }
        deviceCombo.addActionListener {
            if (updatingDeviceModel) return@addActionListener
            updateControls()
            // Picking another online device switches to it; the service replaces the current session.
            val device = selectedDevice()?.takeIf(DeviceInfo::online) ?: return@addActionListener
            if (device.serial != targetSerial()) {
                userDisconnected = false
                service.connect(device.serial)
            }
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
        showStatus("Detecting ADB devices…", StatusTone.NEUTRAL, deviceNotice = true)
        updateControls()
        service.refreshDevices { result ->
            refreshInProgress = false
            result.fold(
                onSuccess = ::applyDevices,
                onFailure = {
                    showStatus(it.message ?: "Unable to detect ADB devices", StatusTone.ERROR, deviceNotice = true)
                    updateControls()
                },
            )
        }
    }

    private fun requestClipboard() {
        if (connectionState == ConnectionState.READY) service.requestClipboardSnapshot()
    }

    private fun applyClipboard(update: ClipboardUpdate) {
        val incoming = StyledText(update.text.orEmpty(), update.runs)
        inputArea.applyStyled(
            if (incoming.isImportable) incoming else StyledText(""),
            replace = replaceEditorCheckBox.isSelected,
        )
        // Every import reports its outcome, so a notice about an earlier blank clipboard cannot outlive it.
        showStatus(
            ClipboardImportStatus.message(incoming),
            if (incoming.isImportable) StatusTone.SUCCESS else StatusTone.NEUTRAL,
        )
    }

    private fun clearEditor() {
        inputArea.setPlainText("")
        inputArea.requestFocusInWindow()
    }

    private fun copyEditorText() {
        val text = inputArea.selectedText?.takeIf(String::isNotEmpty) ?: inputArea.plainText
        if (text.isNotEmpty()) {
            CopyPasteManager.getInstance().setContents(StringSelection(text))
        }
        inputArea.requestFocusInWindow()
    }

    private fun showFavorites() {
        val insertionTarget = EditorInsertionTarget.capture(inputArea)
        FavoritesPopup.show(
            anchor = favoritesButton,
            service = favoritesService,
            saveText = favoriteText(),
            onSave = ::addFavorite,
            onManage = ::manageFavorites,
        ) { favorite ->
            insertionTarget.insert(favorite.content)
            inputArea.requestFocusInWindow()
        }
    }

    private fun favoriteText(): String? =
        (inputArea.selectedText?.takeIf(String::isNotBlank) ?: inputArea.plainText).takeIf(String::isNotBlank)

    private fun addFavorite(text: String) {
        val dialog = SaveFavoriteDialog(
            owningProject,
            favoritesService.suggestedTitle(text),
            { title -> favoritesService.titleValidationError(title) },
        )
        if (!dialog.showAndGet()) {
            inputArea.requestFocusInWindow()
            return
        }
        try {
            favoritesService.tryAdd(dialog.favoriteTitle, text).saveErrorMessage()?.let { message ->
                Messages.showErrorDialog(owningProject, message, "Unable to save favorite")
            }
        } catch (error: FavoriteValidationException) {
            Messages.showErrorDialog(owningProject, error.message, "Unable to save favorite")
        }
        inputArea.requestFocusInWindow()
    }

    private fun FavoriteAddStatus.saveErrorMessage(): String? =
        when (this) {
            FavoriteAddStatus.READY -> null
            FavoriteAddStatus.EMPTY -> "Enter text to save"
            FavoriteAddStatus.ITEM_TOO_LARGE -> "Favorite text exceeds 256 KB"
            FavoriteAddStatus.DUPLICATE -> "This text is already saved"
            FavoriteAddStatus.COLLECTION_FULL -> "The favorites limit has been reached"
            FavoriteAddStatus.STORAGE_FULL -> "The favorites storage limit has been reached"
        }

    private fun manageFavorites() {
        FavoritesManagerDialog(owningProject, favoritesService).show()
        inputArea.requestFocusInWindow()
    }

    private fun showHistory() {
        InputHistoryPopup.show(historyButton, historyService.history()) { item ->
            inputArea.setPlainText(item.content)
            inputArea.requestFocusInWindow()
        }
    }

    private fun updateEditorActions() {
        val hasText = inputArea.document.length > 0
        clearButton.isEnabled = hasText
        copyButton.isEnabled = hasText
    }

    private fun send() {
        service.sendText(
            inputArea.plainText,
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
        // Devices stay selectable while connected, so a newly attached device can be chosen directly.
        deviceCombo.isEnabled = deviceModel.size > 0
        refreshButton.isEnabled = !refreshInProgress
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

    /** The device the service is connected to or trying to reach, if any. */
    private fun targetSerial(): String? = connectionStatus.serial.takeIf { connectionState in ACTIVE_STATES }

    private fun renderDevices() {
        val target = targetSerial()
        val entries = if (devicesLoaded) DeviceChoice.entries(devices, target, target?.let(deviceModels::get)) else emptyList()
        val selection = DeviceChoice.selection(entries, target, selectedDevice()?.serial)
        val current = (0 until deviceModel.size).map(deviceModel::getElementAt)
        if (current == entries && deviceModel.selectedItem == selection) return
        updatingDeviceModel = true
        try {
            if (current != entries) {
                deviceModel.removeAllElements()
                entries.forEach(deviceModel::addElement)
            }
            deviceModel.selectedItem = selection
        } finally {
            updatingDeviceModel = false
        }
    }

    /** Names why nothing can connect, or replaces a finished device notice with the connection status. */
    private fun showDeviceNotice() {
        val notice = when {
            connectionState != ConnectionState.DISCONNECTED -> null
            devices.isEmpty() -> "No ADB devices detected"
            devices.any(DeviceInfo::online) -> null
            devices.any { it.state == DeviceInfo.UNAUTHORIZED_STATE } -> "Allow USB debugging on the device to connect"
            else -> "No available ADB device"
        }
        when {
            notice != null -> showStatus(notice, StatusTone.ERROR, deviceNotice = true)
            deviceNoticeShown -> showConnectionStatus()
        }
    }

    private fun showConnectionStatus() {
        showStatus(
            connectionStatus.message,
            when (connectionState) {
                ConnectionState.READY -> StatusTone.SUCCESS
                ConnectionState.ERROR -> StatusTone.ERROR
                else -> StatusTone.NEUTRAL
            },
        )
    }

    private fun showStatus(message: String, tone: StatusTone, deviceNotice: Boolean = false) {
        deviceNoticeShown = deviceNotice
        statusLabel.text = message
        // Clipboard diagnostics outrun the label width, so the full text stays reachable.
        statusLabel.toolTipText = message
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
            ConnectionState.WAITING,
            ConnectionState.SUSPENDED,
        )
    }

    private enum class StatusTone {
        NEUTRAL,
        SUCCESS,
        ERROR,
    }
}
