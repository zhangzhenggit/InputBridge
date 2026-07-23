package com.tools.inputbridge.ui

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

class OpenInputBridgeAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.let(InputBridgeDialog::show)
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }
}
