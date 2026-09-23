package com.proxy.mcbedrock.net

import java.net.InetAddress

/**
 * Connection lifecycle of a single relayed flow, as far as it can be observed
 * from the outside.
 */
enum class ConnectionPhase {
    /** Nothing recognised yet. */
    IDLE,

    /** Server-list style ping (unconnected ping/pong) — no session yet. */
    SERVER_PING,

    /** RakNet handshake: MTU discovery, connection request/accept. */
    RAKNET_HANDSHAKE,

    /** RakNet is up and the Bedrock login exchange is in flight (still cleartext). */
    GAME_LOGIN,

    /** ServerToClientHandshake seen: everything after this is encrypted. */
    ENCRYPTED,

    /** StartGame seen — the session became a real game session. */
    PLAY,

    /** Disconnect notification observed. */
    DISCONNECTED
}

/**
 * Read-only observer for one (client socket → server) flow.
 *
 * This is where packet inspection lives, and it is deliberately side-effect free
 * with respect to the traffic: it reads the bytes a flow is already sending and
 * updates statistics/metadata. It never returns anything that the relay would
 * write back, so it cannot alter timing or content of what reaches the server.
 *
 * What is observable *without* decrypting anything (and why):
 *   - RakNet handshake details: MTU, the client's RakNet protocol version.
 *   - Server metadata from the unconnected pong (MOTD, version, player counts).
 *   - RTT, jitter, peer-reported packet loss from RakNet ping/pong and ACK/NACK.
 *   - The *shape* of game traffic: batch counts and sizes.
 *   - Which connection phase the session is in, because the login packets
 *     (Login, ServerToClientHandshake, ClientToServerHandshake) are exchanged in
 *     cleartext before encryption is negotiated.
 *
 * What is NOT observable, by construction: the contents of game packets after
 * the handshake. Bedrock switches to AES once the handshake completes, and the
 * session key is derived from an ECDH exchange between the client and the
 * server. A passive observer sees both public keys but holds neither private
 * key, so the key — and therefore coordinates, chat, inventory, entity data —
 * cannot be recovered without terminating (and re-originating) the session. See
 * docs/decode-research.md.
 */
class BedrockFlowInspector(
    val remoteAddress: InetAddress,
    val remotePort: Int,
    val clientPort: Int,
    val stats: ConnectionStats = ConnectionStats(),
    private val clock: () -> Long = System::currentTimeMillis
) {

    @Volatile var phase: ConnectionPhase = ConnectionPhase.IDLE
        private set

    @Volatile var raknetClientProtocol: Int = -1
        private set

    @Volatile var negotiatedMtu: Int = -1
        private set

    @Volatile var serverMotd: String? = null
        private set
    @Volatile var serverVersion: String? = null
        private set
    @Volatile var serverProtocol: Int = -1
        private set
    @Volatile var serverPlayers: Int = -1
        private set
    @Volatile var serverMaxPlayers: Int = -1
        private set

    @Volatile var encryptionStarted: Boolean = false
        private set

    /** True once we saw a batch we could not read as cleartext packets — i.e. compression/encryption kicked in. */
    @Volatile var opaqueTrafficSeen: Boolean = false
        private set

    @Volatile var upstreamPackets: Long = 0
        private set
    @Volatile var downstreamPackets: Long = 0
        private set
    @Volatile var unrecognisedUpstream: Long = 0
        private set
    @Volatile var unrecognisedDownstream: Long = 0
        private set

    /** Last few Bedrock packet IDs seen in cleartext, newest last. Handy for debugging. */
    val recentCleartextPacketIds = ArrayDeque<Int>()

    fun onUpstream(payload: ByteArray, offset: Int, length: Int) {
        upstreamPackets++
        stats.recordUpstreamPacket(length)
        val message = RakNet.parse(payload, offset, length)
        if (message == null) {
            unrecognisedUpstream++
            return
        }
        handle(message, payload, upstream = true)
    }

    fun onDownstream(payload: ByteArray, offset: Int, length: Int) {
        downstreamPackets++
        stats.recordDownstreamPacket(length)
        val message = RakNet.parse(payload, offset, length)
        if (message == null) {
            unrecognisedDownstream++
            return
        }
        handle(message, payload, upstream = false)
    }

    private fun handle(message: RakNetMessage, buffer: ByteArray, upstream: Boolean) {
        when (message) {
            is UnconnectedPing -> {
                if (upstream && phase == ConnectionPhase.IDLE) phase = ConnectionPhase.SERVER_PING
            }

            is UnconnectedPong -> {
                if (!upstream) {
                    parseServerAdvertisement(message.motd)
                    if (phase == ConnectionPhase.IDLE || phase == ConnectionPhase.SERVER_PING) {
                        phase = ConnectionPhase.SERVER_PING
                    }
                }
            }

            is OpenConnectionRequest1 -> {
                raknetClientProtocol = message.clientProtocol
                negotiatedMtu = maxOf(negotiatedMtu, message.mtu)
                if (phase == ConnectionPhase.IDLE || phase == ConnectionPhase.SERVER_PING) {
                    phase = ConnectionPhase.RAKNET_HANDSHAKE
                }
            }

            is OpenConnectionReply1 -> {
                if (message.mtu > 0) negotiatedMtu = maxOf(negotiatedMtu, message.mtu)
                if (phase == ConnectionPhase.IDLE || phase == ConnectionPhase.SERVER_PING) {
                    phase = ConnectionPhase.RAKNET_HANDSHAKE
                }
            }

            is ConnectedPing -> {
                // ConnectedPing carries the sender's own clock reading. Only the
                // client's ping is useful to us: it is sent from this very device,
                // so the echoed timestamp shares our clock and (now - echoed) is a
                // genuine RTT. The server's ping uses the server's clock, which we
                // cannot compare against ours — the server measures that RTT itself.
                if (upstream) clientPingSentAt = message.pingTimeMillis
            }

            is ConnectedPong -> {
                // A pong travelling downstream is the server echoing the client's
                // ping, so RTT = our clock now - the echoed client timestamp.
                if (!upstream) {
                    val sent = clientPingSentAt
                    if (sent > 0 && message.pingTimeMillis == sent) {
                        stats.recordRtt((clock() - sent).toInt())
                        clientPingSentAt = -1
                    }
                }
            }

            is ConnectionControl -> {
                when (message.id) {
                    RakNet.ID_CONNECTION_REQUEST,
                    RakNet.ID_CONNECTION_REQUEST_ACCEPTED,
                    RakNet.ID_NEW_INCOMING_CONNECTION -> {
                        if (phase == ConnectionPhase.IDLE ||
                            phase == ConnectionPhase.SERVER_PING ||
                            phase == ConnectionPhase.RAKNET_HANDSHAKE
                        ) {
                            phase = ConnectionPhase.RAKNET_HANDSHAKE
                        }
                    }

                    RakNet.ID_DISCONNECTION_NOTIFICATION -> phase = ConnectionPhase.DISCONNECTED
                }
            }

            is RakNetDatagram -> {
                stats.recordDatagramSequence(message.sequenceNumber, upstream)
                for (frame in message.frames) {
                    if (frame.payloadLength <= 0) continue
                    if ((buffer[frame.payloadOffset].toInt() and 0xFF) != RakNet.BEDROCK_BATCH_ID) continue
                    stats.recordGameBatch(upstream)
                    inspectBatch(buffer, frame.payloadOffset, frame.payloadLength, upstream)
                }
            }

            is Ack -> if (upstream) {
                // ACKs travelling upstream were sent by the client, so they describe
                // the server -> client direction: the client is acknowledging what it got.
                stats.recordAcknowledgement(ack = true, units = message.rangesCovered, upstream = false)
            } else {
                stats.recordAcknowledgement(ack = true, units = message.rangesCovered, upstream = true)
            }

            is Nack -> if (upstream) {
                // Client telling the server what it never received: loss on the way down.
                stats.recordAcknowledgement(ack = false, units = message.rangesCovered, upstream = false)
            } else {
                stats.recordAcknowledgement(ack = false, units = message.rangesCovered, upstream = true)
            }
        }
    }

    private fun inspectBatch(buffer: ByteArray, offset: Int, length: Int, upstream: Boolean) {
        // Once encryption is on, batch contents are opaque — we can still count
        // bytes and batches, but nothing inside is readable.
        if (encryptionStarted) {
            opaqueTrafficSeen = true
            return
        }

        val batch = BedrockBatchReader.read(buffer, offset, length)
        if (batch == null || batch.packets.isEmpty()) {
            // Most likely a compressed batch (compression is enabled by the server
            // right after login), which we cannot inspect without the session key.
            opaqueTrafficSeen = true
            return
        }

        for (entry in batch.packets) {
            recentCleartextPacketIds.addLast(entry.packetId)
            while (recentCleartextPacketIds.size > RECENT_ID_MEMORY) recentCleartextPacketIds.removeFirst()

            when (entry.packetId) {
                RakNet.BEDROCK_PKT_LOGIN -> if (upstream && phase != ConnectionPhase.ENCRYPTED) {
                    phase = ConnectionPhase.GAME_LOGIN
                }

                RakNet.BEDROCK_PKT_PLAY_STATUS -> if (phase == ConnectionPhase.IDLE || phase == ConnectionPhase.RAKNET_HANDSHAKE) {
                    phase = ConnectionPhase.GAME_LOGIN
                }

                RakNet.BEDROCK_PKT_SERVER_TO_CLIENT_HANDSHAKE -> {
                    // From here on the server encrypts everything it sends.
                    if (!upstream) encryptionStarted = true
                    phase = ConnectionPhase.ENCRYPTED
                }

                RakNet.BEDROCK_PKT_CLIENT_TO_SERVER_HANDSHAKE -> {
                    phase = ConnectionPhase.ENCRYPTED
                    opaqueTrafficSeen = true
                }

                RakNet.BEDROCK_PKT_START_GAME -> if (!upstream) phase = ConnectionPhase.PLAY

                RakNet.BEDROCK_PKT_DISCONNECT -> phase = ConnectionPhase.DISCONNECTED
            }
        }
    }

    /**
     * The unconnected pong payload is the classic semicolon-delimited Bedrock
     * advertisement: edition;MOTD;protocol;version;players;max;serverId;...
     * (Layout has been stable for years; we only read the first six fields and
     * fall back to showing the raw string.)
     */
    private fun parseServerAdvertisement(raw: String?) {
        if (raw.isNullOrEmpty()) return
        val parts = raw.split(';')
        if (parts.size < 6) {
            serverMotd = raw
            return
        }
        serverMotd = parts[1].ifBlank { null }
        serverProtocol = parts[2].toIntOrNull() ?: -1
        serverVersion = parts[3].ifBlank { null }
        serverPlayers = parts[4].toIntOrNull() ?: -1
        serverMaxPlayers = parts[5].toIntOrNull() ?: -1
    }

    fun shortLabel(): String {
        val host = "${remoteAddress.hostAddress}:$remotePort"
        val snapshot = stats.snapshot()
        val rtt = if (snapshot.rttLastMs >= 0) "${snapshot.rttLastMs}ms" else "—"
        return "$host · $rtt"
    }

    fun describeServer(): String {
        val motd = serverMotd ?: return "no server metadata yet"
        val version = serverVersion ?: "?"
        val players = if (serverPlayers >= 0) "$serverPlayers/${serverMaxPlayers}" else "?"
        return "$motd ($version, $players)"
    }

    private var clientPingSentAt: Long = -1

    companion object {
        private const val RECENT_ID_MEMORY = 12
    }
}
