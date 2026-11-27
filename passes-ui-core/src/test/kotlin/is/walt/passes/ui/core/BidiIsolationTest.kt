package `is`.walt.passes.ui.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pins that `isolated(s)` is exactly `FSI + s + PDI`; every surface that shows untrusted text relies on it. */
class BidiIsolationTest {

    @Test
    fun isolatedWrapsInFsiAndPdi() {
        val wrapped = isolated("hello")
        assertThat(wrapped).isEqualTo("⁨hello⁩")
        assertThat(wrapped.first()).isEqualTo(FSI)
        assertThat(wrapped.last()).isEqualTo(PDI)
    }

    @Test
    fun isolatedAcceptsEmptyStringAndPreservesFence() {
        // A surface should never invoke isolated("") in practice — the empty case is
        // handled by the caller — but if it does, the fence must still be intact so
        // surrounding bidi context cannot reorder later glyphs into the empty span.
        assertThat(isolated("")).isEqualTo("⁨⁩")
    }
}
