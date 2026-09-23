package com.proxy.mcbedrock.hud

/**
 * What the on-screen HUD can show.
 *
 * Every module here is a *report* on the relay's own measurements. Nothing reads
 * game state, because the relay cannot see any: Bedrock encrypts everything after
 * the login handshake with a key derived between the client and the server (see
 * docs/decode-research.md). The reference client's module list was studied for how
 * this is structured, not for what it does — see docs/eclient-notes.md.
 *
 * Deliberately absent, and why:
 *  - coordinates / minimap / waypoints / armour / effects / scoreboard: they live in
 *    encrypted game packets, so a relay has nothing to show;
 *  - FPS: Android exposes no API for another app's frame rate;
 *  - keystrokes / CPS: would require hooking input, which is not this app's job and
 *    is not possible from a VPN service.
 */
enum class HudModule(
    val id: String,
    val label: String,
    val description: String,
    val defaultEnabled: Boolean
) {
    PING(
        id = "ping",
        label = "Ping",
        description = "Round-trip time measured from RakNet ping/pong, last and average.",
        defaultEnabled = true
    ),
    JITTER(
        id = "jitter",
        label = "Jitter",
        description = "Variation in round-trip time, plus how evenly packets arrive.",
        defaultEnabled = true
    ),
    LOSS(
        id = "loss",
        label = "Packet loss",
        description = "Share of datagrams the client or server never acknowledged.",
        defaultEnabled = true
    ),
    THROUGHPUT(
        id = "throughput",
        label = "Throughput",
        description = "Bytes per second in each direction.",
        defaultEnabled = false
    ),
    HISTORY(
        id = "history",
        label = "Graph",
        description = "Recent ping samples as a small bar graph.",
        defaultEnabled = true
    ),
    PHASE(
        id = "phase",
        label = "Session state",
        description = "RakNet/Bedrock phase the connection is in, and idle time.",
        defaultEnabled = true
    ),
    TARGET(
        id = "target",
        label = "Target",
        description = "Which server and tunnel scope are in effect.",
        defaultEnabled = false
    ),
    SERVER(
        id = "server",
        label = "Server info",
        description = "MOTD, version and player count from the server's advertisement.",
        defaultEnabled = false
    ),
    LOGIN(
        id = "login",
        label = "Login",
        description = "Version and account name the client stated at login (cleartext).",
        defaultEnabled = false
    ),
    HANDSHAKE(
        id = "handshake",
        label = "Handshake",
        description = "How the session is encrypted, and where inspection stops.",
        defaultEnabled = false
    );

    companion object {
        fun byId(id: String): HudModule? = entries.firstOrNull { it.id == id }

        /** Modules in the order they are drawn. */
        val ordered: List<HudModule> = entries.toList()

        val defaultEnabledIds: Set<String> = entries.filter { it.defaultEnabled }.map { it.id }.toSet()
    }
}

/**
 * Formatting for the HUD lines.
 *
 * Pure functions taking plain numbers, so the exact strings the overlay will draw
 * can be checked without an Android device.
 */
object HudText {

    /** `52ms (avg 61)` — the `—` used elsewhere means "no sample yet". */
    fun ping(rttLastMs: Int, rttAvgMs: Int): String = when {
        rttLastMs < 0 -> "·"
        rttAvgMs >= 0 -> "${rttLastMs}ms (avg $rttAvgMs)"
        else -> "${rttLastMs}ms"
    }

    fun jitter(rttJitterMs: Int, arrivalJitterMs: Int): String = when {
        rttJitterMs < 0 && arrivalJitterMs < 0 -> "·"
        else -> "$rttJitterMs / ${arrivalJitterMs}ms"
    }

    fun loss(upPermille: Int, downPermille: Int): String =
        "↑${percent(upPermille)} ↓${percent(downPermille)}"

    fun throughput(upBytesPerSecond: Long, downBytesPerSecond: Long): String =
        "↑${rate(upBytesPerSecond)} ↓${rate(downBytesPerSecond)}"

    fun phase(phase: String, idleSeconds: Long): String = when {
        phase.isBlank() -> "·"
        idleSeconds > 0 -> "$phase · ${idleSeconds}s idle"
        else -> phase
    }

    private fun percent(permille: Int): String = if (permille < 0) "·" else "%.1f%%".format(permille / 10.0)

    private fun rate(bytesPerSecond: Long): String = when {
        bytesPerSecond >= 1024L * 1024L -> "%.1fM".format(bytesPerSecond / (1024.0 * 1024.0))
        bytesPerSecond >= 1024L -> "%.0fK".format(bytesPerSecond / 1024.0)
        else -> "${bytesPerSecond}B"
    }
}
