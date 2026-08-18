package com.tools.inputbridge.ui

import java.awt.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Presentation rules for device clipboard formatting.
 *
 * Device colors are authored for the application they were copied from, so a value
 * that is legible on the device can be invisible in the IDE theme. Colors are only
 * applied when they keep enough contrast against the surface behind them.
 */
internal object TextStyleAttributes {
    const val MIN_CONTRAST_RATIO = 3.0
    const val MIN_FONT_SIZE = 8
    const val MAX_FONT_SIZE = 48

    /** Android colors carry alpha the editor cannot honour per character. */
    fun opaqueColor(argb: Int): Color = Color(argb and 0xFFFFFF)

    fun scaledFontSize(baseSize: Int, percent: Int): Int =
        (baseSize * percent / 100.0).roundToInt().coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)

    fun isReadable(foreground: Color, backdrop: Color): Boolean =
        contrastRatio(foreground, backdrop) >= MIN_CONTRAST_RATIO

    /** WCAG 2.1 relative luminance contrast ratio, between 1.0 and 21.0. */
    fun contrastRatio(first: Color, second: Color): Double {
        val one = relativeLuminance(first)
        val other = relativeLuminance(second)
        return (max(one, other) + 0.05) / (min(one, other) + 0.05)
    }

    private fun relativeLuminance(color: Color): Double =
        0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)

    private fun linear(channel: Int): Double {
        val value = channel / 255.0
        return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
}
