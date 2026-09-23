package com.proxy.mcbedrock

/**
 * A single place where the running service publishes what it is seeing, so the
 * activity and the notification can read it without holding a reference to the
 * service or passing anything across a binder.
 *
 * The service is the only writer ([publish]) and it writes whole immutable
 * snapshots, so readers always see a consistent picture.
 */
object StatsRegistry {

    @Volatile
    var latest: ServiceStats = ServiceStats.idle()
        private set

    fun publish(stats: ServiceStats) {
        latest = stats
    }

    fun reset() {
        latest = ServiceStats.idle()
    }
}

/** Per-flow view of one relayed connection, ready for display. */
data class FlowView(
    val remoteLabel: String,
    val phase: String,
    val serverDescription: String,
    val rttLastMs: Int,
    val rttMinMs: Int,
    val rttAvgMs: Int,
    val rttMaxMs: Int,
    val jitterMs: Int,
    val arrivalJitterMs: Int,
    val upstreamLossPermille: Int,
    val downstreamLossPermille: Int,
    val upstreamBytes: Long,
    val downstreamBytes: Long,
    val packets: Long,
    val mtu: Int,
    val raknetProtocol: Int,
    val encryptionStarted: Boolean,
    /** Client's own login claim, e.g. `Alex · login 1.26.40 (protocol 2168)`. */
    val loginDescription: String,
    /** Client vs server protocol comparison, or null when nothing is known yet. */
    val protocolComparison: String?,
    /** How the handshake protects the session, and where inspection stops. */
    val encryptionDescription: String,
    val idleSeconds: Long,

    /** Work the relay itself did per packet: the cost the player pays for the proxy. */
    val relayOverheadAvgMs: Double = 0.0,
    val relayOverheadP95Ms: Double = 0.0,
    /** RTT to the server measured at the relay's own socket, not the client's. */
    val serverRttLastMs: Int = -1,
    val serverRttMinMs: Int = -1,
    val serverRttAvgMs: Int = -1,
    val serverRttMaxMs: Int = -1,
)

/** Aggregate state of the proxy for the UI and the notification. */
data class ServiceStats(
    val vpnEstablished: Boolean,
    /** `play.example.com:19132`, or empty when nothing was chosen. */
    val targetLabel: String = "",
    /** Human sentence describing what the tunnel captures. */
    val scopeDescription: String = "",
    /** True when only the target server's address is routed into the tunnel. */
    val scopedToTarget: Boolean = false,
    /** Addresses the target resolved to, when scoped. */
    val resolvedAddresses: List<String> = emptyList(),
    /** `Minecraft 1.26.40 (com.mojang.minecraftpe)`. */
    val appDescription: String = "",
    val flows: List<FlowView>,
    val totalUpstreamBytes: Long,
    val totalDownstreamBytes: Long,
    val upstreamBytesPerSecond: Long,
    val downstreamBytesPerSecond: Long,
    val droppedNonUdp: Long,
    val icmpRejectionsSent: Long,
    val relayErrors: Long,
    val oversizedReplies: Long,
    val lastError: String?
) {
    val bestRttMs: Int get() = flows.map { it.rttLastMs }.filter { it >= 0 }.minOrNull() ?: -1
    val worstLossPermille: Int
        get() = flows.maxOfOrNull { maxOf(it.upstreamLossPermille, it.downstreamLossPermille) } ?: 0

    companion object {
        fun idle() = ServiceStats(
            vpnEstablished = false,
            flows = emptyList(),
            totalUpstreamBytes = 0,
            totalDownstreamBytes = 0,
            upstreamBytesPerSecond = 0,
            downstreamBytesPerSecond = 0,
            droppedNonUdp = 0,
            icmpRejectionsSent = 0,
            relayErrors = 0,
            oversizedReplies = 0,
            lastError = null
        )
    }
}

/** Small formatting helpers shared by the UI and the notification. */
object Format {

    fun bytes(value: Long): String = when {
        value >= 1024L * 1024L * 1024L -> "%.2f GiB".format(value / (1024.0 * 1024.0 * 1024.0))
        value >= 1024L * 1024L -> "%.1f MiB".format(value / (1024.0 * 1024.0))
        value >= 1024L -> "%.1f KiB".format(value / 1024.0)
        else -> "$value B"
    }

    fun rate(bytesPerSecond: Long): String = bytes(bytesPerSecond) + "/s"

    fun loss(permille: Int): String = "%.1f%%".format(permille / 10.0)

    fun rtt(millis: Int): String = if (millis < 0) "—" else "${millis}ms"
}
