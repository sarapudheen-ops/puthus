package com.bod3.app.live

/** The most recent [capacity] samples per value, oldest first, for the graph tiles. */
class LiveHistory(private val capacity: Int = 120) {
    private val samples = LinkedHashMap<String, ArrayDeque<Double>>()

    fun add(key: String, value: Double) {
        val queue = samples.getOrPut(key) { ArrayDeque() }
        queue.addLast(value)
        while (queue.size > capacity) queue.removeFirst()
    }

    fun clear() = samples.clear()

    fun snapshot(): Map<String, List<Double>> = samples.mapValues { it.value.toList() }
}
