package com.proxy.mcbedrock.net

import android.net.VpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * One NAT session = one real UDP socket standing in for a single
 * (device app socket) -> (real destination) flow.
 *
 * The device's Minecraft app believes it is talking to the real server; this
 * session owns the actual socket and relays datagrams in both directions. The
 * upstream direction is a byte-for-byte passthrough — nothing here rewrites,
 * reorders or delays what the client sends (see the scope note in
 * [MinecraftVpnService]).
 *
 * ##### Why this file is written the way it is
 *
 * Chunk loading is throughput-bound: chunks are the highest-priority data the
 * server sends and everything else waits behind them, so every datagram this relay
 * drops or delays pushes the client's chunk fill back by at least one RakNet
 * retransmit. Three consequences, all handled here:
 *
 *  - **Buffers.** The socket receive buffer is raised so a burst of chunk datagrams
 *    survives while this loop is busy. A kernel-level drop is invisible to us and
 *    costs a retransmit; a bigger queue is the cheapest fix available in userspace.
 *  - **Allocation.** The reply packet is built into a reused buffer. At chunk-load
 *    rates this path runs thousands of times a second on a device that is also
 *    rendering, and per-packet garbage means GC pauses on the game's threads too.
 *  - **Ordering.** Inspection runs *after* the bytes have been written to the
 *    tunnel. Reading a packet is never on the path that delivers it.
 *
 * Lifetime rules that the rest of the app depends on:
 *  - [isOpen] is false as soon as the socket is closed, so the relay loop can tell
 *    a stale entry from a live one instead of writing into a dead socket;
 *  - every failure path returns a value instead of throwing into the relay loop,
 *    because an exception there would take down the whole VPN.
 */
class UdpNatSession(
    vpnService: VpnService,
    private val scope: CoroutineScope,
    private val tunOutput: FileOutputStream,
    private val tunWriteLock: kotlinx.coroutines.sync.Mutex,
    val clientPort: Int,
    val remoteAddress: InetAddress,
    val remotePort: Int,
    val inspector: BedrockFlowInspector,
    private val tunMtu: Int,
    private val onReplyTooLarge: () -> Unit = {}
) {

    private val socket: DatagramSocket? = try {
        DatagramSocket(0).also { vpnService.protect(it) }.also { tune(it) }
    } catch (e: Exception) {
        // Without a protected socket this flow cannot be relayed at all.
        null
    }

    /** Relay cost per packet, and the server-leg RTT seen at this socket. */
    val overhead = OverheadTracker()
    val pingLedger = PingLedger()

    /** TUN writes to a given session are serialised, so one buffer is enough. */
    private val replyBuffer = ByteArray(IPV4_HEADER_LEN + UDP_HEADER_LEN + MAX_UDP_PAYLOAD)

    private var readJob: Job? = null

    /** False once closed — checked before handing this session any more packets. */
    val isOpen: Boolean get() = socket != null && !socket.isClosed

    @Volatile
    var lastActivityMillis: Long = System.currentTimeMillis()
        private set

    @Volatile
    var relayErrors: Long = 0
        private set

    @Volatile
    var oversizedReplies: Long = 0
        private set

    /** Socket buffers actually granted by the kernel, for the UI to report honestly. */
    @Volatile
    var receiveBufferBytes: Int = 0
        private set

    @Volatile
    var sendBufferBytes: Int = 0
        private set

    @Volatile
    private var clientAddress: InetAddress? = null

    fun start() {
        val active = socket ?: return
        // One thread, no coroutine scheduling per packet: this loop is the hottest
        // path in the app and the game is waiting on it.
        readJob = scope.launch {
            val recvBuffer = ByteArray(MAX_UDP_PAYLOAD)
            val recvPacket = DatagramPacket(recvBuffer, recvBuffer.size)
            while (true) {
                active.receive(recvPacket)
                lastActivityMillis = System.currentTimeMillis()

                val length = recvPacket.length

                // Timestamps for RTT are taken here, before any work: the relay must
                // not flatter its own numbers.
                val arrivedNanos = System.nanoTime()
                val echo = if (length > 0) PingLedger.pongEcho(recvBuffer, 0, length) else null
                if (echo != null) pingLedger.onPongForwarded(echo, arrivedNanos)

                if (length <= 0) continue

                // A datagram bigger than the tunnel's MTU cannot be handed to the app
                // intact, so it is counted and dropped rather than written as a
                // truncated (and therefore corrupt) packet.
                if (IPV4_HEADER_LEN + UDP_HEADER_LEN + length > tunMtu) {
                    oversizedReplies++
                    onReplyTooLarge()
                    continue
                }

                val target = clientAddressFor()
                val started = System.nanoTime()
                val packetLength = buildIpv4UdpInto(
                    destination = replyBuffer,
                    fromAddress = remoteAddress,
                    fromPort = remotePort,
                    toAddress = target,
                    toPort = clientPort,
                    payload = recvBuffer,
                    payloadLength = length
                )
                if (packetLength > 0) {
                    tunWriteLock.lock()
                    try {
                        tunOutput.write(replyBuffer, 0, packetLength)
                    } finally {
                        tunWriteLock.unlock()
                    }
                    overhead.record(System.nanoTime() - started)
                }

                // Inspection last: reading a packet is never on the path that
                // delivers it to the game. The overhead timer above deliberately
                // stops before this line, so the number reported to the user is the
                // cost the game actually pays.
                inspector.onDownstream(recvBuffer, 0, length)
            }
        }
    }

    /**
     * Forwards a client datagram upstream, unchanged. Returns false if the packet
     * could not be sent, which the caller only uses for statistics — a dropped
     * packet here is preferable to an exception that stops the relay.
     */
    fun sendUpstream(buffer: ByteArray, offset: Int, length: Int): Boolean {
        val active = socket ?: return false
        lastActivityMillis = System.currentTimeMillis()
        return try {
            val pingEcho = PingLedger.pingEcho(buffer, offset, length)
            active.send(DatagramPacket(buffer, offset, length, remoteAddress, remotePort))
            if (pingEcho != null) pingLedger.onPingForwarded(pingEcho, System.nanoTime())
            true
        } catch (e: IOException) {
            relayErrors++
            false
        }
    }

    fun close() {
        readJob?.cancel()
        readJob = null
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    /** The tunnel's own address, resolved once instead of per packet. */
    private fun clientAddressFor(): InetAddress {
        clientAddress?.let { return it }
        val resolved = InetAddress.getByName(VPN_CLIENT_ADDRESS)
        clientAddress = resolved
        return resolved
    }

    private companion object {
        // Must match MinecraftVpnService's VPN_ADDRESS.
        const val VPN_CLIENT_ADDRESS = "10.0.0.2"
        private const val MAX_UDP_PAYLOAD = 65_535 - 8
        private const val IPV4_HEADER_LEN = 20
        private const val UDP_HEADER_LEN = 8

        /**
         * Ask for room for a chunk burst. The kernel may cap this; whatever it grants
         * is reported back through [receiveBufferBytes] rather than assumed.
         */
        const val DESIRED_RECEIVE_BUFFER = 1 shl 20
        const val DESIRED_SEND_BUFFER = 1 shl 19

        /** IPTOS_LOWDELAY (0x10) | IPTOS_THROUGHPUT (0x08): real-time game traffic. */
        const val TRAFFIC_CLASS = 0x18

        fun tune(socket: DatagramSocket) {
            runCatching { socket.receiveBufferSize = DESIRED_RECEIVE_BUFFER }
            runCatching { socket.sendBufferSize = DESIRED_SEND_BUFFER }
            // Wi-Fi WMM maps TOS/DSCP onto its access categories, so marking game
            // traffic low-delay keeps it out of the best-effort queue when the link
            // is busy — the moment it matters most.
            runCatching { socket.trafficClass = TRAFFIC_CLASS }
        }
    }

    /** Fills in the granted buffer sizes; called once the session is live. */
    private fun recordBufferSizes() {
        val active = socket ?: return
        receiveBufferBytes = runCatching { active.receiveBufferSize }.getOrDefault(0)
        sendBufferBytes = runCatching { active.sendBufferSize }.getOrDefault(0)
    }

    init {
        recordBufferSizes()
    }
}
