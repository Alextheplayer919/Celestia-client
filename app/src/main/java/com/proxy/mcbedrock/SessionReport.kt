package com.proxy.mcbedrock

/**
 * Turns a session into something that can be handed over.
 *
 * A play test that produces "it felt fine" is worth nothing; this produces numbers.
 * The report is plain text (for a chat message or a clipboard) and JSON (for
 * anything that wants to parse it), built from the same stats the UI shows, so
 * nothing here can disagree with what the app displayed.
 *
 * Deliberately honest about its own limits, in the text itself: it records what the
 * relay measured, and says that FPS and in-game chunks are not measurable from
 * outside the game.
 */
object SessionReport {

    /**
     * The main text report.
     *
     * [minutes] is how long the session ran, used to contextualise the numbers.
     */
    fun text(stats: ServiceStats, minutes: Long = -1): String = buildString {
        appendLine("Celestia relay report")
        appendLine("=====================")
        appendLine()
        appendLine("When")
        appendLine("  session length:   ${if (minutes >= 0) "$minutes min" else "unknown"}")
        appendLine()
        appendLine("Where")
        appendLine("  target:           ${stats.targetLabel.ifBlank { "none set" }}")
        appendLine("  scope:            ${stats.scopeDescription.ifBlank { if (stats.scopedToTarget) "target only" else "all traffic" }}")
        appendLine("  app relayed:      ${stats.appDescription.ifBlank { "unknown" }}")
        if (stats.resolvedAddresses.isNotEmpty()) {
            appendLine("  addresses:        ${stats.resolvedAddresses.joinToString(", ")}")
        }
        appendLine()

        val flow = stats.flows.firstOrNull()
        appendLine("Connection")
        if (flow == null) {
            appendLine("  no flow was relayed in this session")
        } else {
            appendLine("  state:            ${flow.phase}")
            appendLine("  server:           ${flow.serverDescription.ifBlank { "no advertisement seen" }}")
            appendLine("  raknet mtu:       ${flow.mtu}")
            appendLine("  client protocol:  ${flow.raknetProtocol}")
            appendLine("  login:            ${flow.loginDescription}")
            appendLine("  encryption:       ${flow.encryptionDescription}")
        }
        appendLine()

        appendLine("Latency")
        if (flow == null || flow.rttLastMs < 0) {
            appendLine("  no RTT samples (RakNet pings were not seen)")
        } else {
            appendLine("  rtt last/avg:     ${flow.rttLastMs}ms / ${flow.rttAvgMs}ms")
            appendLine("  rtt min/max:      ${flow.rttMinMs}ms / ${flow.rttMaxMs}ms")
            appendLine("  jitter:           ${flow.jitterMs}ms (arrival jitter ${flow.arrivalJitterMs}ms)")
        }
        if (flow != null && flow.serverRttLastMs >= 0) {
            appendLine("  server leg rtt:   ${flow.serverRttLastMs}ms (min ${flow.serverRttMinMs}, max ${flow.serverRttMaxMs})")
        }
        appendLine()

        appendLine("Relay cost (what this app itself costs you)")
        if (flow != null && flow.relayOverheadAvgMs > 0.0) {
            appendLine("  per packet:       %.2fms average, %.2fms p95".format(flow.relayOverheadAvgMs, flow.relayOverheadP95Ms))
            if (flow.relayOverheadAvgMs > 3.0) {
                appendLine("  >>> above 3ms average: the relay is likely hurting more than helping; report this")
            }
        } else {
            appendLine("  no packets measured yet")
        }
        appendLine()

        appendLine("Reliability")
        if (flow != null) {
            appendLine("  loss up/down:     %.1f%% / %.1f%%".format(flow.upstreamLossPermille / 10.0, flow.downstreamLossPermille / 10.0))
            appendLine("  idle when sampled:${flow.idleSeconds}s")
        }
        appendLine("  relay errors:     ${stats.relayErrors}")
        appendLine("  oversized drops:  ${stats.oversizedReplies}")
        appendLine("  non-UDP dropped:  ${stats.droppedNonUdp}")
        appendLine()

        appendLine("Volume")
        appendLine("  throughput:       up ${stats.upstreamBytesPerSecond}B/s, down ${stats.downstreamBytesPerSecond}B/s")
        appendLine("  total:            up ${stats.totalUpstreamBytes}B, down ${stats.totalDownstreamBytes}B")
        appendLine("  datagrams:        ${flow?.packets ?: 0}")
        if (stats.lastError != null) {
            appendLine()
            appendLine("Last error: ${stats.lastError}")
        }
        appendLine()
        appendLine("Not measurable from outside the game, so absent above: frame rate, chunk")
        appendLine("load times, and anything inside the encrypted game session.")
    }

    /**
     * The same session as JSON, for anything that wants to read it back.
     * Written by hand rather than with a serializer: it is a flat, stable shape and
     * a hand-written writer cannot pull in a dependency or change format silently.
     */
    fun json(stats: ServiceStats, minutes: Long = -1): String {
        val flow = stats.flows.firstOrNull()
        val fields = LinkedHashMap<String, Any?>()
        fields["report"] = "celestia-relay"
        fields["sessionMinutes"] = if (minutes >= 0) minutes else null
        fields["target"] = stats.targetLabel
        fields["scope"] = stats.scopeDescription
        fields["scopedToTarget"] = stats.scopedToTarget
        fields["app"] = stats.appDescription
        fields["vpnEstablished"] = stats.vpnEstablished
        fields["resolvedAddresses"] = stats.resolvedAddresses
        fields["phase"] = flow?.phase
        fields["server"] = flow?.serverDescription
        fields["mtu"] = flow?.mtu
        fields["raknetProtocol"] = flow?.raknetProtocol
        fields["rttLastMs"] = flow?.rttLastMs
        fields["rttAvgMs"] = flow?.rttAvgMs
        fields["rttMinMs"] = flow?.rttMinMs
        fields["rttMaxMs"] = flow?.rttMaxMs
        fields["jitterMs"] = flow?.jitterMs
        fields["arrivalJitterMs"] = flow?.arrivalJitterMs
        fields["serverLegRttLastMs"] = flow?.serverRttLastMs
        fields["serverLegRttMinMs"] = flow?.serverRttMinMs
        fields["serverLegRttMaxMs"] = flow?.serverRttMaxMs
        fields["relayOverheadAvgMs"] = flow?.relayOverheadAvgMs
        fields["relayOverheadP95Ms"] = flow?.relayOverheadP95Ms
        fields["upstreamLossPermille"] = flow?.upstreamLossPermille
        fields["downstreamLossPermille"] = flow?.downstreamLossPermille
        fields["datagrams"] = flow?.packets
        fields["upstreamBytes"] = stats.totalUpstreamBytes
        fields["downstreamBytes"] = stats.totalDownstreamBytes
        fields["upstreamBytesPerSecond"] = stats.upstreamBytesPerSecond
        fields["downstreamBytesPerSecond"] = stats.downstreamBytesPerSecond
        fields["relayErrors"] = stats.relayErrors
        fields["oversizedReplies"] = stats.oversizedReplies
        fields["droppedNonUdp"] = stats.droppedNonUdp
        fields["lastError"] = stats.lastError

        return buildString {
            appendLine("{")
            val entries = fields.entries.toList()
            entries.forEachIndexed { index, (key, value) ->
                append("  ").append('"').append(key).append("\": ").append(value_(value))
                if (index != entries.lastIndex) append(',')
                appendLine()
            }
            appendLine("}")
        }
    }

    private fun value_(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean, is Int, is Long, is Double -> value.toString()
        is List<*> -> value.joinToString(prefix = "[", postfix = "]") { item -> if (item == null) "null" else quote(item.toString()) }
        else -> quote(value.toString())
    }

    private fun quote(text: String): String = buildString {
        append('"')
        for (character in text) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }

    /** One line for a toast or a clipboard, when the whole report is too much. */
    fun summary(stats: ServiceStats): String {
        val flow = stats.flows.firstOrNull() ?: return "no flow relayed"
        val parts = mutableListOf<String>()
        if (flow.rttLastMs >= 0) parts += "${flow.rttLastMs}ms rtt"
        if (flow.jitterMs >= 0) parts += "${flow.jitterMs}ms jitter"
        if (flow.relayOverheadAvgMs > 0.0) parts += "%.2fms relay".format(flow.relayOverheadAvgMs)
        if (flow.upstreamLossPermille > 0 || flow.downstreamLossPermille > 0) {
            parts += "%.1f%%/%.1f%% loss".format(
                flow.upstreamLossPermille / 10.0,
                flow.downstreamLossPermille / 10.0
            )
        }
        return parts.joinToString(", ").ifEmpty { flow.phase }
    }
}
