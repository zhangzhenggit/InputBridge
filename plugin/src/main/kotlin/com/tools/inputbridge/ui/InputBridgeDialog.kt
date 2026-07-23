package com.tools.inputbridge.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.DialogWrapper.IdeModalityType
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import javax.swing.Action
import javax.swing.JComponent

internal class InputBridgeDialog private constructor(
    private val project: Project,
) : DialogWrapper(project, false, IdeModalityType.MODELESS) {
    private val panel = InputBridgePanel(project)

    init {
        title = "InputBridge"
        init()
    }

    override fun createCenterPanel(): JComponent = panel

    override fun createActions(): Array<Action> = emptyArray()

    override fun getPreferredFocusedComponent(): JComponent = panel.preferredFocusComponent

    override fun dispose() {
        if (project.getUserData(DIALOG_KEY) === this) {
            project.putUserData(DIALOG_KEY, null)
        }
        Disposer.dispose(panel)
        super.dispose()
    }

    private fun activate() {
        window?.apply {
            toFront()
            requestFocus()
        }
    }

    companion object {
        private val DIALOG_KEY = Key.create<InputBridgeDialog>("inputbridge.dialog")

        fun show(project: Project) {
            project.getUserData(DIALOG_KEY)?.let {
                it.activate()
                return
            }

            InputBridgeDialog(project).also {
                project.putUserData(DIALOG_KEY, it)
                it.show()
                it.panel.activate()
            }
        }
    }
}
