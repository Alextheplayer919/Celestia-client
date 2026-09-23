package com.proxy.mcbedrock.hud

/**
 * A fixed-size ring of ping samples, used for the HUD graph.
 *
 * Bounded on purpose: the relay runs for hours and this is drawn 2-4 times a
 * second, so the buffer never grows and never allocates while samples are added.
 */
class SampleRing(private val capacity: Int) {

    private val values = IntArray(capacity)
    private var count = 0

    /** Index of the next slot to write. */
    private var head = 0

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    val size: Int get() = count

    /** Adds a sample; negative values (no measurement) are stored as -1. */
    fun add(value: Int) {
        values[head] = value
        head = (head + 1) % capacity
        if (count < capacity) count++
    }

    /** Samples oldest first, `-1` where nothing was measured. */
    fun toList(): List<Int> {
        if (count == 0) return emptyList()
        val start = if (count < capacity) 0 else head
        return (0 until count).map { values[(start + it) % capacity] }
    }

    /** Most recent sample, or -1. */
    fun last(): Int = if (count == 0) -1 else values[(head - 1 + capacity) % capacity]

    /** Drops every sample. */
    fun clear() {
        count = 0
        head = 0
    }

    /** Mean of the measured (non-negative) samples, or -1. */
    fun mean(): Int {
        var sum = 0L
        var measured = 0
        for (value in toList()) {
            if (value >= 0) {
                sum += value
                measured++
            }
        }
        return if (measured == 0) -1 else (sum / measured).toInt()
    }

    /**
     * Heights in 0..[maxHeight] for drawing, oldest first. Missing samples come back
     * as 0. Values are scaled against [ceiling] so the graph does not jump around
     * when the ping drifts; when everything is below a floor of 40ms the scale is
     * held at 40ms so a steady connection looks steady instead of noisy.
     */
    fun bars(maxHeight: Int, ceiling: Int): List<Int> {
        if (count == 0) return emptyList()
        val scale = maxOf(ceiling, MIN_SCALE_MS)
        return toList().map { value ->
            if (value < 0) 0 else ((value.toLong() * maxHeight) / scale).toInt().coerceIn(0, maxHeight)
        }
    }

    private companion object {
        const val MIN_SCALE_MS = 40
    }
}

/**
 * Rolling history for every value the HUD graph and trend lines care about.
 * Kept separate from [com.proxy.mcbedrock.net.ConnectionStats] so the stats layer
 * stays a pure counter and the display layer owns its own memory.
 */
class StatsHistory(capacity: Int = 60) {

    val latency = SampleRing(capacity)
    val jitter = SampleRing(capacity)
    val upstreamRate = SampleRing(capacity)
    val downstreamRate = SampleRing(capacity)

    fun record(rttMs: Int, jitterMs: Int, upstreamBytesPerSecond: Long, downstreamBytesPerSecond: Long) {
        latency.add(rttMs)
        jitter.add(jitterMs)
        upstreamRate.add(upstreamBytesPerSecond.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        downstreamRate.add(downstreamBytesPerSecond.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    fun clear() {
        latency.clear()
        jitter.clear()
        upstreamRate.clear()
        downstreamRate.clear()
    }
}
