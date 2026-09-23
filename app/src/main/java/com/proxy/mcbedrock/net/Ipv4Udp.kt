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
private const val PROTOCOL_ICMP = 1
private const val ICMP_DEST_UNREACHABLE = 3
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
 * Header-level view of an IPv4 packet, used to decide what to do with traffic we
 * are not going to relay (anything that isn't UDP, and fragments).
 */
data class Ipv4Info(
    val protocol: Int,
    val sourceAddress: InetAddress,
    val destAddress: InetAddress,
    val headerLength: Int,
    val totalLength: Int,
    val isFragment: Boolean
)

/**
 * Reads just the IPv4 header. Returns null if this is not an IPv4 packet we can
 * make sense of. Unlike [parseIpv4Udp] this is deliberately tolerant: the caller
 * uses it to answer non-UDP traffic (with ICMP) instead of silently dropping it.
 */
fun parseIpv4(buffer: ByteArray, length: Int): Ipv4Info? {
    if (length < IPV4_MIN_HEADER_LEN) return null

    val versionAndIhl = buffer[0].toInt() and 0xFF
    if ((versionAndIhl shr 4) != IPV4_VERSION) return null

    val ipHeaderLen = (versionAndIhl and 0x0F) * 4
    if (ipHeaderLen < IPV4_MIN_HEADER_LEN || length < ipHeaderLen) return null

    val flagsAndFragmentOffset = readUInt16(buffer, 6)

    return Ipv4Info(
        protocol = buffer[9].toInt() and 0xFF,
        sourceAddress = InetAddress.getByAddress(buffer.copyOfRange(12, 16)),
        destAddress = InetAddress.getByAddress(buffer.copyOfRange(16, 20)),
        headerLength = ipHeaderLen,
        totalLength = readUInt16(buffer, 2),
        isFragment = (flagsAndFragmentOffset and 0x3FFF) != 0
    )
}

/**
 * Parses a raw IPv4 packet (as read from the TUN fd) and returns its UDP
 * contents, or null if it's not an IPv4/UDP packet we care about (e.g. TCP,
 * ICMP, IPv6, malformed/truncated, fragmented).
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

    // Reject fragments: a fragment is not a datagram, and only the first one even
    // carries a UDP header. RakNet keeps its datagrams under the MTU, so anything
    // fragmented here is traffic we don't understand and must not guess at.
    // Bits: MF (0x2000) plus the 13-bit fragment offset (0x1FFF).
    val flagsAndFragmentOffset = readUInt16(buffer, 6)
    if (flagsAndFragmentOffset and 0x3FFF != 0) return null

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

/**
 * Same as [buildIpv4Udp] but writes into [destination] and returns the packet
 * length instead of allocating.
 *
 * The reply path runs for every downstream datagram, and during chunk loading that
 * is thousands of allocations per second on a device that is also rendering. One
 * reused buffer per session keeps the relay off the garbage collector.
 *
 * Returns 0 when [destination] is too small, so a caller cannot silently send a
 * truncated packet.
 */
fun buildIpv4UdpInto(
    destination: ByteArray,
    fromAddress: InetAddress,
    fromPort: Int,
    toAddress: InetAddress,
    toPort: Int,
    payload: ByteArray,
    payloadLength: Int
): Int {
    val totalLength = IPV4_MIN_HEADER_LEN + UDP_HEADER_LEN + payloadLength
    if (totalLength > destination.size) return 0

    destination[0] = ((IPV4_VERSION shl 4) or 5).toByte()
    destination[1] = 0
    writeUInt16(destination, 2, totalLength)
    writeUInt16(destination, 4, 0)
    writeUInt16(destination, 6, 0)
    destination[8] = 64
    destination[9] = PROTOCOL_UDP.toByte()
    writeUInt16(destination, 10, 0)
    System.arraycopy(fromAddress.address, 0, destination, 12, 4)
    System.arraycopy(toAddress.address, 0, destination, 16, 4)
    writeUInt16(destination, 10, computeIpv4Checksum(destination, 0, IPV4_MIN_HEADER_LEN))

    val udpOffset = IPV4_MIN_HEADER_LEN
    writeUInt16(destination, udpOffset, fromPort)
    writeUInt16(destination, udpOffset + 2, toPort)
    writeUInt16(destination, udpOffset + 4, UDP_HEADER_LEN + payloadLength)
    writeUInt16(destination, udpOffset + 6, 0) // checksum = 0 (unused, valid for IPv4)
    System.arraycopy(payload, 0, destination, udpOffset + UDP_HEADER_LEN, payloadLength)

    return totalLength
}

/**
 * Builds an ICMPv4 "destination unreachable" message, quoting the start of the
 * packet that could not be delivered (RFC 792).
 *
 * Why this exists: the VPN captures *all* IPv4 traffic from Minecraft, but this
 * proxy only relays UDP. Without a reply, dropped TCP connections (store, sign-in,
 * telemetry) sit in SYN retransmit and time out, which looks like "the proxy
 * broke the game". An ICMP unreachable makes the connection fail immediately and
 * visibly, which is honest about what we do and don't relay.
 *
 * [code] 13 = "communication administratively prohibited" (RFC 1122 §4.2.3.1 —
 * the recommended code for a host that filters protocols it doesn't support);
 * 3 = port unreachable.
 */
fun buildIcmpDestinationUnreachable(
    fromAddress: InetAddress,
    toAddress: InetAddress,
    code: Int,
    originalPacket: ByteArray,
    originalLength: Int
): ByteArray {
    // RFC 792: the ICMP payload is the original IP header plus the first 8 bytes
    // of its payload.
    val quotedLength = minOf(originalLength, IPV4_MIN_HEADER_LEN + 8)
    val icmpLength = 8 + quotedLength
    val totalLength = IPV4_MIN_HEADER_LEN + icmpLength
    val packet = ByteArray(totalLength)

    // --- IPv4 header ---
    packet[0] = ((IPV4_VERSION shl 4) or 5).toByte()
    packet[1] = 0
    writeUInt16(packet, 2, totalLength)
    writeUInt16(packet, 4, 0)
    writeUInt16(packet, 6, 0)
    packet[8] = 64 // TTL
    packet[9] = PROTOCOL_ICMP.toByte()
    writeUInt16(packet, 10, 0)
    System.arraycopy(fromAddress.address, 0, packet, 12, 4)
    System.arraycopy(toAddress.address, 0, packet, 16, 4)
    writeUInt16(packet, 10, computeIpv4Checksum(packet, 0, IPV4_MIN_HEADER_LEN))

    // --- ICMP header ---
    val icmpOffset = IPV4_MIN_HEADER_LEN
    packet[icmpOffset] = ICMP_DEST_UNREACHABLE.toByte()
    packet[icmpOffset + 1] = code.toByte()
    writeUInt16(packet, icmpOffset + 2, 0) // checksum placeholder
    writeUInt16(packet, icmpOffset + 4, 0) // unused for type 3
    writeUInt16(packet, icmpOffset + 6, 0)

    System.arraycopy(originalPacket, 0, packet, icmpOffset + 8, quotedLength)
    writeUInt16(packet, icmpOffset + 2, computeIpv4Checksum(packet, icmpOffset, icmpLength))

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
