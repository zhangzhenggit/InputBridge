package com.tools.inputbridge.core

/**
 * Formatting the device reported for a range of clipboard text.
 *
 * Wire identifiers are shared with the device server and must stay stable
 * across protocol revisions.
 */
enum class TextStyleKind(val wireId: Int) {
    BOLD(1),
    ITALIC(2),
    UNDERLINE(3),
    STRIKETHROUGH(4),

    /** [TextStyleRun.value] is an ARGB color. */
    FOREGROUND(5),

    /** [TextStyleRun.value] is an ARGB color. */
    BACKGROUND(6),

    /** [TextStyleRun.value] is a percentage of the editor font size. */
    RELATIVE_SIZE(7),
    MONOSPACE(8),
    LINK(9),
    SUPERSCRIPT(10),
    SUBSCRIPT(11),
    ;

    companion object {
        private val BY_WIRE_ID = entries.associateBy(TextStyleKind::wireId)

        fun fromWireId(wireId: Int): TextStyleKind? = BY_WIRE_ID[wireId]
    }
}

/** Offsets are UTF-16 code unit indexes into [StyledText.text]. */
data class TextStyleRun(
    val start: Int,
    val end: Int,
    val kind: TextStyleKind,
    val value: Int,
)

data class StyledText(
    val text: String,
    val runs: List<TextStyleRun> = emptyList(),
) {
    /**
     * Whitespace-only clipboard content imports as an invisible edit, which is
     * indistinguishable from a broken sync, so it counts as nothing to import.
     * Blankness follows Kotlin's rules, which include the space separators Java's
     * `Character.isWhitespace` omits, such as the non-breaking space.
     */
    val isImportable: Boolean
        get() = text.isNotBlank()
}
