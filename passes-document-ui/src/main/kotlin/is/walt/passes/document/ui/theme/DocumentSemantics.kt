package `is`.walt.passes.document.ui.theme

import `is`.walt.passes.ui.core.ArgbColor

/**
 * Semantic colour and copy slots a host supplies to `passes-document-ui`, read through
 * `LocalDocumentSemantics`. No slot can hide the trust caption; contrast is the host's job.
 */
public data class DocumentSemantics(
    public val captionBackground: ArgbColor,
    public val captionForeground: ArgbColor,
    public val captionIconTint: ArgbColor = captionForeground,
    /** Behind the page render or decoded image, inline and full screen. */
    public val frameBackground: ArgbColor,
    /** The "open full screen" banner and the full-screen close control. */
    public val fullScreenBannerBackground: ArgbColor,
    public val fullScreenBannerForeground: ArgbColor,
    // English placeholders so tests compose; a production host supplies localised copy.
    public val fullScreenBannerLabel: String = "Tap for full screen",
    public val closeFullScreenLabel: String = "Close",
)
