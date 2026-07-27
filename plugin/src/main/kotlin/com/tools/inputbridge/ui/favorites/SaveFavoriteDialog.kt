package com.tools.inputbridge.ui.favorites

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

internal class SaveFavoriteDialog(
    project: Project,
    suggestedTitle: String,
    private val validateTitle: (String) -> String?,
) : DialogWrapper(project, true) {
    private val titleField = JBTextField(suggestedTitle)

    val favoriteTitle: String
        get() = titleField.text

    init {
        title = "Save favorite"
        setOKButtonText("Save")
        titleField.columns = 32
        init()
        titleField.selectAll()
    }

    override fun createCenterPanel(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent("Title:", titleField)
            .panel

    override fun getPreferredFocusedComponent(): JComponent = titleField

    override fun doValidate(): ValidationInfo? =
        validateTitle(titleField.text)?.let { ValidationInfo(it, titleField) }
}
