package com.kimjisub.launchpad.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Centralized Play screen UI tokens. Previously these hex values were scattered across
 * PlayActivity composables (OptionPanel, ChromeColumn, mode buttons). Consolidating them
 * keeps the color story consistent and avoids CLAUDE.md's "no hardcoded colors" guidance
 * being violated at the view layer.
 *
 * These are UI chrome tokens, distinct from per-unipack ThemeResources which remain
 * user-customizable via theme packs.
 */
object PlayPalette {
	/** Primary accent for the Play screen (progress, active state highlights). */
	val accent = Color(0xFFE8A44A)

	/** Destructive action tint (Quit icon, error states). */
	val danger = Color(0xFFFF6B6B)

	/** Slide-in option panel background. */
	val panelBackground = Color(0xF0161E2B)

	// Play mode distinctive colors. Each mode owns a hue so the segmented control
	// communicates mode identity without relying on text alone.
	val modeAutoPlay = Color(0xFFE8A44A)
	val modeGuidePlay = Color(0xFF4FC3F7)
	val modeStepPractice = Color(0xFF66BB6A)
}
