package org.stypox.dicio.ui.ambient

/**
 * Phase 4.1: voice listening must run without cross-app WindowManager Compose attach.
 *
 * Ambient implementation is retained for Phase 5. In-app [AmbientVoiceOverlayFromSession]
 * on DrivingScreen remains the HUD when the app is foregrounded.
 */
object AmbientVoiceAttachPolicy {
    /**
     * When false, [AmbientVoiceOverlayController] observes session state but does not
     * call WindowManager.addView / ComposeView attachment.
     */
    const val CROSS_APP_WINDOW_ATTACH_ENABLED = false

    fun shouldAttachCrossAppOverlay(
        canDrawOverlays: Boolean,
        hudVisible: Boolean,
    ): Boolean = CROSS_APP_WINDOW_ATTACH_ENABLED && canDrawOverlays && hudVisible

    /** Required SavedState bootstrap order for ComposeView outside an Activity. */
    fun savedStateBootstrapSteps(): List<String> = listOf(
        "performAttach",
        "performRestore",
        "ON_CREATE",
        "ON_START",
        "ON_RESUME",
    )
}
