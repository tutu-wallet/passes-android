package `is`.walt.passes.ui.internal

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ByteBoundedLruTest {

    // Each value weighs its own length.
    private fun lru(maxBytes: Int) = ByteBoundedLru<String, String>(maxBytes) { _, value -> value.length }

    private fun ByteBoundedLru<String, String>.put(key: String, value: String) = putIfAbsent(key, value, generation)

    @Test
    fun keepsEntriesWhileTheyFit() {
        val cache = lru(maxBytes = 10)
        cache.put("a", "xxxx")
        cache.put("b", "yyyyyy")

        assertThat(cache.get("a")).isEqualTo("xxxx")
        assertThat(cache.get("b")).isEqualTo("yyyyyy")
        assertThat(cache.bytes).isEqualTo(10)
    }

    @Test
    fun evictsTheLeastRecentlyUsedPastTheBudget() {
        val cache = lru(maxBytes = 10)
        cache.put("a", "xxxx")
        cache.put("b", "yyyy")
        cache.get("a")

        cache.put("c", "zzzz")

        assertThat(cache.get("b")).isNull()
        assertThat(cache.get("a")).isEqualTo("xxxx")
        assertThat(cache.get("c")).isEqualTo("zzzz")
        assertThat(cache.bytes).isEqualTo(8)
    }

    @Test
    fun anEntryHeavierThanTheBudgetIsNotKeptAndEvictsNothing() {
        val cache = lru(maxBytes = 10)
        cache.put("a", "xxxx")

        cache.put("big", "y".repeat(11))

        assertThat(cache.get("big")).isNull()
        assertThat(cache.get("a")).isEqualTo("xxxx")
        assertThat(cache.bytes).isEqualTo(4)
    }

    @Test
    fun theFirstValueKeptForAKeyWinsAndIsReturned() {
        val cache = lru(maxBytes = 10)
        assertThat(cache.put("a", "xxxx")).isNull()

        val held = cache.put("a", "yyyyyyyy")

        assertThat(held).isEqualTo("xxxx")
        assertThat(cache.get("a")).isEqualTo("xxxx")
        assertThat(cache.bytes).isEqualTo(4)
    }

    @Test
    fun aValueFromBeforeAClearIsNotKept() {
        val cache = lru(maxBytes = 10)
        val before = cache.generation

        cache.clear()
        cache.putIfAbsent("a", "xxxx", before)

        assertThat(cache.get("a")).isNull()
        assertThat(cache.bytes).isEqualTo(0)
    }

    @Test
    fun clearEmptiesTheCache() {
        val cache = lru(maxBytes = 10)
        cache.put("a", "xxxx")

        cache.clear()

        assertThat(cache.get("a")).isNull()
        assertThat(cache.bytes).isEqualTo(0)
    }

    @Test
    fun budgetMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) { lru(maxBytes = 0) }
    }
}
