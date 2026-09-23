package com.proxy.mcbedrock.net

import java.net.InetAddress

/**
 * Minimal IPv4 + UDP header parsing and rebuilding.
 *
 * This is intentionally narrow — it only handles what's needed to relay
 * Minecraft Bedrock's RakNet (UDP) traffic. No TCP, no IPv6, no IP options
 * beyond skipping them via IHL. That's a deliberate scope choice: with the
 * VPN interface restricted to just the Minecraft app (see
 * addAllowedApplication in MinecraftVpnService), UDP-only is a safe
 * assumption for actual gameplay traffic.
 */

private const val IPV4_VERSION = 4
private const val PROTOCOL_UDP = 17
private const val IPV4_MIN_HEADER_LEN = 20
private const val UDP_HEADER_LEN = 8

data class ParsedUdpPacket(
    val sourceAddress: InetAddress,
    val destAddress: InetAddress,
    val sourcePort: Int,
    val destPort: Int,
    val payload: ByteArray,
    val payloadOffset: Int,
    val payloadLength: Int
)

/**
 * Parses a raw IPv4 packet (as read from the TUN fd) and returns its UDP
 * contents, or null if it's not an IPv4/UDP packet we care about (e.g. TCP,
 * ICMP, IPv6, malformed/truncated).
 */
fun parseIpv4Udp(buffer: ByteArray, length: Int): ParsedUdpPacket? {
    if (length < IPV4_MIN_HEADER_LEN) return null

    val versionAndIhl = buffer[0].toInt() and 0xFF
    val version = versionAndIhl shr 4
    if (version != IPV4_VERSION) return null // not IPv4 (IPv6 unsupported here)

    val ihl = versionAndIhl and 0x0F
    val ipHeaderLen = ihl * 4
    if (ipHeaderLen < IPV4_MIN_HEADER_LEN || length < ipHeaderLen + UDP_HEADER_LEN) return null

    val protocol = buffer[9].toInt() and 0xFF
    if (protocol != PROTOCOL_UDP) return null // only relaying UDP

    val srcAddr = InetAddress.getByAddress(buffer.copyOfRange(12, 16))
    val dstAddr = InetAddress.getByAddress(buffer.copyOfRange(16, 20))

    val udpOffset = ipHeaderLen
    val srcPort = readUInt16(buffer, udpOffset)
    val dstPort = readUInt16(buffer, udpOffset + 2)
    val udpLength = readUInt16(buffer, udpOffset + 4)

    val payloadOffset = udpOffset + UDP_HEADER_LEN
    val payloadLength = udpLength - UDP_HEADER_LEN
    if (payloadLength < 0 || payloadOffset + payloadLength > length) return null

    return ParsedUdpPacket(
        sourceAddress = srcAddr,
        destAddress = dstAddr,
        sourcePort = srcPort,
        destPort = dstPort,
        payload = buffer,
        payloadOffset = payloadOffset,
        payloadLength = payloadLength
    )
}

/**
 * Builds a raw IPv4 + UDP packet to write back into the TUN interface,
 * appearing to come from (fromAddress, fromPort) and addressed to
 * (toAddress, toPort) — i.e. the reply path: real server -> device.
 *
 * UDP checksum is set to 0 (valid/optional for IPv4 per RFC 768) to avoid
 * needing the pseudo-header checksum calculation; the IPv4 header checksum
 * IS computed properly since the kernel/network stack does validate it.
 */
fun buildIpv4Udp(
    fromAddress: InetAddress,
    fromPort: Int,
    toAddress: InetAddress,
    toPort: Int,
    payload: ByteArray,
    payloadLength: Int
): ByteArray {
    val totalLength = IPV4_MIN_HEADER_LEN + UDP_HEADER_LEN + payloadLength
    val packet = ByteArray(totalLength)

    // --- IPv4 header ---
    packet[0] = ((IPV4_VERSION shl 4) or 5).toByte() // version=4, IHL=5 (20 bytes, no options)
    packet[1] = 0 // DSCP/ECN
    writeUInt16(packet, 2, totalLength)
    writeUInt16(packet, 4, 0) // identification
    writeUInt16(packet, 6, 0) // flags/fragment offset
    packet[8] = 64 // TTL
    packet[9] = PROTOCOL_UDP.toByte()
    writeUInt16(packet, 10, 0) // header checksum placeholder
    System.arraycopy(fromAddress.address, 0, packet, 12, 4)
    System.arraycopy(toAddress.address, 0, packet, 16, 4)

    val headerChecksum = computeIpv4Checksum(packet, 0, IPV4_MIN_HEADER_LEN)
    writeUInt16(packet, 10, headerChecksum)

    // --- UDP header ---
    val udpOffset = IPV4_MIN_HEADER_LEN
    writeUInt16(packet, udpOffset, fromPort)
    writeUInt16(packet, udpOffset + 2, toPort)
    writeUInt16(packet, udpOffset + 4, UDP_HEADER_LEN + payloadLength)
    writeUInt16(packet, udpOffset + 6, 0) // checksum = 0 (unused, valid for IPv4)

    // --- payload ---
    System.arraycopy(payload, 0, packet, udpOffset + UDP_HEADER_LEN, payloadLength)

    return packet
}

private fun readUInt16(buffer: ByteArray, offset: Int): Int {
    return ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
}

private fun writeUInt16(buffer: ByteArray, offset: Int, value: Int) {
    buffer[offset] = ((value shr 8) and 0xFF).toByte()
    buffer[offset + 1] = (value and 0xFF).toByte()
}

/** Standard one's-complement checksum over the IPv4 header (RFC 791). */
private fun computeIpv4Checksum(buffer: ByteArray, offset: Int, length: Int): Int {
    var sum = 0L
    var i = offset
    while (i < offset + length) {
        val word = ((buffer[i].toInt() and 0xFF) shl 8) or (buffer[i + 1].toInt() and 0xFF)
        sum += word
        i += 2
    }
    while (sum shr 16 != 0L) {
        sum = (sum and 0xFFFF) + (sum shr 16)
    }
    return (sum.inv() and 0xFFFF).toInt()
}
