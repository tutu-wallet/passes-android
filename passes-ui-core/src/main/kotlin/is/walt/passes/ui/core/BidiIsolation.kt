package `is`.walt.passes.ui.core

/**
 * Wraps [s] in First-Strong Isolate / Pop Directional Isolate (U+2068, U+2069) so an
 * untrusted string cannot reorder the text around it. The one bidi fence for every UI module.
 */
public fun isolated(s: String): String = "$FSI$s$PDI"

/**
 * First Strong Isolate (U+2068). Opens an isolate that takes the directional class
 * of the first strong-class character within.
 */
public const val FSI: Char = '⁨'

/** Pop Directional Isolate (U+2069). Closes the most recently opened isolate. */
public const val PDI: Char = '⁩'
