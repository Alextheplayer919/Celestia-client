package com.proxy.mcbedrock.net

import android.net.VpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * One NAT session = one real UDP socket standing in for a single
 * (device app socket) -> (real destination) flow.
 *
 * The device's Minecraft app thinks it's talking on VPN_ADDRESS (10.0.0.2);
 * this session owns the actual socket that talks to the real server, and
 * relays replies back into the TUN interface so they look like they came
 * from the real server, addressed to the device.
 */
class UdpNatSession(
    vpnService: VpnService,
    private val scope: CoroutineScope,
    private val tunOutput: FileOutputStream,
    private val tunWriteLock: Mutex,
    val clientPort: Int,
    val remoteAddress: InetAddress,
    val remotePort: Int
) {
    private val socket = DatagramSocket(0).also { vpnService.protect(it) }
    private var readJob: Job? = null

    @Volatile
    var lastActivityMillis: Long = System.currentTimeMillis()
        private set

    fun start() {
        readJob = scope.launch {
            val recvBuffer = ByteArray(32767)
            val recvPacket = DatagramPacket(recvBuffer, recvBuffer.size)
            try {
                while (true) {
                    socket.receive(recvPacket)
                    lastActivityMillis = System.currentTimeMillis()

                    // TODO: this is where a read-only Bedrock protocol
                    // decode step plugs in for the overlay/stats features —
                    // decode recvBuffer[0, recvPacket.length) here without
                    // altering it, before relaying downstream.

                    val reply = buildIpv4Udp(
                        fromAddress = remoteAddress,
                        fromPort = remotePort,
                        toAddress = InetAddress.getByName(VPN_CLIENT_ADDRESS),
                        toPort = clientPort,
                        payload = recvBuffer,
                        payloadLength = recvPacket.length
                    )

                    tunWriteLock.withLock {
                        tunOutput.write(reply)
                    }
                }
            } catch (_ : Exception) {
                // Socket closed (session expired) or VPN torn down — normal
                // shutdown path, nothing to log as an error here.
            }
        }
    }

    /** Send an upstream (device -> real server) payload as a straight passthrough. */
    fun sendUpstream(buffer: ByteArray, offset: Int, length: Int) {
        lastActivityMillis = System.currentTimeMillis()
        val packet = DatagramPacket(buffer, offset, length, remoteAddress, remotePort)
        socket.send(packet)
    }

    fun close() {
        readJob?.cancel()
        socket.close()
    }

    companion object {
        // Must match MinecraftVpnService's VPN_ADDRESS.
        const val VPN_CLIENT_ADDRESS = "10.0.0.2"
    }
}
