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
        DatagramSocket(0).also { vpnService.protect(it) }
    } catch (e: Exception) {
        // Without a protected socket this flow cannot be relayed at all.
        null
    }

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

    fun start() {
        val active = socket ?: return
        readJob = scope.launch {
            val recvBuffer = ByteArray(MAX_UDP_PAYLOAD)
            val recvPacket = DatagramPacket(recvBuffer, recvBuffer.size)
            try {
                while (true) {
                    active.receive(recvPacket)
                    lastActivityMillis = System.currentTimeMillis()

                    val length = recvPacket.length
                    if (length <= 0) continue

                    // Read-only inspection of the downstream bytes, before relaying.
                    inspector.onDownstream(recvBuffer, 0, length)

                    // A datagram bigger than the tunnel's MTU cannot be handed to
                    // the app intact, so it is counted and dropped rather than
                    // written as a truncated (and therefore corrupt) packet.
                    if (IPV4_HEADER_LEN + UDP_HEADER_LEN + length > tunMtu) {
                        oversizedReplies++
                        onReplyTooLarge()
                        continue
                    }

                    val reply = buildIpv4Udp(
                        fromAddress = remoteAddress,
                        fromPort = remotePort,
                        toAddress = InetAddress.getByName(VPN_CLIENT_ADDRESS),
                        toPort = clientPort,
                        payload = recvBuffer,
                        payloadLength = length
                    )

                    tunWriteLock.lock()
                    try {
                        tunOutput.write(reply)
                    } finally {
                        tunWriteLock.unlock()
                    }
                }
            } catch (e: IOException) {
                // Socket closed (session reaped) or the VPN went away: normal.
            } catch (e: Exception) {
                // Anything else must not escape a launched coroutine, or the
                // process would crash with a relay error.
                relayErrors++
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
            active.send(DatagramPacket(buffer, offset, length, remoteAddress, remotePort))
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

    companion object {
        // Must match MinecraftVpnService's VPN_ADDRESS.
        const val VPN_CLIENT_ADDRESS = "10.0.0.2"
        private const val MAX_UDP_PAYLOAD = 65_535 - 8
        private const val IPV4_HEADER_LEN = 20
        private const val UDP_HEADER_LEN = 8
    }
}
