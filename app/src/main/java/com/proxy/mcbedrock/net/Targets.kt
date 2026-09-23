package com.proxy.mcbedrock.net

/**
 * A server the user asked the relay to care about.
 *
 * The relay itself is transparent — Minecraft is pointed at the address by the
 * player, not by this app — so a target is used for two things: scoping the
 * tunnel to that server's address (so unrelated traffic is untouched) and
 * labelling/looking up what the relay sees.
 *
 * Everything is plain data and plain string handling so it can be unit tested
 * without an Android device.
 */
data class ServerTarget(
    val host: String,
    val port: Int,
    val motd: String? = null,
    val mcVersion: String? = null,
    val protocolVersion: Int = -1,
    val players: Int = -1,
    val maxPlayers: Int = -1,
    val lastSeenAtMillis: Long = 0L
) {
    /** `play.example.com:19132`, or just `host` for the default port. */
    fun label(): String = if (port == DEFAULT_PORT) host else "$host:$port"

    /** `MOTD · 1.26.40 · 3/10`, using only the parts that are known. */
    fun describe(): String {
        val bits = ArrayList<String>(3)
        motd?.takeIf { it.isNotBlank() }?.let { bits += it }
        if (protocolVersion > 0) bits += ProtocolVersions.describe(protocolVersion)
        else mcVersion?.takeIf { it.isNotBlank() }?.let { bits += it }
        if (players >= 0 && maxPlayers >= 0) bits += "$players/$maxPlayers"
        return if (bits.isEmpty()) label() else bits.joinToString(" · ")
    }

    companion object {
        const val DEFAULT_PORT = 19132
    }
}

/**
 * Rules for the address fields in the UI: what a valid host/port looks like and
 * how the recently used list behaves.
 *
 * Kept separate from the storage layer (SharedPreferences) so the behaviour can
 * be tested directly, and mirroring the reference client's approach of keeping a
 * short, de-duplicated, newest-first list of servers next to the current one.
 */
object TargetRules {

    /** How many previously used servers the UI keeps around. */
    const val MAX_RECENTS = 5

    private const val MAX_HOST_LENGTH = 253

    /**
     * Accepts what a player would plausibly type. A trailing port in the host
     * field (`host:19132`) or a scheme is normalised away rather than rejected,
     * because that is what people paste.
     */
    fun normaliseHost(raw: String): String {
        var host = raw.trim()
        val scheme = host.indexOf("://")
        if (scheme >= 0) host = host.substring(scheme + 3)
        host = host.substringBefore('/')
        // "host:port" in the host box: split the port out and apply it to the port
        // field instead of failing validation.
        if (host.count { it == ':' } == 1) {
            val (name, maybePort) = host.split(':')
            if (maybePort.toIntOrNull() != null) host = name
        }
        return host.trim().trimEnd('.').lowercase()
    }

    /** The port embedded in a pasted `host:port`, or null. */
    fun hostEmbeddedPort(raw: String): Int? {
        var host = raw.trim()
        if (host.contains("://")) host = host.substringAfter("://")
        host = host.substringBefore('/')
        if (host.count { it == ':' } != 1) return null
        return host.substringAfter(':').trim().toIntOrNull()?.takeIf { it in 1..65535 }
    }

    /** Human-readable problem with [host], or null when it is usable. */
    fun validateHost(host: String): String? {
        val value = normaliseHost(host)
        if (value.isEmpty()) return "Enter a server address"
        if (value.length > MAX_HOST_LENGTH) return "Address is too long"
        if (value.any { it.isWhitespace() }) return "Address cannot contain spaces"
        val allowed = value.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' || it == ':' }
        if (!allowed) return "Address contains characters that are not valid in a hostname"
        if (value.startsWith('.') || value.startsWith('-')) return "Address cannot start with '${value.first()}'"
        val isIpv4 = value.split('.').let { parts ->
            parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
        }
        val isIpv6Like = value.contains(':')
        val hasDot = value.contains('.')
        if (!isIpv4 && !isIpv6Like && !hasDot) {
            // A bare single label is only plausible for a LAN name the system can
            // still resolve through search domains, so allow it but say nothing.
            return null
        }
        return null
    }

    /** Human-readable problem with [port], or null when it is usable. */
    fun validatePort(port: Int): String? = when {
        port !in 1..65535 -> "Port must be between 1 and 65535"
        else -> null
    }

    /** Parses the port field, defaulting to the Bedrock port when blank. */
    fun parsePort(raw: String): Int? {
        val value = raw.trim()
        if (value.isEmpty()) return ServerTarget.DEFAULT_PORT
        return value.toIntOrNull()?.takeIf { it in 1..65535 }
    }

    /**
     * Newest-first, de-duplicated by (host, port), capped at [MAX_RECENTS].
     * Returns a new list; callers persist the result.
     */
    fun remember(existing: List<ServerTarget>, target: ServerTarget): List<ServerTarget> {
        val other = existing.filterNot { it.host == target.host && it.port == target.port }
        return (listOf(target) + other).take(MAX_RECENTS)
    }

    /** True when two targets point at the same server. */
    fun sameServer(a: ServerTarget, b: ServerTarget): Boolean = a.host == b.host && a.port == b.port
}

/**
 * The semicolon-delimited advertisement Minecraft servers answer an unconnected
 * ping with:
 *
 *   edition;MOTD;protocol;version;players;max;serverId;subMotd;gamemode;...
 *
 * The layout has been stable for years; only the first six fields are read, and
 * anything shorter than that is kept as a raw MOTD rather than thrown away.
 */
object ServerAdvertisement {

    data class Advert(
        val motd: String?,
        val protocolVersion: Int,
        val mcVersion: String?,
        val players: Int,
        val maxPlayers: Int,
        val subMotd: String?
    )

    fun parse(raw: String?): Advert? {
        if (raw.isNullOrEmpty()) return null
        val parts = raw.split(';')
        if (parts.size < 6) {
            return Advert(raw, -1, null, -1, -1, null)
        }
        return Advert(
            motd = parts[1].ifBlank { null },
            protocolVersion = parts[2].toIntOrNull() ?: -1,
            mcVersion = parts[3].ifBlank { null },
            players = parts[4].toIntOrNull() ?: -1,
            maxPlayers = parts[5].toIntOrNull() ?: -1,
            subMotd = parts.getOrNull(7)?.ifBlank { null }
        )
    }
}
