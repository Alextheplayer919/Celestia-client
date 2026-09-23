package com.proxy.mcbedrock.net

/**
 * A server the app offers to fill the target fields with.
 *
 * Where these come from and what they are worth:
 *  - FEATURED entries are the servers partnered with Mojang and listed in the game's
 *    own server tab (play.inpvp.net, mco.lbsg.net, mco.cubecraft.net,
 *    geo.hivebedrock.network, play.galaxite.net, play.enchanted.gg). They are the
 *    ones a phone player is most likely to have visited, so they are the ones worth
 *    having a button for.
 *  - COMMUNITY entries are public Bedrock servers with published addresses.
 *
 * This is a convenience list, not a discovery service: nothing is probed, ranked or
 * phoned home about, and an address that stops working is a stale constant rather
 * than a failure. Tests assert the entries stay sane (unique, host-shaped, valid
 * ports).
 *
 * **Important for the terminated-session mode**: featured servers are partner
 * servers tied to Xbox Live sign-in. A relay that terminates the session and logs in
 * as the app may well be refused there. The transparent relay is unaffected. Each
 * entry carries the note so the UI can say so before someone spends an evening
 * debugging it.
 */
data class ServerPreset(
    val name: String,
    val host: String,
    val port: Int = DEFAULT_PORT,
    val kind: Kind = Kind.COMMUNITY,
    val note: String = "",
    /** Rough region, shown in the list so a distant server is obviously distant. */
    val region: String = ""
) {

    enum class Kind { FEATURED, COMMUNITY }

    /** `play.inpvp.net:19132`, or just the host when it is the default port. */
    fun address(): String = if (port == DEFAULT_PORT) host else "$host:$port"

    /** A line for the list: name, address, and anything the user should know. */
    fun describe(): String = buildString {
        append(address())
        val extras = listOfNotNull(region.takeIf { it.isNotBlank() }, note.takeIf { it.isNotBlank() })
        if (extras.isNotEmpty()) append("  ·  ").append(extras.joinToString(" · "))
    }

    companion object {
        const val DEFAULT_PORT = 19132
    }
}

object ServerDirectory {

    /**
     * Featured (partner) servers. Addresses as published by the Minecraft Wiki's
     * featured-server list; SoulSteel, added in 2025, is deliberately omitted
     * because it has no published Bedrock address.
     */
    val featured: List<ServerPreset> = listOf(
        ServerPreset(
            name = "The Hive",
            host = "geo.hivebedrock.network",
            kind = ServerPreset.Kind.FEATURED,
            note = "partners handle sign-in themselves; a proxy login may be refused",
            region = "EU/NA/Asia routing"
        ),
        ServerPreset(
            name = "CubeCraft",
            host = "mco.cubecraft.net",
            kind = ServerPreset.Kind.FEATURED,
            note = "Xbox sign-in required",
            region = "EU/NA/Asia routing"
        ),
        ServerPreset(
            name = "Lifeboat",
            host = "mco.lbsg.net",
            kind = ServerPreset.Kind.FEATURED,
            note = "Xbox sign-in required",
            region = "NA/EU/Asia routing"
        ),
        ServerPreset(
            name = "Mineville",
            host = "play.inpvp.net",
            kind = ServerPreset.Kind.FEATURED,
            note = "Xbox sign-in required",
            region = "North America"
        ),
        ServerPreset(
            name = "Galaxite",
            host = "play.galaxite.net",
            kind = ServerPreset.Kind.FEATURED,
            note = "Xbox sign-in required",
            region = "EU/NA"
        ),
        ServerPreset(
            name = "Enchanted Dragons",
            host = "play.enchanted.gg",
            kind = ServerPreset.Kind.FEATURED,
            note = "Xbox sign-in required",
            region = "EU/NA"
        )
    )

    /**
     * Community servers with published addresses. Kept short and conservative: a
     * long list of unverified addresses helps nobody, and an entry that no longer
     * resolves is worse than no entry.
     */
    val community: List<ServerPreset> = listOf(
        ServerPreset(
            name = "NetherGames",
            host = "play.nethergames.org",
            kind = ServerPreset.Kind.COMMUNITY,
            note = "minigames, EU and NA proxies",
            region = "EU/Asia"
        ),
        ServerPreset(
            name = "2b2tpe",
            host = "2b2tpe.org",
            kind = ServerPreset.Kind.COMMUNITY,
            note = "anarchy; expect no rules and heavy traffic",
            region = "EU/NA proxies"
        ),
        ServerPreset(
            name = "Broken Lens",
            host = "play.brlns.net",
            port = 2000,
            kind = ServerPreset.Kind.COMMUNITY,
            note = "non-default port, which is exactly why the port field matters",
            region = "EU"
        ),
        ServerPreset(
            name = "Vulengate",
            host = "pms.vulengate.com",
            kind = ServerPreset.Kind.COMMUNITY,
            note = "vanilla survival",
            region = "EU/NA"
        )
    )

    val all: List<ServerPreset> = featured + community

    fun byHost(host: String): ServerPreset? =
        all.firstOrNull { it.host.equals(host.trim(), ignoreCase = true) }

    /** Everything as a flat list, featured first, for a picker. */
    fun ordered(): List<ServerPreset> = all

    /**
     * A server worth testing against: closest thing to a fair measurement. NetherGames
     * runs EU proxies, which matters for anyone in Europe — a test against a
     * US-hosted server measures the ocean, not the relay.
     */
    fun suggestedForPlayTest(): ServerPreset = community.first { it.name == "NetherGames" }
}
