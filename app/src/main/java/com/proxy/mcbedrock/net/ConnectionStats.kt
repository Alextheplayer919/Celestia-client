package com.proxy.mcbedrock.net

/**
 * Per-flow connection statistics, derived only from packets we already see.
 *
 * Concurrency: the relay loop writes the upstream side and the session's socket
 * reader writes the downstream side, so every field here has exactly one writer
 * and is @Volatile — enough for the UI/notification threads to read consistent
 * values without locking on the packet path.
 *
 * Loss is measured the way RakNet itself measures it: a NACK means "I did not
 * receive these datagram sequence numbers", an ACK means "I did". So
 * missing / (missing + acknowledged) is the peer's own view of the wire loss on
 * that direction — far more meaningful than counting gaps ourselves, because
 * RakNet retransmits (a retransmit looks like a gap to a naive observer but was
 * recovered).
 */
class ConnectionStats(private val clock: () -> Long = System::currentTimeMillis) {

    private val lock = Any()

    // --- throughput counters ---
    @Volatile var upstreamPackets: Long = 0; private set
    @Volatile var upstreamBytes: Long = 0; private set
    @Volatile var downstreamPackets: Long = 0; private set
    @Volatile var downstreamBytes: Long = 0; private set

    // --- RTT (from RakNet ConnectedPing/ConnectedPong echo pairs) ---
    private val rttWindow = IntArray(RTT_WINDOW)
    private var rttCount = 0
    private var rttIndex = 0

    @Volatile var rttLastMs: Int = -1; private set
    @Volatile var jitterMs: Int = 0; private set

    // --- datagram sequence tracking (wire gaps, reordering, duplicates) ---
    private var upFirstSeq = -1
    private var upLastSeq = -1
    private var upSeen = 0
    private var upGapEvents = 0
    private var upLateOrDuplicate = 0
    private var downFirstSeq = -1
    private var downLastSeq = -1
    private var downSeen = 0
    private var downGapEvents = 0
    private var downLateOrDuplicate = 0

    // --- acknowledged / missing datagrams, as reported by the peer ---
    private var upAcknowledged = 0L
    private var upMissing = 0L
    private var downAcknowledged = 0L
    private var downMissing = 0L

    // --- downstream arrival jitter (how evenly packets land on this device) ---
    @Volatile var arrivalJitterMs: Int = 0; private set
    private var lastArrivalAt = 0L
    private var arrivalMean = 0.0
    private var arrivalCount = 0

    @Volatile var gameBatchesUp: Long = 0; private set
    @Volatile var gameBatchesDown: Long = 0; private set

    fun recordUpstreamPacket(bytes: Int) {
        upstreamPackets++
        upstreamBytes += bytes
    }

    fun recordDownstreamPacket(bytes: Int) {
        downstreamPackets++
        downstreamBytes += bytes
        recordArrival()
    }

    /**
     * Called with the RTT implied by a ConnectedPing→ConnectedPong pair: the pong
     * echoes the ping's timestamp, so (now - echoed) is an end-to-end RTT sample.
     */
    fun recordRtt(rttMs: Int) {
        if (rttMs < 0 || rttMs > MAX_PLAUSIBLE_RTT_MS) return
        synchronized(lock) {
            val previous = rttLastMs
            rttLastMs = rttMs
            rttWindow[rttIndex] = rttMs
            rttIndex = (rttIndex + 1) % rttWindow.size
            if (rttCount < rttWindow.size) rttCount++

            if (previous >= 0) {
                val delta = kotlin.math.abs(rttMs - previous)
                // RFC 3550-style smoothing, expressed as an integer EWMA.
                jitterMs = if (jitterMs == 0) delta else (jitterMs * JITTER_EWMA_OLD + delta * JITTER_EWMA_NEW) / JITTER_EWMA_DIV
            }
        }
    }

    private fun recordArrival() {
        val now = clock()
        val last = lastArrivalAt
        lastArrivalAt = now
        if (last == 0L) return
        val gap = (now - last).toDouble()
        arrivalCount++
        if (arrivalCount == 1) {
            arrivalMean = gap
            return
        }
        val deviation = kotlin.math.abs(gap - arrivalMean)
        arrivalMean = arrivalMean + (gap - arrivalMean) / ARRIVAL_MEAN_ALPHA_DIV
        val jitter = deviation.toInt()
        arrivalJitterMs = if (arrivalJitterMs == 0) jitter else (arrivalJitterMs * JITTER_EWMA_OLD + jitter * JITTER_EWMA_NEW) / JITTER_EWMA_DIV
    }

    fun recordDatagramSequence(sequence: Int, upstream: Boolean) {
        synchronized(lock) {
            if (upstream) {
                if (upFirstSeq < 0) {
                    upFirstSeq = sequence; upLastSeq = sequence; upSeen = 1; return
                }
                if (sequence > upLastSeq) {
                    if (sequence != upLastSeq + 1) upGapEvents++
                    upLastSeq = sequence
                    upSeen++
                } else {
                    upLateOrDuplicate++
                }
            } else {
                if (downFirstSeq < 0) {
                    downFirstSeq = sequence; downLastSeq = sequence; downSeen = 1; return
                }
                if (sequence > downLastSeq) {
                    if (sequence != downLastSeq + 1) downGapEvents++
                    downLastSeq = sequence
                    downSeen++
                } else {
                    downLateOrDuplicate++
                }
            }
        }
    }

    /** [units] = how many datagram sequence numbers this ACK/NACK covers. */
    fun recordAcknowledgement(ack: Boolean, units: Int, upstream: Boolean) {
        if (units <= 0) return
        synchronized(lock) {
            if (upstream) {
                if (ack) upAcknowledged += units else upMissing += units
            } else {
                if (ack) downAcknowledged += units else downMissing += units
            }
        }
    }

    fun recordGameBatch(upstream: Boolean) {
        if (upstream) gameBatchesUp++ else gameBatchesDown++
    }

    fun snapshot(): StatsSnapshot = synchronized(lock) {
        var min = Int.MAX_VALUE
        var max = 0
        var sum = 0L
        for (i in 0 until rttCount) {
            val v = rttWindow[i]
            if (v < min) min = v
            if (v > max) max = v
            sum += v
        }
        val upExpected = if (upFirstSeq >= 0 && upLastSeq >= upFirstSeq) (upLastSeq - upFirstSeq + 1) else 0
        val downExpected = if (downFirstSeq >= 0 && downLastSeq >= downFirstSeq) (downLastSeq - downFirstSeq + 1) else 0

        StatsSnapshot(
            upstreamPackets = upstreamPackets,
            upstreamBytes = upstreamBytes,
            downstreamPackets = downstreamPackets,
            downstreamBytes = downstreamBytes,
            rttLastMs = rttLastMs,
            rttMinMs = if (rttCount > 0) min else -1,
            rttAvgMs = if (rttCount > 0) (sum / rttCount).toInt() else -1,
            rttMaxMs = if (rttCount > 0) max else -1,
            jitterMs = jitterMs,
            arrivalJitterMs = arrivalJitterMs,
            upstreamLossPermille = permille(upMissing, upAcknowledged),
            downstreamLossPermille = permille(downMissing, downAcknowledged),
            upstreamWireGapPermille = permille((upExpected - upSeen).toLong().coerceAtLeast(0), upExpected.toLong()),
            downstreamWireGapPermille = permille((downExpected - downSeen).toLong().coerceAtLeast(0), downExpected.toLong()),
            upstreamGapEvents = upGapEvents,
            upstreamLateOrDuplicate = upLateOrDuplicate,
            downstreamGapEvents = downGapEvents,
            downstreamLateOrDuplicate = downLateOrDuplicate,
            gameBatchesUp = gameBatchesUp,
            gameBatchesDown = gameBatchesDown
        )
    }

    private fun permille(missing: Long, acknowledged: Long): Int {
        val total = missing + acknowledged
        if (total <= 0) return 0
        return ((missing * 1000) / total).toInt().coerceIn(0, 1000)
    }

    companion object {
        private const val RTT_WINDOW = 64
        private const val MAX_PLAUSIBLE_RTT_MS = 60_000
        private const val JITTER_EWMA_OLD = 15
        private const val JITTER_EWMA_NEW = 1
        private const val JITTER_EWMA_DIV = 16
        private const val ARRIVAL_MEAN_ALPHA_DIV = 16
    }
}

/** Immutable snapshot of [ConnectionStats] for display, all values already reduced. */
data class StatsSnapshot(
    val upstreamPackets: Long,
    val upstreamBytes: Long,
    val downstreamPackets: Long,
    val downstreamBytes: Long,
    val rttLastMs: Int,
    val rttMinMs: Int,
    val rttAvgMs: Int,
    val rttMaxMs: Int,
    val jitterMs: Int,
    val arrivalJitterMs: Int,
    /** Peer-reported missing-datagram ratio, parts per thousand. */
    val upstreamLossPermille: Int,
    val downstreamLossPermille: Int,
    val upstreamWireGapPermille: Int,
    val downstreamWireGapPermille: Int,
    /** Discontinuities while advancing through the sequence space (a gap or a jump). */
    val upstreamGapEvents: Int,
    /** Datagrams that arrived late or twice — wire reordering, not loss. */
    val upstreamLateOrDuplicate: Int,
    val downstreamGapEvents: Int,
    val downstreamLateOrDuplicate: Int,
    val gameBatchesUp: Long,
    val gameBatchesDown: Long
) {
    fun lossPercent(permille: Int): String = "%.1f%%".format(permille / 10.0)

    companion object {
        val EMPTY = StatsSnapshot(
            0, 0, 0, 0, -1, -1, -1, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        )
    }
}
