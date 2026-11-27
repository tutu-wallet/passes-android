package `is`.walt.passes.document.ui.theme

import `is`.walt.passes.ui.core.ArgbColor

/** Colour and copy slots a host supplies to `passes-document-ui`, read through `LocalDocumentSemantics`. */
public data class DocumentSemantics(
    /** Behind the page render or decoded image, inline and full screen. */
    public val frameBackground: ArgbColor,
    /** The "open full screen" banner and the full-screen close control. */
    public val fullScreenBannerBackground: ArgbColor,
    public val fullScreenBannerForeground: ArgbColor,
    // English placeholders so tests compose; a production host supplies localised copy.
    public val fullScreenBannerLabel: String = "Tap for full screen",
    public val closeFullScreenLabel: String = "Close",
)
