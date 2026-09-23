package com.proxy.mcbedrock.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Finds Minecraft servers on the local network by broadcasting the same
 * unconnected ping the game's own server list sends, and reading the pongs.
 *
 * Read-only and local: one UDP socket, one broadcast per port, replies are only
 * parsed. It exists so the target server can be picked instead of typed — the
 * same discovery the game itself uses for "Visible to LAN players" worlds, which
 * means a phone hosting a world on the same network shows up like any other
 * server.
 *
 * The packet building/parsing halves are pure functions so they can be tested
 * against [RakNet]'s parser; only [scan] touches a socket.
 */
object LanDiscovery {

    /** Bedrock listens on 19132, and 19133 for a second world on the same host. */
    val DEFAULT_PORTS = listOf(19132, 19133)

    /** RakNet's unconnected magic, as parsed by [RakNet]. */
    private val MAGIC = byteArrayOf(
        0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00,
        0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(),
        0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(),
        0x12, 0x34, 0x56, 0x78
    )

    /** Unconnected ping: id, time, magic, client GUID — the shape go-raknet documents. */
    fun buildUnconnectedPing(timeMillis: Long, clientGuid: Long): ByteArray {
        val out = ByteArray(33)
        out[0] = 0x01
        for (i in 0 until 8) out[1 + i] = ((timeMillis shr (8 * (7 - i))) and 0xFF).toByte()
        System.arraycopy(MAGIC, 0, out, 9, MAGIC.size)
        for (i in 0 until 8) out[25 + i] = ((clientGuid shr (8 * (7 - i))) and 0xFF).toByte()
        return out
    }

    /**
     * Parses an unconnected pong into a [ServerTarget]. Returns null when the
     * payload is not a pong, so a busy socket can be filtered cheaply.
     */
    fun parsePong(payload: ByteArray, offset: Int, length: Int, port: Int, nowMillis: Long): ServerTarget? {
        if (length < 35) return null
        if ((payload[offset].toInt() and 0xFF) != 0x1C) return null
        // id + time(8) + guid(8) + magic(16) + motd length(2) + motd
        val motdLengthOffset = offset + 33
        if (motdLengthOffset + 2 > offset + length) return null
        val motdLength = ((payload[motdLengthOffset].toInt() and 0xFF) shl 8) or
            (payload[motdLengthOffset + 1].toInt() and 0xFF)
        val motdOffset = motdLengthOffset + 2
        if (motdLength <= 0 || motdOffset + motdLength > offset + length) return null
        val raw = String(payload, motdOffset, motdLength, Charsets.UTF_8)
        val advert = ServerAdvertisement.parse(raw) ?: return null
        return ServerTarget(
            host = "",
            port = port,
            motd = advert.motd,
            mcVersion = advert.mcVersion,
            protocolVersion = advert.protocolVersion,
            players = advert.players,
            maxPlayers = advert.maxPlayers,
            lastSeenAtMillis = nowMillis
        )
    }

    /**
     * Broadcasts on [ports] and collects replies until [timeoutMillis] has
     * passed. Blocking: call it off the UI thread. Hosts are filled in from the
     * replies' source address, de-duplicated by host:port.
     */
    fun scan(
        timeoutMillis: Int = 1500,
        ports: List<Int> = DEFAULT_PORTS,
        clientGuid: Long = (Math.random() * Long.MAX_VALUE).toLong(),
        nowMillis: () -> Long = System::currentTimeMillis
    ): List<ServerTarget> {
        val found = LinkedHashMap<String, ServerTarget>()
        val socket = DatagramSocket(null)
        try {
            socket.reuseAddress = true
            socket.broadcast = true
            socket.soTimeout = 250
            socket.bind(InetSocketAddress(0))

            val ping = buildUnconnectedPing(nowMillis(), clientGuid)
            val destinations = ArrayList<InetAddress>()
            destinations += InetAddress.getByName("255.255.255.255")
            runCatching { InetAddress.getByName("239.255.255.250") } // harmless extra, usually ignored
            val deadline = nowMillis() + timeoutMillis
            for (port in ports) {
                for (address in destinations) {
                    runCatching { socket.send(DatagramPacket(ping, ping.size, address, port)) }
                }
            }

            val buffer = ByteArray(4096)
            while (nowMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                }
                val target = parsePong(packet.data, packet.offset, packet.length, packet.port, nowMillis()) ?: continue
                val host = packet.address?.hostAddress ?: continue
                val key = "$host:${target.port}"
                found[key] = target.copy(host = host)
            }
        } catch (_: Exception) {
            // Scanning is best effort: no permission, no network, or no reply all
            // mean the same thing to the caller — an empty list.
        } finally {
            runCatching { socket.close() }
        }
        return found.values.toList()
    }
}
