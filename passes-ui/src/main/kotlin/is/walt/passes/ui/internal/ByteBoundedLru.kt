package `is`.walt.passes.ui.internal

/**
 * Least-recently-used map whose entries' weights, as [weigh] gives them, total at most [maxBytes].
 * An entry heavier than [maxBytes] is not kept. Safe to call from any thread.
 */
internal class ByteBoundedLru<K : Any, V : Any>(
    private val maxBytes: Int,
    private val weigh: (K, V) -> Int,
) {
    init {
        require(maxBytes > 0) { "maxBytes must be positive, was $maxBytes" }
    }

    private class Weighted<V>(val value: V, val weight: Int)

    private val entries = LinkedHashMap<K, Weighted<V>>(INITIAL_CAPACITY, LOAD_FACTOR, true)

    var bytes: Long = 0L
        @Synchronized get
        private set

    /** Advanced by [clear], so a value computed from before a clear is not kept after it. */
    var generation: Long = 0L
        @Synchronized get
        private set

    @Synchronized
    fun get(key: K): V? = entries[key]?.value

    /** Returns the value already held for [key], else keeps [value] unless [clear] ran since [generation]. */
    @Synchronized
    fun putIfAbsent(key: K, value: V, generation: Long): V? {
        entries[key]?.let { return it.value }
        val weight = weigh(key, value)
        if (generation != this.generation || weight > maxBytes) return null
        entries[key] = Weighted(value, weight)
        bytes += weight
        val eldest = entries.values.iterator()
        while (bytes > maxBytes) {
            bytes -= eldest.next().weight
            eldest.remove()
        }
        return null
    }

    @Synchronized
    fun clear() {
        entries.clear()
        bytes = 0L
        generation++
    }

    private companion object {
        const val INITIAL_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f
    }
}
