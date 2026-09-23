package com.proxy.mcbedrock.net

/**
 * Read-only parsing of the RakNet layer that Minecraft Bedrock runs on top of UDP.
 *
 * Everything here is pure inspection: nothing in this file mutates or rebuilds a
 * packet. That keeps it usable for the parts of the app that only observe
 * (stats, connection-phase display, server metadata) without ever touching bytes
 * that go to the server.
 *
 * Wire formats verified against Sandertv/go-raknet (the reference RakNet
 * implementation used by gophertunnel), specifically:
 *   - internal/message/id.go          packet IDs + the unconnected magic sequence
 *   - packet.go                       frame header/reliability encoding
 *   - binary.go                       uint24 is LITTLE-endian, uint16/uint32 big-endian
 *   - acknowledge.go                  ACK/NACK record encoding
 *
 * Notable quirks that are easy to get wrong:
 *   - The datagram sequence number is a 24-bit *little-endian* integer.
 *   - ACK/NACK headers also carry the 0x80 datagram bit (0xc0 / 0xa0), so the
 *     ACK and NACK checks must come *before* the generic datagram check.
 *   - A normal data datagram's flag byte is 0x84 (0x80 | 0x04).
 */
object RakNet {

    /** The 16-byte "unconnected message" magic every offline RakNet message carries. */
    val MAGIC = byteArrayOf(
        0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00,
        0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(),
        0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(),
        0x12, 0x34, 0x56, 0x78
    )

    // --- offline (unconnected) messages ---
    const val ID_UNCONNECTED_PING = 0x01
    const val ID_UNCONNECTED_PING_OPEN_CONNECTIONS = 0x02
    const val ID_UNCONNECTED_PONG = 0x1C
    const val ID_OPEN_CONNECTION_REQUEST_1 = 0x05
    const val ID_OPEN_CONNECTION_REPLY_1 = 0x06
    const val ID_OPEN_CONNECTION_REQUEST_2 = 0x07
    const val ID_OPEN_CONNECTION_REPLY_2 = 0x08

    // --- online (connected) messages ---
    const val ID_CONNECTED_PING = 0x00
    const val ID_CONNECTED_PONG = 0x03
    const val ID_DETECT_LOST_CONNECTIONS = 0x04
    const val ID_CONNECTION_REQUEST = 0x09
    const val ID_CONNECTION_REQUEST_ACCEPTED = 0x10
    const val ID_NEW_INCOMING_CONNECTION = 0x13
    const val ID_DISCONNECTION_NOTIFICATION = 0x15
    const val ID_INCOMPATIBLE_PROTOCOL_VERSION = 0x19

    // --- datagram / acknowledgement flag bits ---
    const val FLAG_DATAGRAM = 0x80
    const val FLAG_ACK = 0x40
    const val FLAG_NACK = 0x20
    const val FLAG_NEEDS_B_AND_AS = 0x04
    const val FRAME_FLAG_SPLIT = 0x10

    /** Bedrock wraps game packets in a batch whose first byte is 0xFE. */
    const val BEDROCK_BATCH_ID = 0xFE

    // --- well-known Bedrock game packet IDs (stable for many protocol revisions).
    // Used only to recognise connection phases from *unencrypted* batches; see
    // BedrockFlowInspector for why that matters.
    const val BEDROCK_PKT_LOGIN = 0x01
    const val BEDROCK_PKT_PLAY_STATUS = 0x02
    const val BEDROCK_PKT_SERVER_TO_CLIENT_HANDSHAKE = 0x03
    const val BEDROCK_PKT_CLIENT_TO_SERVER_HANDSHAKE = 0x04
    const val BEDROCK_PKT_DISCONNECT = 0x05
    const val BEDROCK_PKT_START_GAME = 0x0B
    const val BEDROCK_PKT_NETWORK_SETTINGS = 0x8F

    fun magicAt(buffer: ByteArray, offset: Int): Boolean {
        if (offset + MAGIC.size > buffer.size) return false
        for (i in MAGIC.indices) {
            if (buffer[offset + i] != MAGIC[i]) return false
        }
        return true
    }

    /**
     * Cheap check used to decide whether a UDP payload is worth feeding to the
     * RakNet parser at all. Deliberately permissive: we would rather parse a
     * packet and fail than misclassify a Bedrock flow as "not Bedrock".
     */
    fun looksLikeRakNetPayload(buffer: ByteArray, offset: Int, length: Int): Boolean {
        if (length < 1 || offset + length > buffer.size) return false
        val id = buffer[offset].toInt() and 0xFF
        if (id and FLAG_ACK != 0 || id and FLAG_NACK != 0) return true
        if (id and FLAG_DATAGRAM != 0) return true
        // Offline messages carry the magic at different offsets: the handshake
        // pair puts it right after the id, while the ping/pong pair has a
        // timestamp (and the pong also a GUID) in front of it.
        return when (id) {
            ID_UNCONNECTED_PING, ID_UNCONNECTED_PING_OPEN_CONNECTIONS -> magicAt(buffer, offset + 9)
            ID_UNCONNECTED_PONG -> magicAt(buffer, offset + 17)
            ID_OPEN_CONNECTION_REQUEST_1,
            ID_OPEN_CONNECTION_REPLY_1,
            ID_OPEN_CONNECTION_REQUEST_2,
            ID_OPEN_CONNECTION_REPLY_2 -> magicAt(buffer, offset + 1)
            else -> false
        }
    }

    /**
     * Parses one RakNet payload (the UDP payload, i.e. one datagram or offline
     * message). Returns null when the bytes are not something we understand.
     *
     * Returned frame payloads are expressed as offsets into [buffer] — the
     * caller's buffer is not copied or held.
     */
    fun parse(buffer: ByteArray, offset: Int, length: Int): RakNetMessage? {
        if (length < 1 || offset + length > buffer.size) return null
        val end = offset + length
        val id = buffer[offset].toInt() and 0xFF

        // Order matters: ACK/NACK keep the datagram bit set.
        // ACK/NACK also carry the 0x80 datagram bit, so they must be tested first.
        // Both are validated strictly: a stray byte that merely has the ACK bit
        // set must not be mistaken for an acknowledgement.
        if (id and FLAG_ACK != 0 && id != BEDROCK_BATCH_ID) {
            return parseAcknowledgement(buffer, offset, length, ack = true) ?: return null
        }
        if (id and FLAG_NACK != 0 && id != BEDROCK_BATCH_ID) {
            return parseAcknowledgement(buffer, offset, length, ack = false) ?: return null
        }

        if (id and FLAG_DATAGRAM != 0) return parseDatagram(buffer, offset, length)

        return when (id) {
            ID_UNCONNECTED_PING, ID_UNCONNECTED_PING_OPEN_CONNECTIONS -> {
                if (length < 33 || !magicAt(buffer, offset + 9)) null
                else UnconnectedPing(
                    pingTimeMillis = readInt64(buffer, offset + 1),
                    clientGuid = readInt64(buffer, offset + 25)
                )
            }

            ID_UNCONNECTED_PONG -> {
                if (length < 35 || !magicAt(buffer, offset + 17)) null
                else {
                    val dataLen = readUInt16(buffer, offset + 33)
                    val motd = if (dataLen > 0 && offset + 35 + dataLen <= end) {
                        String(buffer, offset + 35, dataLen, Charsets.UTF_8)
                    } else {
                        null
                    }
                    UnconnectedPong(
                        pingTimeMillis = readInt64(buffer, offset + 1),
                        serverGuid = readInt64(buffer, offset + 9),
                        motd = motd
                    )
                }
            }

            ID_OPEN_CONNECTION_REQUEST_1 -> {
                if (length < 18 || !magicAt(buffer, offset + 1)) null
                else OpenConnectionRequest1(
                    clientProtocol = buffer[offset + 17].toInt() and 0xFF,
                    // RakNet finds its MTU by padding this packet out to MTU size,
                    // so the payload length *is* the measurement.
                    mtu = length + 28
                )
            }

            ID_OPEN_CONNECTION_REPLY_1 -> {
                if (length < 18 || !magicAt(buffer, offset + 1)) null
                else {
                    val hasSecurity = length > 25 && buffer[offset + 25].toInt() != 0
                    val mtuOffset = if (hasSecurity) offset + 30 else offset + 26
                    OpenConnectionReply1(
                        serverGuid = readInt64(buffer, offset + 17),
                        mtu = if (mtuOffset + 2 <= end) readUInt16(buffer, mtuOffset) else 0
                    )
                }
            }

            ID_CONNECTED_PING -> {
                if (length < 9) null
                else ConnectedPing(pingTimeMillis = readInt64(buffer, offset + 1))
            }

            ID_CONNECTED_PONG -> {
                if (length < 17) null
                else ConnectedPong(
                    pingTimeMillis = readInt64(buffer, offset + 1),
                    pongTimeMillis = readInt64(buffer, offset + 9)
                )
            }

            ID_CONNECTION_REQUEST,
            ID_CONNECTION_REQUEST_ACCEPTED,
            ID_NEW_INCOMING_CONNECTION,
            ID_DISCONNECTION_NOTIFICATION,
            ID_DETECT_LOST_CONNECTIONS,
            ID_INCOMPATIBLE_PROTOCOL_VERSION -> ConnectionControl(id)

            else -> null
        }
    }

    private fun parseDatagram(buffer: ByteArray, offset: Int, length: Int): RakNetMessage? {
        if (length < 4) return null
        val flags = buffer[offset].toInt() and 0xFF
        val sequence = readUInt24LE(buffer, offset + 1)

        val frames = ArrayList<RakNetFrame>(4)
        var cursor = offset + 4
        val end = offset + length
        while (cursor + 3 <= end) {
            val header = buffer[cursor].toInt() and 0xFF
            val bitLength = readUInt16(buffer, cursor + 1)
            val byteLength = bitLength ushr 3
            if (byteLength <= 0) break

            val reliability = (header ushr 5) and 0x07
            val isSplit = (header and FRAME_FLAG_SPLIT) != 0

            var p = cursor + 3
            if (isReliable(reliability)) p += 3
            if (isSequenced(reliability)) p += 3
            var orderIndex = -1
            if (isSequencedOrOrdered(reliability)) {
                orderIndex = readUInt24LE(buffer, p)
                p += 4 // 3-byte order index + 1 order channel
            }
            var splitId = -1
            var splitIndex = -1
            var splitCount = -1
            if (isSplit) {
                splitCount = readInt32(buffer, p)
                splitId = readUInt16(buffer, p + 4)
                splitIndex = readInt32(buffer, p + 6)
                p += 10
            }

            if (p + byteLength > end) break
            frames += RakNetFrame(
                reliability = reliability,
                isSplit = isSplit,
                payloadOffset = p,
                payloadLength = byteLength,
                orderIndex = orderIndex,
                splitId = splitId,
                splitIndex = splitIndex,
                splitCount = splitCount
            )
            cursor = p + byteLength
        }

        return RakNetDatagram(flags = flags, sequenceNumber = sequence, frames = frames)
    }

    /**
     * Returns null when the bytes are not a well-formed acknowledgement. Records
     * must be exactly reconstructible from the payload, which is what stops a
     * random packet whose first byte happens to have the ACK bit set from being
     * reported as a real acknowledgement (and polluting the loss numbers).
     */
    private fun parseAcknowledgement(buffer: ByteArray, offset: Int, length: Int, ack: Boolean): RakNetMessage? {
        if (length < 3) return null

        val recordCount = readUInt16(buffer, offset + 1)
        if (recordCount == 0) return null

        val ranges = ArrayList<IntRange>(8)
        var rangesCovered = 0
        var p = offset + 3
        val end = offset + length
        var records = 0
        while (records < recordCount) {
            if (p + 1 > end) return null
            val single = (buffer[p].toInt() and 0xFF) == 1
            if (single) {
                if (p + 4 > end) return null
                val seq = readUInt24LE(buffer, p + 1)
                ranges += seq..seq
                rangesCovered++
                p += 4
            } else {
                if (p + 7 > end) return null
                val first = readUInt24LE(buffer, p + 1)
                val last = readUInt24LE(buffer, p + 4)
                if (last < first) return null
                ranges += first..last
                rangesCovered += (last - first + 1)
                p += 7
            }
            records++
        }
        return if (ack) Ack(rangesCovered, ranges) else Nack(rangesCovered, ranges)
    }

    fun isReliable(reliability: Int) = reliability == 2 || reliability == 3 || reliability == 4
    fun isSequenced(reliability: Int) = reliability == 1 || reliability == 4
    fun isSequencedOrOrdered(reliability: Int) = isSequenced(reliability) || reliability == 3

    // --- little-endian helpers (RakNet mixes endianness; see class comment) ---

    fun readUInt24LE(buffer: ByteArray, offset: Int): Int {
        if (offset + 3 > buffer.size) return 0
        return (buffer[offset].toInt() and 0xFF) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 16)
    }

    fun readUInt16(buffer: ByteArray, offset: Int): Int {
        if (offset + 2 > buffer.size) return 0
        return ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
    }

    fun readInt32(buffer: ByteArray, offset: Int): Int {
        if (offset + 4 > buffer.size) return 0
        return ((buffer[offset].toInt() and 0xFF) shl 24) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
            (buffer[offset + 3].toInt() and 0xFF)
    }

    fun readInt64(buffer: ByteArray, offset: Int): Long {
        if (offset + 8 > buffer.size) return 0L
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (buffer[offset + i].toLong() and 0xFF)
        }
        return v
    }

    /** Unsigned varint, as used by Bedrock batch/packet headers. */
    fun readVarUInt(buffer: ByteArray, offset: Int, end: Int): Long? {
        var value = 0L
        var shift = 0
        var p = offset
        while (p < end && shift < 35) {
            val b = buffer[p].toInt() and 0xFF
            value = value or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return value
            shift += 7
            p++
        }
        return null
    }
}

sealed interface RakNetMessage

/** A datagram carrying one or more frames (frames hold the actual packet bytes). */
data class RakNetDatagram(
    val flags: Int,
    val sequenceNumber: Int,
    val frames: List<RakNetFrame>
) : RakNetMessage

class RakNetFrame(
    val reliability: Int,
    val isSplit: Boolean,
    val payloadOffset: Int,
    val payloadLength: Int,
    val orderIndex: Int,
    val splitId: Int,
    val splitIndex: Int,
    val splitCount: Int
) {
    override fun toString(): String =
        "RakNetFrame(rel=$reliability, split=$isSplit, len=$payloadLength)"
}

data class Ack(val rangesCovered: Int, val ranges: List<IntRange>) : RakNetMessage
data class Nack(val rangesCovered: Int, val ranges: List<IntRange>) : RakNetMessage

data class UnconnectedPing(val pingTimeMillis: Long, val clientGuid: Long) : RakNetMessage
data class UnconnectedPong(val pingTimeMillis: Long, val serverGuid: Long, val motd: String?) : RakNetMessage
data class OpenConnectionRequest1(val clientProtocol: Int, val mtu: Int) : RakNetMessage
data class OpenConnectionReply1(val serverGuid: Long, val mtu: Int) : RakNetMessage
data class ConnectedPing(val pingTimeMillis: Long) : RakNetMessage
data class ConnectedPong(val pingTimeMillis: Long, val pongTimeMillis: Long) : RakNetMessage

/** ConnectionRequest / Accepted / NewIncomingConnection / Disconnect / etc. */
data class ConnectionControl(val id: Int) : RakNetMessage

/**
 * Parsed contents of an *unencrypted* Bedrock batch (0xFE ...). Only the packet
 * IDs and lengths are read — the payloads are never decoded here.
 */
data class BedrockBatch(val packets: List<BatchEntry>)

class BatchEntry(val packetId: Int, val payloadOffset: Int, val payloadLength: Int)

object BedrockBatchReader {

    /**
     * Reads a cleartext (uncompressed, unencrypted) Bedrock batch. Returns null
     * if the bytes don't look like a batch.
     *
     * Batches are: 0xFE, varint length, then repeating [varint length][packet id][payload].
     */
    fun read(buffer: ByteArray, offset: Int, length: Int): BedrockBatch? {
        if (length < 3) return null
        if ((buffer[offset].toInt() and 0xFF) != RakNet.BEDROCK_BATCH_ID) return null

        val end = offset + length
        val declared = RakNet.readVarUInt(buffer, offset + 1, end) ?: return null
        var p = offset + 1
        var consumed = 0L
        while (consumed < declared && p < end) {
            val b = buffer[p].toInt() and 0xFF
            p++
            consumed++
            if (b and 0x80 == 0) break
        }

        val entries = ArrayList<BatchEntry>(8)
        while (p < end) {
            val innerLength = RakNet.readVarUInt(buffer, p, end) ?: break
            var after = p
            var shift = 0
            while (after < end) {
                val b = buffer[after].toInt() and 0xFF
                after++
                if (b and 0x80 == 0) break
                shift += 7
                if (shift > 35) break
            }
            if (after >= end) break
            val packetId = buffer[after].toInt() and 0xFF
            val payloadLength = (innerLength - 1).toInt()
            if (payloadLength < 0 || after + 1 + payloadLength > end) break
            entries += BatchEntry(packetId, after + 1, payloadLength)
            p = after + 1 + payloadLength
        }
        return BedrockBatch(entries)
    }
}
