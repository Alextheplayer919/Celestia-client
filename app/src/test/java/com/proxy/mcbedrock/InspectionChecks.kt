package com.proxy.mcbedrock

import com.proxy.mcbedrock.net.Ack
import com.proxy.mcbedrock.net.BedrockBatchReader
import com.proxy.mcbedrock.net.BedrockFlowInspector
import com.proxy.mcbedrock.net.ConnectionPhase
import com.proxy.mcbedrock.net.ConnectionStats
import com.proxy.mcbedrock.net.Nack
import com.proxy.mcbedrock.net.OpenConnectionReply1
import com.proxy.mcbedrock.net.OpenConnectionRequest1
import com.proxy.mcbedrock.net.RakNet
import com.proxy.mcbedrock.net.RakNetDatagram
import com.proxy.mcbedrock.net.UnconnectedPing
import com.proxy.mcbedrock.net.UnconnectedPong
import com.proxy.mcbedrock.net.buildIcmpDestinationUnreachable
import com.proxy.mcbedrock.net.buildIpv4Udp
import com.proxy.mcbedrock.net.parseIpv4Udp
import java.net.InetAddress

/**
 * Checks for the read-only inspection layer (IPv4/UDP parsing, RakNet parsing,
 * Bedrock batch reading, connection stats, flow/phases).
 *
 * These are written as plain Kotlin rather than JUnit assertions so the exact same
 * suite can run:
 *   - in CI, wrapped by [InspectionTest] (JUnit), and
 *   - locally, straight from the command line during development, with no test
 *     runner or Android SDK present.
 *
 * Every synthetic packet below is built from the documented wire layout rather
 * than from our own writer code, so the parser is being checked against an
 * independent construction of the format — not just round-tripping itself.
 */
object InspectionChecks {

    private var passed = 0
    private var failed = 0
    private val failures = StringBuilder()

    /** How many checks passed in the last [runAll] — the JUnit wrapper asserts this. */
    @Volatile
    var lastPassed: Int = 0
        private set

    fun runAll(verbose: Boolean = false): Int {
        passed = 0
        failed = 0
        failures.setLength(0)

        ipv4UdpChecks()
        icmpChecks()
        rakNetOfflineChecks()
        rakNetConnectedChecks()
        rakNetDatagramChecks()
        acknowledgementChecks()
        bedrockBatchChecks()
        connectionStatsChecks()
        flowPhaseChecks()

        lastPassed = passed
        if (verbose || failed > 0) {
            println(failures.toString())
        }
        println("InspectionChecks: $passed passed, $failed failed")
        return failed
    }

    private fun check(name: String, condition: Boolean, extra: String = "") {
        if (condition) {
            passed++
        } else {
            failed++
            failures.append("  FAIL  ").append(name)
            if (extra.isNotEmpty()) failures.append(" -> ").append(extra)
            failures.append('\n')
        }
    }

    // ---------------------------------------------------------------- IPv4/UDP

    private fun ipv4UdpChecks() {
        val server = InetAddress.getByName("203.0.113.7")
        val client = InetAddress.getByName("10.0.0.2")
        val payload = ByteArray(64) { (it + 1).toByte() }

        val built = buildIpv4Udp(server, 19132, client, 41234, payload, payload.size)
        check("ipv4 builder total length", built.size == 92, "got ${built.size}")
        check("ipv4 header checksum valid", ipv4HeaderChecksumValid(built))
        val parsed = parseIpv4Udp(built, built.size)
        check("ipv4 round trip parses", parsed != null)
        if (parsed != null) {
            check("ipv4 addresses preserved", parsed.sourceAddress == server && parsed.destAddress == client)
            check("ipv4 ports preserved", parsed.sourcePort == 19132 && parsed.destPort == 41234)
            check(
                "ipv4 payload preserved",
                parsed.payload.copyOfRange(parsed.payloadOffset, parsed.payloadOffset + 64).contentEquals(payload)
            )
        }

        val v6 = ByteArray(48).also { it[0] = 0x60 }
        check("ipv6 rejected", parseIpv4Udp(v6, v6.size) == null)
        val tcp = built.copyOf().also { it[9] = 6 }
        check("tcp rejected", parseIpv4Udp(tcp, tcp.size) == null)
        check("runt rejected", parseIpv4Udp(ByteArray(24), 24) == null)

        // Fragmentation guard: MF set (0x2000) must be rejected.
        val fragmented = built.copyOf()
        fragmented[6] = 0x20
        check("fragmented packet (MF) rejected", parseIpv4Udp(fragmented, fragmented.size) == null)
        val offsetFragment = built.copyOf()
        offsetFragment[7] = 0x10 // non-zero fragment offset
        check("fragmented packet (offset) rejected", parseIpv4Udp(offsetFragment, offsetFragment.size) == null)

        // IP options must still be handled.
        val withOptions = ByteArray(24 + 8 + 8)
        withOptions[0] = 0x46
        withOptions[9] = 17
        withOptions[24] = 0; withOptions[25] = 53
        withOptions[26] = 0x1F; withOptions[27] = 0x90.toByte()
        withOptions[28] = 0; withOptions[29] = 16
        val optionParsed = parseIpv4Udp(withOptions, withOptions.size)
        check("ihl>5 payload offset", optionParsed != null && optionParsed.payloadOffset == 32)
        check("ihl>5 ports", optionParsed != null && optionParsed.sourcePort == 53 && optionParsed.destPort == 8080)
    }

    private fun ipv4HeaderChecksumValid(packet: ByteArray): Boolean {
        var sum = 0L
        var i = 0
        while (i < 20) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum and 0xFFFF) == 0xFFFFL
    }

    private fun icmpChecks() {
        val server = InetAddress.getByName("203.0.113.7")
        val client = InetAddress.getByName("10.0.0.2")

        // A TCP SYN, i.e. exactly the traffic we deliberately do not relay.
        val syn = ByteArray(40)
        syn[0] = 0x45
        syn[9] = 6
        syn[12] = 203.toByte(); syn[13] = 0; syn[14] = 113.toByte(); syn[15] = 7
        syn[16] = 10
        syn[19] = 2
        syn[20] = 0x01; syn[21] = 0xBB.toByte() // source port 443

        val icmp = buildIcmpDestinationUnreachable(server, client, 13, syn, syn.size)
        check("icmp ipv4 header checksum valid", ipv4HeaderChecksumValid(icmp))
        check("icmp protocol is 1", icmp[9].toInt() == 1)
        check("icmp type 3 code 13", icmp[20].toInt() == 3 && icmp[21].toInt() == 13)
        check("icmp total length 20+8+28", icmp.size == 56, "got ${icmp.size}")
        check("icmp source is the destination being reported", InetAddress.getByAddress(icmp.copyOfRange(12, 16)) == server)
        check("icmp destination is the original sender", InetAddress.getByAddress(icmp.copyOfRange(16, 20)) == client)

        // RFC 792: quote the original IP header + first 8 bytes of its payload.
        val quoted = icmp.copyOfRange(28, icmp.size)
        check("icmp quotes 28 bytes of the original packet", quoted.size == 28)
        check("icmp quoted bytes match original", quoted.contentEquals(syn.copyOfRange(0, 28)))

        // ICMP checksum over the whole ICMP message must sum to 0xFFFF.
        var sum = 0L
        var i = 20
        while (i < icmp.size) {
            sum += ((icmp[i].toInt() and 0xFF) shl 8) or (icmp[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        check("icmp checksum valid", (sum and 0xFFFF) == 0xFFFFL)
    }

    // ------------------------------------------------------------------ RakNet

    private fun rakNetOfflineChecks() {
        val guid = 0x0123456789ABCDEFL

        val ping = rakNetUnconnectedPing(1234L, guid)
        check("raknet recognises unconnected ping", RakNet.looksLikeRakNetPayload(ping, 0, ping.size))
        val parsedPing = RakNet.parse(ping, 0, ping.size)
        check("unconnected ping parses", parsedPing is UnconnectedPing)
        if (parsedPing is UnconnectedPing) {
            check("unconnected ping time", parsedPing.pingTimeMillis == 1234L)
            check("unconnected ping guid", parsedPing.clientGuid == guid)
        }

        val motd = "MCPE;Alex's World;2168;1.26.40;3;10;1234567890;Celestia;Survival;1;19132;19133;"
        val pong = rakNetUnconnectedPong(1234L, guid, motd)
        val parsedPong = RakNet.parse(pong, 0, pong.size)
        check("unconnected pong parses", parsedPong is UnconnectedPong)
        if (parsedPong is UnconnectedPong) {
            check("unconnected pong motd", parsedPong.motd == motd, "got ${parsedPong.motd}")
            check("unconnected pong guid", parsedPong.serverGuid == guid)
        }

        // MTU is discovered by padding OpenConnectionRequest1 out to MTU size.
        val ocr1 = rakNetOpenConnectionRequest1(clientProtocol = 11, mtu = 1400)
        val parsedOcr1 = RakNet.parse(ocr1, 0, ocr1.size)
        check("open connection request 1 parses", parsedOcr1 is OpenConnectionRequest1)
        if (parsedOcr1 is OpenConnectionRequest1) {
            check("ocr1 client protocol", parsedOcr1.clientProtocol == 11)
            check("ocr1 mtu inferred from padding", parsedOcr1.mtu == 1400, "got ${parsedOcr1.mtu}")
        }

        val reply = rakNetOpenConnectionReply1(guid, mtu = 1400, cookie = null)
        val parsedReply = RakNet.parse(reply, 0, reply.size)
        check("open connection reply 1 parses", parsedReply is OpenConnectionReply1)
        if (parsedReply is OpenConnectionReply1) {
            check("ocr1 reply guid", parsedReply.serverGuid == guid)
            check("ocr1 reply mtu", parsedReply.mtu == 1400, "got ${parsedReply.mtu}")
        }

        val secureReply = rakNetOpenConnectionReply1(guid, mtu = 1200, cookie = 0xDEADBEEFL)
        val parsedSecure = RakNet.parse(secureReply, 0, secureReply.size)
        check("ocr1 reply with cookie parses", parsedSecure is OpenConnectionReply1)
        check("ocr1 reply with cookie mtu", (parsedSecure as? OpenConnectionReply1)?.mtu == 1200)
    }

    private fun rakNetConnectedChecks() {
        val ping = rakNetConnectedPing(5000L)
        val parsedPing = RakNet.parse(ping, 0, ping.size)
        check("connected ping parses", parsedPing is com.proxy.mcbedrock.net.ConnectedPing)

        val pong = rakNetConnectedPong(5000L, 5042L)
        val parsedPong = RakNet.parse(pong, 0, pong.size)
        check("connected pong parses", parsedPong is com.proxy.mcbedrock.net.ConnectedPong)
        if (parsedPong is com.proxy.mcbedrock.net.ConnectedPong) {
            check("connected pong echoes ping time", parsedPong.pingTimeMillis == 5000L)
            check("connected pong own time", parsedPong.pongTimeMillis == 5042L)
        }
    }

    private fun rakNetDatagramChecks() {
        // Sequence numbers are 24-bit LITTLE-endian — the classic RakNet gotcha.
        val gamePayload = bedrockBatch(listOf(0x01 to ByteArray(8)))
        val datagram = rakNetDatagram(
            sequence = 0x002A1C,
            frames = listOf(frameOf(gamePayload, reliability = 3, orderIndex = 7))
        )
        val parsed = RakNet.parse(datagram, 0, datagram.size)
        check("datagram parses", parsed is RakNetDatagram)
        if (parsed is RakNetDatagram) {
            check("datagram sequence is little-endian", parsed.sequenceNumber == 0x002A1C, "got ${parsed.sequenceNumber}")
            check("datagram has one frame", parsed.frames.size == 1)
            val frame = parsed.frames.first()
            check("frame payload length", frame.payloadLength == gamePayload.size, "got ${frame.payloadLength}")
            check(
                "frame payload contents",
                datagram.copyOfRange(frame.payloadOffset, frame.payloadOffset + frame.payloadLength)
                    .contentEquals(gamePayload)
            )
        }

        // Every reliability variant must be skipped over correctly.
        val variants = listOf(0, 1, 2, 3, 4)
        for (reliability in variants) {
            val body = byteArrayOf(0x11, 0x22, 0x33, 0x44)
            val one = rakNetDatagram(
                sequence = 5,
                frames = listOf(frameOf(body, reliability = reliability, orderIndex = 3))
            )
            val parsedOne = RakNet.parse(one, 0, one.size)
            val frameOne = (parsedOne as? RakNetDatagram)?.frames?.firstOrNull()
            check(
                "reliability=$reliability frame extracted",
                frameOne != null && frameOne.payloadLength == body.size &&
                    one.copyOfRange(frameOne.payloadOffset, frameOne.payloadOffset + 4).contentEquals(body),
                "frame=$frameOne"
            )
        }

        // Split frames carry 10 extra bytes of split metadata.
        val splitBody = ByteArray(16) { it.toByte() }
        val splitDatagram = rakNetDatagram(
            sequence = 9,
            frames = listOf(
                frameOf(splitBody, reliability = 3, split = true, splitId = 0x1234, splitIndex = 2, splitCount = 4)
            )
        )
        val parsedSplit = RakNet.parse(splitDatagram, 0, splitDatagram.size)
        val splitFrame = (parsedSplit as? RakNetDatagram)?.frames?.firstOrNull()
        check("split frame parses", splitFrame != null && splitFrame.isSplit)
        check("split frame metadata", splitFrame?.splitId == 0x1234 && splitFrame.splitIndex == 2 && splitFrame.splitCount == 4)
        check(
            "split frame payload intact",
            splitDatagram.copyOfRange(splitFrame!!.payloadOffset, splitFrame.payloadOffset + splitFrame.payloadLength)
                .contentEquals(splitBody)
        )

        // Two frames in one datagram.
        val twoFrames = rakNetDatagram(
            sequence = 33,
            frames = listOf(frameOf(byteArrayOf(1, 2, 3), reliability = 2), frameOf(byteArrayOf(4, 5), reliability = 0))
        )
        val parsedTwo = RakNet.parse(twoFrames, 0, twoFrames.size) as? RakNetDatagram
        check("datagram with two frames", parsedTwo?.frames?.size == 2)
        check(
            "both frame payloads intact",
            parsedTwo != null &&
                twoFrames.copyOfRange(parsedTwo.frames[0].payloadOffset, parsedTwo.frames[0].payloadOffset + 3)
                    .contentEquals(byteArrayOf(1, 2, 3)) &&
                twoFrames.copyOfRange(parsedTwo.frames[1].payloadOffset, parsedTwo.frames[1].payloadOffset + 2)
                    .contentEquals(byteArrayOf(4, 5))
        )
    }

    private fun acknowledgementChecks() {
        val ack = rakNetAcknowledgement(ack = true, singles = listOf(10, 11, 20), ranges = listOf(30 to 33))
        val parsedAck = RakNet.parse(ack, 0, ack.size)
        check("ack parses", parsedAck is Ack)
        if (parsedAck is Ack) {
            // 3 singles + one range covering 30..33 (4 numbers)
            check("ack covered count", parsedAck.rangesCovered == 7, "got ${parsedAck.rangesCovered}")
            check("ack ranges", parsedAck.ranges.size == 4)
            check("ack range decoded", parsedAck.ranges.last() == 30..33)
        }

        val nack = rakNetAcknowledgement(ack = false, singles = listOf(99), ranges = emptyList())
        val parsedNack = RakNet.parse(nack, 0, nack.size)
        check("nack parses", parsedNack is Nack)
        check("nack covered count", (parsedNack as? Nack)?.rangesCovered == 1)

        // ACK/NACK set the datagram bit too, so ordering in the parser matters.
        check("ack header byte is 0xc0", (ack[0].toInt() and 0xFF) == 0xC0)
        check("nack header byte is 0xa0", (nack[0].toInt() and 0xFF) == 0xA0)
    }

    private fun bedrockBatchChecks() {
        val batch = bedrockBatch(listOf(0x01 to ByteArray(20) { 7 }, 0x03 to ByteArray(5) { 9 }))
        val parsed = BedrockBatchReader.read(batch, 0, batch.size)
        check("batch parses", parsed != null)
        if (parsed != null) {
            check("batch packet count", parsed.packets.size == 2)
            check("batch packet ids", parsed.packets[0].packetId == 0x01 && parsed.packets[1].packetId == 0x03)
            check("batch payload lengths", parsed.packets[0].payloadLength == 20 && parsed.packets[1].payloadLength == 5)
        }
        check("non-batch rejected", BedrockBatchReader.read(byteArrayOf(0x42, 0x00, 0x00), 0, 3) == null)
        check("truncated batch rejected", BedrockBatchReader.read(byteArrayOf(0xFE.toByte(), 0x7F), 0, 2) == null)
    }

    // ------------------------------------------------------------- statistics

    private fun connectionStatsChecks() {
        var now = 0L
        val stats = ConnectionStats(clock = { now })

        stats.recordRtt(40)
        stats.recordRtt(60)
        stats.recordRtt(50)
        val snapshot = stats.snapshot()
        check("rtt last", snapshot.rttLastMs == 50)
        check("rtt min", snapshot.rttMinMs == 40)
        check("rtt max", snapshot.rttMaxMs == 60)
        check("rtt avg", snapshot.rttAvgMs == 50)
        check("jitter is smoothed, nonzero", snapshot.jitterMs > 0, "got ${snapshot.jitterMs}")

        // Implausible samples are ignored rather than poisoning the window.
        stats.recordRtt(9_999_999)
        check("implausible rtt ignored", stats.snapshot().rttMaxMs == 60)

        // Loss is peer-reported: NACK units / (NACK + ACK units).
        val lossStats = ConnectionStats()
        lossStats.recordAcknowledgement(ack = true, units = 95, upstream = true)
        lossStats.recordAcknowledgement(ack = false, units = 5, upstream = true)
        lossStats.recordAcknowledgement(ack = true, units = 100, upstream = false)
        val lossSnapshot = lossStats.snapshot()
        check("upstream loss permille", lossSnapshot.upstreamLossPermille == 50, "got ${lossSnapshot.upstreamLossPermille}")
        check("downstream loss permille is zero", lossSnapshot.downstreamLossPermille == 0)

        // Sequence tracking distinguishes wire gaps from reordering.
        val seqStats = ConnectionStats()
        listOf(10, 11, 12, 15).forEach { seqStats.recordDatagramSequence(it, upstream = true) }
        seqStats.recordDatagramSequence(14, upstream = true) // late arrival
        seqStats.recordDatagramSequence(15, upstream = true) // duplicate
        val seqSnapshot = seqStats.snapshot()
        check("gap event recorded", seqSnapshot.upstreamGapEvents == 1, "got ${seqSnapshot.upstreamGapEvents}")
        check("late/duplicate counted", seqSnapshot.upstreamLateOrDuplicate == 2, "got ${seqSnapshot.upstreamLateOrDuplicate}")
        check("wire gap permille", seqSnapshot.upstreamWireGapPermille > 0)

        // Throughput counters and arrival-jitter path.
        val bytesStats = ConnectionStats(clock = { now })
        bytesStats.recordUpstreamPacket(100)
        bytesStats.recordUpstreamPacket(50)
        now = 10
        bytesStats.recordDownstreamPacket(200)
        now = 20
        bytesStats.recordDownstreamPacket(200)
        val bytesSnapshot = bytesStats.snapshot()
        check("upstream bytes", bytesSnapshot.upstreamBytes == 150L)
        check("downstream packets", bytesSnapshot.downstreamPackets == 2L)
        check("arrival jitter computed", bytesSnapshot.arrivalJitterMs >= 0)
    }

    // --------------------------------------------------------- flow phases

    private fun flowPhaseChecks() {
        var now = 1_000L
        val inspector = BedrockFlowInspector(
            remoteAddress = InetAddress.getByName("203.0.113.7"),
            remotePort = 19132,
            clientPort = 41234,
            stats = ConnectionStats(clock = { now }),
            clock = { now }
        )

        check("initial phase idle", inspector.phase == ConnectionPhase.IDLE)

        inspector.onUpstream(rakNetUnconnectedPing(1L, 42L), 0, rakNetUnconnectedPing(1L, 42L).size)
        check("ping moves to SERVER_PING", inspector.phase == ConnectionPhase.SERVER_PING)

        val motd = "MCPE;Celestia Test;2168;1.26.40;3;10;999;sub;Survival;1;19132;19133;"
        val pong = rakNetUnconnectedPong(1L, 42L, motd)
        inspector.onDownstream(pong, 0, pong.size)
        check("server motd parsed", inspector.serverMotd == "Celestia Test", "got ${inspector.serverMotd}")
        check("server version parsed", inspector.serverVersion == "1.26.40")
        check("server protocol parsed", inspector.serverProtocol == 2168)
        check("server player counts parsed", inspector.serverPlayers == 3 && inspector.serverMaxPlayers == 10)
        check("describeServer readable", inspector.describeServer().contains("Celestia Test"))

        val ocr1 = rakNetOpenConnectionRequest1(11, 1400)
        inspector.onUpstream(ocr1, 0, ocr1.size)
        check("ocr1 moves to RAKNET_HANDSHAKE", inspector.phase == ConnectionPhase.RAKNET_HANDSHAKE)
        check("client raknet protocol recorded", inspector.raknetClientProtocol == 11)
        check("mtu recorded", inspector.negotiatedMtu == 1400)

        val loginBatch = bedrockBatch(listOf(RakNet.BEDROCK_PKT_LOGIN to ByteArray(30)))
        val loginDatagram = rakNetDatagram(1, listOf(frameOf(loginBatch, reliability = 3, orderIndex = 0)))
        inspector.onUpstream(loginDatagram, 0, loginDatagram.size)
        check("login moves to GAME_LOGIN", inspector.phase == ConnectionPhase.GAME_LOGIN)
        check("cleartext packet ids recorded", inspector.recentCleartextPacketIds.contains(RakNet.BEDROCK_PKT_LOGIN))
        check("game batches counted", inspector.stats.snapshot().gameBatchesUp == 1L)

        // RTT comes from the client's own ping echoed by the server's pong.
        val ping = rakNetConnectedPing(1000L)
        inspector.onUpstream(ping, 0, ping.size)
        now = 1_100L
        val pingPong = rakNetConnectedPong(1000L, 5L)
        inspector.onDownstream(pingPong, 0, pingPong.size)
        check("rtt sample from ping/pong echo", inspector.stats.snapshot().rttLastMs == 100, "got ${inspector.stats.snapshot().rttLastMs}")

        // A pong whose echoed time does not match any ping must not fabricate a sample.
        now = 2_000L
        val strayPong = rakNetConnectedPong(777L, 5L)
        inspector.onDownstream(strayPong, 0, strayPong.size)
        check("mismatched pong ignored", inspector.stats.snapshot().rttLastMs == 100)

        // The handshake switches the session to encrypted; after that, batches are opaque.
        val handshakeBatch = bedrockBatch(listOf(RakNet.BEDROCK_PKT_SERVER_TO_CLIENT_HANDSHAKE to ByteArray(12)))
        val handshakeDatagram = rakNetDatagram(2, listOf(frameOf(handshakeBatch, reliability = 3, orderIndex = 1)))
        inspector.onDownstream(handshakeDatagram, 0, handshakeDatagram.size)
        check("handshake sets encryption", inspector.encryptionStarted)
        check("phase becomes ENCRYPTED", inspector.phase == ConnectionPhase.ENCRYPTED)

        // Encrypted/compressed traffic is counted but not interpreted.
        val opaque = rakNetDatagram(3, listOf(frameOf(bedrockBatch(listOf(0x0B to ByteArray(4))), reliability = 3, orderIndex = 2)))
        val batchesBefore = inspector.stats.snapshot().gameBatchesDown
        inspector.onDownstream(opaque, 0, opaque.size)
        check("opaque batches still counted", inspector.stats.snapshot().gameBatchesDown == batchesBefore + 1)
        check("phase stays ENCRYPTED after StartGame-looking bytes", inspector.phase == ConnectionPhase.ENCRYPTED)

        // NACK direction mapping: an upstream NACK is the client reporting loss on
        // the way *down* from the server.
        val nack = rakNetAcknowledgement(ack = false, singles = listOf(5, 6), ranges = emptyList())
        inspector.onUpstream(nack, 0, nack.size)
        val stats = inspector.stats.snapshot()
        check("upstream nack counts as downstream loss", stats.downstreamLossPermille == 1000, "got ${stats.downstreamLossPermille}")

        // A fresh inspector that sees StartGame before encryption reports PLAY.
        val playInspector = BedrockFlowInspector(
            remoteAddress = InetAddress.getByName("203.0.113.9"),
            remotePort = 19132,
            clientPort = 1234
        )
        val startGame = rakNetDatagram(
            1,
            listOf(frameOf(bedrockBatch(listOf(RakNet.BEDROCK_PKT_START_GAME to ByteArray(4))), reliability = 3))
        )
        playInspector.onDownstream(startGame, 0, startGame.size)
        check("cleartext StartGame reports PLAY", playInspector.phase == ConnectionPhase.PLAY)

        // An unparseable payload is counted, not crashed on.
        val junk = byteArrayOf(0x77, 0x01, 0x02)
        val before = inspector.unrecognisedUpstream
        inspector.onUpstream(junk, 0, junk.size)
        check("junk payload counted as unrecognised", inspector.unrecognisedUpstream == before + 1)
        check("junk payload leaves stats sane", inspector.stats.snapshot().upstreamPackets > 0)
    }

    // ------------------------------------------------- synthetic packet builders
    // Deliberately hand-rolled from the documented layouts, independent of the
    // production parser, so these checks are not self-fulfilling.

    private fun byte(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun u16be(value: Int) = byte((value shr 8) and 0xFF, value and 0xFF)
    private fun u24le(value: Int) = byte(value and 0xFF, (value shr 8) and 0xFF, (value shr 16) and 0xFF)
    private fun u32be(value: Long) = byte(
        ((value shr 24) and 0xFF).toInt(), ((value shr 16) and 0xFF).toInt(),
        ((value shr 8) and 0xFF).toInt(), (value and 0xFF).toInt()
    )

    private fun i64be(value: Long) = ByteArray(8) { ((value shr (8 * (7 - it))) and 0xFF).toByte() }

    private fun magic() = byte(
        0x00, 0xFF, 0xFF, 0x00, 0xFE, 0xFE, 0xFE, 0xFE,
        0xFD, 0xFD, 0xFD, 0xFD, 0x12, 0x34, 0x56, 0x78
    )

    // Layout per go-raknet: id, pingTime, magic, clientGUID.
    private fun rakNetUnconnectedPing(pingTime: Long, guid: Long) =
        byte(0x01) + i64be(pingTime) + magic() + i64be(guid)

    private fun rakNetUnconnectedPong(pingTime: Long, guid: Long, motd: String) =
        byte(0x1C) + i64be(pingTime) + i64be(guid) + magic() +
            u16be(motd.toByteArray(Charsets.UTF_8).size) + motd.toByteArray(Charsets.UTF_8)

    private fun rakNetOpenConnectionRequest1(clientProtocol: Int, mtu: Int): ByteArray {
        val total = ByteArray(mtu - 28)
        total[0] = 0x05
        System.arraycopy(magic(), 0, total, 1, 16)
        total[17] = clientProtocol.toByte()
        return total
    }

    private fun rakNetOpenConnectionReply1(guid: Long, mtu: Int, cookie: Long?): ByteArray {
        val head = byte(0x06) + magic() + i64be(guid)
        return if (cookie == null) {
            head + byte(0x00) + u16be(mtu)
        } else {
            head + byte(0x01) + u32be(cookie) + u16be(mtu)
        }
    }

    private fun rakNetConnectedPing(pingTime: Long) = byte(0x00) + i64be(pingTime)

    private fun rakNetConnectedPong(pingTime: Long, pongTime: Long) = byte(0x03) + i64be(pingTime) + i64be(pongTime)

    private fun frameOf(
        payload: ByteArray,
        reliability: Int,
        split: Boolean = false,
        orderIndex: Int = 0,
        splitId: Int = 0,
        splitIndex: Int = 0,
        splitCount: Int = 0
    ): ByteArray {
        var header = (reliability shl 5) and 0xFF
        if (split) header = header or 0x10
        var out = byte(header) + u16be(payload.size shl 3)
        if (RakNet.isReliable(reliability)) out += u24le(0)
        if (RakNet.isSequenced(reliability)) out += u24le(0)
        if (RakNet.isSequencedOrOrdered(reliability)) out += u24le(orderIndex) + byte(0)
        if (split) out += u32be(splitCount.toLong()) + u16be(splitId) + u32be(splitIndex.toLong())
        return out + payload
    }

    private fun rakNetDatagram(sequence: Int, frames: List<ByteArray>): ByteArray {
        var out = byte(0x80 or 0x04) + u24le(sequence)
        frames.forEach { out += it }
        return out
    }

    private fun rakNetAcknowledgement(ack: Boolean, singles: List<Int>, ranges: List<Pair<Int, Int>>): ByteArray {
        val records = singles.size + ranges.size
        var out = byte(if (ack) 0xC0 else 0xA0) + u16be(records)
        singles.forEach { out += byte(0x01) + u24le(it) }
        ranges.forEach { (first, last) -> out += byte(0x00) + u24le(first) + u24le(last) }
        return out
    }

    private fun bedrockBatch(packets: List<Pair<Int, ByteArray>>): ByteArray {
        var body = ByteArray(0)
        packets.forEach { (id, payload) ->
            val inner = byte(id) + payload
            body += varUInt(inner.size) + inner
        }
        return byte(0xFE) + varUInt(body.size) + body
    }

    private fun varUInt(value: Int): ByteArray {
        var v = value
        val out = ArrayList<Byte>(5)
        while (true) {
            if (v and 0x7F.inv() == 0) {
                out.add(v.toByte())
                break
            }
            out.add(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        return out.toByteArray()
    }

    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val out = ByteArray(size + other.size)
        System.arraycopy(this, 0, out, 0, size)
        System.arraycopy(other, 0, out, size, other.size)
        return out
    }
}
