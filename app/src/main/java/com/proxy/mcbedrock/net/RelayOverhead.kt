package com.proxy.mcbedrock.net

/**
 * How much the relay itself costs per packet.
 *
 * This is the honest answer to "is the proxy making my game slower": the number
 * measured here is the work this app does on a packet that the game would not have
 * paid without it — building the TUN reply and writing it back. Everything else
 * (the server's own delay, the radio, the WAN) is not ours.
 *
 * Kept as plain arithmetic so the statistics can be tested without a socket.
 */
class OverheadTracker(private val sampleLimit: Int = 2048) {

    private val samples = LongArray(sampleLimit)
    private var writeIndex = 0
    private var sampleCount = 0

    var count: Long = 0
        private set
    var totalNanos: Long = 0
        private set
    var maxNanos: Long = 0
        private set

    /** Records one packet's relay cost. Ignores nonsense values instead of skewing the average. */
    fun record(nanos: Long) {
        if (nanos <= 0 || nanos > MAX_PLAUSIBLE_NANOS) return
        count++
        totalNanos += nanos
        if (nanos > maxNanos) maxNanos = nanos
        samples[writeIndex] = nanos
        writeIndex = (writeIndex + 1) % sampleLimit
        if (sampleCount < sampleLimit) sampleCount++
    }

    fun averageNanos(): Long = if (count == 0L) 0L else totalNanos / count

    fun averageMicros(): Double = averageNanos() / 1_000.0

    fun maxMicros(): Double = maxNanos / 1_000.0

    /**
     * 95th percentile of the recent samples. Averages hide the thing players feel:
     * a single bad packet that stalls a chunk. The tail is what matters here.
     */
    fun p95Micros(): Double {
        if (sampleCount == 0) return 0.0
        val copy = samples.copyOf(sampleCount)
        copy.sort()
        val index = ((sampleCount - 1) * 95 / 100).coerceIn(0, sampleCount - 1)
        return copy[index] / 1_000.0
    }

    fun reset() {
        count = 0
        totalNanos = 0
        maxNanos = 0
        writeIndex = 0
        sampleCount = 0
    }

    /**
     * Distribution of recent samples in µs, for a quick sense of the shape:
     * buckets [0,10) [10,50) [50,100) [100,500) [500,2000) [2000+).
     */
    fun histogram(): IntArray {
        val buckets = IntArray(BUCKET_EDGES_MICROS.size + 1)
        for (index in 0 until sampleCount) {
            val micros = samples[index] / 1_000
            var placed = false
            for (b in BUCKET_EDGES_MICROS.indices) {
                if (micros < BUCKET_EDGES_MICROS[b]) {
                    buckets[b]++
                    placed = true
                    break
                }
            }
            if (!placed) buckets[buckets.size - 1]++
        }
        return buckets
    }

    /** Human line for the UI: `0.4ms avg, 3.1ms p95`. */
    fun describe(): String =
        if (count == 0L) "·"
        else "%.1fms avg, %.1fms p95".format(averageMicros() / 1000.0, p95Micros() / 1000.0)

    private companion object {
        val BUCKET_EDGES_MICROS = intArrayOf(10, 50, 100, 500, 2_000)

        /** A packet taking longer than this was not really "relayed", it was stalled. */
        const val MAX_PLAUSIBLE_NANOS = 500_000_000L
    }
}

/**
 * Server round-trip time measured at the relay itself.
 *
 * RakNet keepalive pings are forwarded unchanged (the relay never schedules or
 * alters them); the relay only notes when it passed a ping on and when the matching
 * pong came back. That gives the server-leg RTT without the client's clock, and
 * without asking the game anything.
 *
 * The match key is the Echo timestamp inside the packet, which the server copies
 * back — exactly the mechanism the client itself uses.
 */
class PingLedger(private val capacity: Int = 16) {

    private val pending = LinkedHashMap<Long, Long>(capacity)
    private val rtts = ArrayDeque<Int>()

    var lastServerRttMs: Int = -1
        private set
    var minServerRttMs: Int = -1
        private set
    var maxServerRttMs: Int = -1
        private set
    private var totalRttMs: Long = 0
    private var rttCount: Long = 0

    /** A ping left the relay towards the server. */
    fun onPingForwarded(echoMillis: Long, nowNanos: Long) {
        pending[echoMillis] = nowNanos
        while (pending.size > capacity) {
            val oldest = pending.keys.firstOrNull() ?: break
            pending.remove(oldest)
        }
    }

    /**
     * A pong came back. Returns the server-leg RTT in ms when it matched a ping the
     * relay saw, or null when it did not (a stale reply, or one that started before
     * the relay was involved).
     */
    fun onPongForwarded(echoMillis: Long, nowNanos: Long): Int? {
        val sentNanos = pending.remove(echoMillis) ?: return null
        val rttMillis = ((nowNanos - sentNanos) / 1_000_000L).toInt()
        if (rttMillis < 0) return null
        lastServerRttMs = rttMillis
        if (minServerRttMs < 0 || rttMillis < minServerRttMs) minServerRttMs = rttMillis
        if (rttMillis > maxServerRttMs) maxServerRttMs = rttMillis
        totalRttMs += rttMillis
        rttCount++
        rtts.addLast(rttMillis)
        if (rtts.size > capacity) rtts.removeFirst()
        return rttMillis
    }

    fun averageServerRttMs(): Int = if (rttCount == 0L) -1 else (totalRttMs / rttCount).toInt()

    /** Most recent samples, oldest first — the raw material for a sparkline. */
    fun samples(): List<Int> = rtts.toList()

    fun reset() {
        pending.clear()
        rtts.clear()
        lastServerRttMs = -1
        minServerRttMs = -1
        maxServerRttMs = -1
        totalRttMs = 0
        rttCount = 0
    }

    companion object {
        const val UNCONNECTED_PING = 0x01
        const val UNCONNECTED_PONG = 0x1C
        const val CONNECTED_PING = 0x00
        const val CONNECTED_PONG = 0x03

        /** The Echo timestamp of a RakNet ping, or null when this is not a ping. */
        fun pingEcho(buffer: ByteArray, offset: Int, length: Int): Long? {
            if (length < 9) return null
            val id = buffer[offset].toInt() and 0xFF
            if (id != UNCONNECTED_PING && id != CONNECTED_PING) return null
            return readInt64BE(buffer, offset + 1)
        }

        /** The Echo timestamp of a RakNet pong, or null when this is not a pong. */
        fun pongEcho(buffer: ByteArray, offset: Int, length: Int): Long? {
            if (length < 9) return null
            val id = buffer[offset].toInt() and 0xFF
            if (id != UNCONNECTED_PONG && id != CONNECTED_PONG) return null
            return readInt64BE(buffer, offset + 1)
        }

        /** RakNet writes these timestamps big-endian. */
        fun readInt64BE(buffer: ByteArray, offset: Int): Long {
            var value = 0L
            for (i in 0 until 8) {
                value = (value shl 8) or (buffer[offset + i].toLong() and 0xFF)
            }
            return value
        }
    }
}
