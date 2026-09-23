package com.proxy.mcbedrock.net

import java.util.Base64

/**
 * Minimal, dependency-free readers for the JWTs that Bedrock exchanges in the
 * clear during login.
 *
 * Everything here is **informational only**:
 *  - signatures are never verified (no keys are used at all),
 *  - nothing returned by these helpers influences the relayed traffic, and
 *  - no value from here is ever used for authentication or key derivation.
 *
 * It exists so the UI can tell the user what the session actually is — which
 * Minecraft version the client logged in with, which account name the client put
 * in its own login token, and what the server's `ServerToClientHandshake`
 * negotiated — without decrypting anything or touching the session.
 *
 * `java.util.Base64` needs API 26, which matches this app's minSdk.
 */
object JwtScan {

    /** A parsed `header.payload.signature` shape. */
    data class Token(
        val headerJson: String,
        val payloadJson: String,
        val hasSignature: Boolean
    )

    /**
     * Splits a JWS/JWT and base64url-decodes the header and payload.
     * Returns null for anything that is not a decodable 3-part token.
     */
    fun split(token: String?): Token? {
        if (token.isNullOrBlank()) return null
        val parts = token.trim().split('.')
        if (parts.size != 3) return null
        val header = decodePart(parts[0]) ?: return null
        val payload = decodePart(parts[1]) ?: return null
        return Token(header, payload, parts[2].isNotEmpty())
    }

    /** First string value found for [key], or null. */
    fun stringField(json: String?, key: String): String? = readValue(json, key)?.asString()

    /**
     * First string value found for any of the given keys. Bedrock is not
     * consistent about casing (`DisplayName` in tokens, `displayName` elsewhere),
     * so callers can pass both spellings.
     */
    fun firstStringField(json: String?, vararg keys: String): String? {
        for (key in keys) stringField(json, key)?.let { return it }
        return null
    }

    /**
     * [stringField], but also looks one or more levels down into string values
     * that themselves contain JSON. Bedrock nests the same object twice in
     * different versions — e.g. `{"extraData":{"DisplayName":"Alex"}}` versus a
     * double-encoded `{"extraData":"{\"DisplayName\":\"Alex\"}"}` — and both
     * shapes have to work.
     */
    fun deepStringField(json: String?, key: String, maxDepth: Int = 3): String? {
        if (json.isNullOrBlank() || maxDepth <= 0) return null
        stringField(json, key)?.let { return it }
        for (nested in jsonStringValues(json)) {
            val value = deepStringField(nested, key, maxDepth - 1)
            if (value != null) return value
        }
        return null
    }

    /** True when the JSON contains [key] at all (regardless of its type). */
    fun hasKey(json: String?, key: String): Boolean = readValue(json, key) != null

    /** Base64url-decodes one JWT part and returns it as text, or null. */
    private fun decodePart(part: String): String? {
        if (part.isEmpty()) return null
        return try {
            // Java's base64 decoders accept missing '=' padding.
            String(Base64.getUrlDecoder().decode(part), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private class ValueAt(private val json: String, private val index: Int) {
        fun asString(): String? {
            if (index >= json.length || json[index] != '"') return null
            return readString(json, index)?.first
        }
    }

    /**
     * Finds `"key" :` and returns the position right after the colon.
     * Keys are matched literally, so a key appearing as a *value* (e.g. the
     * string "salt" somewhere else) is not picked up: a value is never followed
     * by a colon.
     */
    private fun readValue(json: String?, key: String): ValueAt? {
        if (json.isNullOrEmpty() || key.isEmpty()) return null
        var i = 0
        while (i < json.length) {
            val quote = json.indexOf('"', i)
            if (quote < 0) return null
            val parsed = readString(json, quote) ?: return null
            val (name, afterName) = parsed
            var j = afterName
            while (j < json.length && json[j].isWhitespace()) j++
            if (j < json.length && json[j] == ':') {
                if (name == key) {
                    var k = j + 1
                    while (k < json.length && json[k].isWhitespace()) k++
                    return ValueAt(json, k)
                }
            }
            i = afterName
        }
        return null
    }

    /** Every string value in the document that looks like a nested JSON object/array. */
    private fun jsonStringValues(json: String): List<String> {
        val out = ArrayList<String>(4)
        var i = 0
        while (i < json.length && out.size < 32) {
            val quote = json.indexOf('"', i)
            if (quote < 0) break
            val parsed = readString(json, quote)
            if (parsed == null) break
            val (value, after) = parsed
            if (value.startsWith("{") || value.startsWith("[")) out.add(value)
            i = after
        }
        return out
    }

    /**
     * Reads a JSON string starting at [quoteIndex] (which must point at the
     * opening quote) and returns the decoded value plus the index after the
     * closing quote. Handles the usual escape sequences.
     */
    private fun readString(json: String, quoteIndex: Int): Pair<String, Int>? {
        if (quoteIndex >= json.length || json[quoteIndex] != '"') return null
        val sb = StringBuilder()
        var i = quoteIndex + 1
        while (i < json.length) {
            val c = json[i]
            when {
                c == '\\' -> {
                    if (i + 1 >= json.length) return null
                    when (val esc = json[i + 1]) {
                        '"', '\\', '/' -> { sb.append(esc); i += 2 }
                        'b' -> { sb.append('\b'); i += 2 }
                        'f' -> { sb.append('\u000C'); i += 2 }
                        'n' -> { sb.append('\n'); i += 2 }
                        'r' -> { sb.append('\r'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        'u' -> {
                            if (i + 6 > json.length) return null
                            val hex = json.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16) ?: return null
                            sb.append(code.toChar())
                            i += 6
                        }
                        else -> { sb.append(esc); i += 2 }
                    }
                }
                c == '"' -> return sb.toString() to (i + 1)
                else -> { sb.append(c); i++ }
            }
        }
        return null
    }
}
