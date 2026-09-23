package com.proxy.mcbedrock.net

/**
 * Bedrock protocol version -> Minecraft version labels.
 *
 * The table is generated from the codec classes shipped by
 * CloudburstMC/Protocol (`Bedrock_vNNN.CODEC.protocolVersion` /
 * `.minecraftVersion`), not from memory, and the target build for this project
 * (protocol 2168 = Minecraft 1.26.40 / "26.40" on Android) is one of its entries.
 * See docs/eclient-notes.md for why the relay wants this mapping at all.
 *
 * Regenerating: `Dump4.java` in docs/decode-research.md walks the codec jar and
 * prints exactly this table.
 */
object ProtocolVersions {

    private val labels: Map<Int, String> = mapOf(
        291 to "1.7.0",
        313 to "1.8.0",
        332 to "1.9.0",
        340 to "1.10.0",
        354 to "1.11.0",
        361 to "1.12.0",
        388 to "1.13.0",
        389 to "1.14.0",
        390 to "1.14.60",
        407 to "1.16.0",
        408 to "1.16.20",
        419 to "1.16.100",
        422 to "1.16.200",
        428 to "1.16.210",
        431 to "1.16.220",
        440 to "1.17.0",
        448 to "1.17.10",
        465 to "1.17.30",
        471 to "1.17.40",
        475 to "1.18.0",
        486 to "1.18.10",
        503 to "1.18.30",
        527 to "1.19.0",
        534 to "1.19.10",
        544 to "1.19.20",
        545 to "1.19.21",
        554 to "1.19.30",
        557 to "1.19.40",
        560 to "1.19.50",
        567 to "1.19.60",
        568 to "1.19.63",
        575 to "1.19.70",
        582 to "1.19.80",
        589 to "1.20.0",
        594 to "1.20.10",
        618 to "1.20.30",
        622 to "1.20.40",
        630 to "1.20.50",
        649 to "1.20.60",
        662 to "1.20.70",
        671 to "1.20.80",
        685 to "1.21.0",
        686 to "1.21.2",
        712 to "1.21.20",
        729 to "1.21.30",
        748 to "1.21.40",
        766 to "1.21.50",
        776 to "1.21.60",
        786 to "1.21.70",
        800 to "1.21.80",
        818 to "1.21.90",
        819 to "1.21.93",
        827 to "1.21.100",
        844 to "1.21.111",
        859 to "1.21.120",
        860 to "1.21.124",
        898 to "1.21.130",
        924 to "1.26.0",
        944 to "1.26.10",
        975 to "1.26.20",
        1001 to "1.26.30",
        2168 to "1.26.40",
        2169 to "1.26.45",
        2193 to "1.26.50",
    )

    /** Newest protocol version this table knows a name for. */
    val newestKnown: Int = labels.keys.max()

    /** Oldest protocol version this table knows a name for. */
    val oldestKnown: Int = labels.keys.min()

    /** Minecraft version label for a protocol version, or null if unknown. */
    fun label(protocolVersion: Int): String? = labels[protocolVersion]

    /**
     * Human-readable description for UI/reporting, e.g.
     * `1.26.40 (protocol 2168)` or `protocol 2172 (not in this build's table)`.
     */
    fun describe(protocolVersion: Int): String = when {
        protocolVersion <= 0 -> "unknown"
        else -> {
            val name = labels[protocolVersion]
            if (name != null) "$name (protocol $protocolVersion)"
            else "protocol $protocolVersion (unnamed in this build)"
        }
    }

    /**
     * Compares what the client announced at login with what the server advertised
     * in its ping. Returns null when there is nothing worth saying, so callers can
     * simply not print a line.
     */
    fun compare(clientProtocol: Int, serverProtocol: Int): String? = when {
        clientProtocol <= 0 && serverProtocol <= 0 -> null
        serverProtocol <= 0 -> "client ${describe(clientProtocol)} (server version not advertised)"
        clientProtocol <= 0 -> "server ${describe(serverProtocol)}"
        clientProtocol == serverProtocol -> "both ${describe(clientProtocol)}"
        else -> "client ${describe(clientProtocol)} vs server ${describe(serverProtocol)} — versions differ"
    }
}
