package com.proxy.mcbedrock

import com.proxy.mcbedrock.relay.AccountIdentity
import com.proxy.mcbedrock.relay.JwtClaimReader
import com.proxy.mcbedrock.relay.LoginRewrite
import com.proxy.mcbedrock.relay.MitmHandshake
import com.proxy.mcbedrock.relay.RelaySideKeyPair
import io.netty.buffer.Unpooled
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket
import org.cloudburstmc.protocol.bedrock.packet.ServerToClientHandshakePacket
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils
import org.jose4j.jws.AlgorithmIdentifiers
import org.jose4j.jws.JsonWebSignature
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.util.Base64

/**
 * Checks for the terminated-session core, run in CI against the real codec and
 * crypto libraries rather than mocks.
 *
 * What is actually being proved here:
 *  - the key exchange works in both directions, i.e. the relay can reproduce the
 *    secret the *game* computes on the client leg and the secret the *server*
 *    computes on the server leg (that is the whole basis of being able to read a
 *    session at all);
 *  - the re-signed login verifies against the account key and survives a real
 *    encode/decode round trip through `Bedrock_v2168.CODEC`.
 *
 * Deliberately not proved here: anything requiring a network, a device, or a real
 * Microsoft account. Those are the next increments (docs/mitm-plan.md).
 */
object MitmChecks {

    private var passed = 0
    private var failed = 0
    private val failures = StringBuilder()

    @Volatile
    var lastPassed: Int = 0
        private set

    fun runAll(verbose: Boolean = false): Int {
        passed = 0
        failed = 0
        failures.setLength(0)

        clientLegKeyExchangeChecks()
        serverLegKeyExchangeChecks()
        loginRewriteChecks()
        codecRoundTripChecks()
        payloadChecks()

        lastPassed = passed
        if (verbose || failed > 0) println(failures.toString())
        println("MitmChecks: $passed passed, $failed failed")
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

    // ------------------------------------------------------- the two key exchanges

    /**
     * Client leg: the relay pretends to be the server. The game computes
     * `ECDH(gamePriv, relayPub, salt)` and the relay must compute the same value as
     * `ECDH(relayPriv, gamePub, salt)`.
     */
    private fun clientLegKeyExchangeChecks() {
        val gameKeys = MitmHandshake.generatePair()
        val relay = RelaySideKeyPair()
        val salt = MitmHandshake.newSalt()

        check("salt is 16 bytes", salt.size == 16, "got ${salt.size}")

        val handshake = MitmHandshake.clientSideHandshake(relay, salt)
        val jwt = handshake.jwt
        check("handshake carries a JWT", jwt.isNotBlank())
        check("handshake is a three part token", jwt.count { it == '.' } == 2)

        val relayKeyFromJwt = JwtClaimReader.handshakeServerKey(jwt)
        check("handshake exposes the relay's key", relayKeyFromJwt != null)
        check(
            "handshake key is the relay's own key",
            relayKeyFromJwt?.encoded?.contentEquals(relay.publicKey.encoded) == true
        )
        val saltFromJwt = JwtClaimReader.handshakeSalt(jwt)
        check("handshake exposes the salt", saltFromJwt?.contentEquals(salt) == true)

        val gameSide = MitmHandshake.deriveSecret(gameKeys.private, relay.publicKey, salt)
        val relaySide = MitmHandshake.clientLegSecret(relay, gameKeys.public, salt)
        check(
            "both sides derive the same client-leg secret",
            gameSide.encoded.contentEquals(relaySide.encoded)
        )

        val plaintext = "{\"packet\":\"MovePlayer\"}".toByteArray(StandardCharsets.UTF_8)
        val encrypted = MitmHandshake.encryptCipher(relaySide).doFinal(plaintext)
        check("ciphertext differs from plaintext", !encrypted.contentEquals(plaintext))
        val decrypted = MitmHandshake.decryptCipher(gameSide).doFinal(encrypted)
        check(
            "the game can read what the relay encrypted",
            String(decrypted, StandardCharsets.UTF_8) == String(plaintext, StandardCharsets.UTF_8)
        )

        // A different salt must produce a different secret: that is what stops a
        // relay from reusing a key across sessions.
        val otherSalt = MitmHandshake.newSalt()
        val otherSecret = MitmHandshake.clientLegSecret(relay, gameKeys.public, otherSalt)
        check("a different salt gives a different secret", !otherSecret.encoded.contentEquals(relaySide.encoded))
    }

    /**
     * Server leg: the real server sends its handshake, and the relay derives the
     * secret using the account key it logged in with.
     */
    private fun serverLegKeyExchangeChecks() {
        val identity = testIdentity()
        val serverKeys = MitmHandshake.generatePair()
        val salt = MitmHandshake.newSalt()

        // The real server's handshake, built exactly as the library builds it.
        val serverHandshake = ServerToClientHandshakePacket().apply {
            jwt = EncryptionUtils.createHandshakeJwt(serverKeys, salt)
        }

        val serverKey = JwtClaimReader.handshakeServerKey(serverHandshake.jwt)
        check("server handshake exposes the server key", serverKey != null)
        val serverSalt = JwtClaimReader.handshakeSalt(serverHandshake.jwt)
        check("server handshake exposes the salt", serverSalt?.contentEquals(salt) == true)

        val relaySide = MitmHandshake.serverLegSecret(identity, serverHandshake.jwt)
        check("relay derives a server-leg secret", relaySide != null)
        val realServerSide = MitmHandshake.deriveSecret(serverKeys.private, identity.keyPair.public, salt)
        check(
            "relay and the real server derive the same secret",
            relaySide != null && relaySide.encoded.contentEquals(realServerSide.encoded)
        )

        val payload = "{\"packet\":\"Text\"}".toByteArray(StandardCharsets.UTF_8)
        if (relaySide != null) {
            val fromServer = MitmHandshake.encryptCipher(realServerSide).doFinal(payload)
            val readByRelay = MitmHandshake.decryptCipher(relaySide).doFinal(fromServer)
            check("the relay can read what the server encrypted", readByRelay.contentEquals(payload))
        }

        check("a handshake without a salt yields no secret", MitmHandshake.serverLegSecret(identity, "a.b.c") == null)
        check("a handshake with a bad key yields no secret", MitmHandshake.serverLegSecret(identity, "not.a.token") == null)
    }

    // ------------------------------------------------------------- login rewriting

    private fun loginRewriteChecks() {
        val gameKeys = MitmHandshake.generatePair()
        val identity = testIdentity()

        val gameJwt = signWith(gameKeys, """{"extraData":{"XUID":"2535400000000000","displayName":"Alex"}}""")
        val original = loginPacket(2168, listOf(fakeChainJwt(gameKeys)), gameJwt)

        check("game key is readable from the login", JwtClaimReader.clientKeyFromLogin(original) != null)
        check(
            "the key read back is the game's key",
            JwtClaimReader.clientKeyFromLogin(original)?.encoded?.contentEquals(gameKeys.public.encoded) == true
        )
        check("display name is readable from the login", JwtClaimReader.displayName(gameJwt) == "Alex")
        check("xuid is readable from the login", JwtClaimReader.xuid(gameJwt) == "2535400000000000")

        val rewritten = LoginRewrite.serverSideLogin(original, identity)
        check("protocol version is preserved", rewritten.protocolVersion == 2168)
        check("chain is replaced with the account chain", chainOf(rewritten) == identity.chain)
        check("client JWT is replaced", rewritten.clientJwt != gameJwt)

        // The whole point: the new JWT must verify against the *account* key, and
        // must not verify against the game's key any more.
        check("re-signed JWT verifies with the account key", verifies(rewritten.clientJwt!!, identity.keyPair.public))
        check("re-signed JWT does not verify with the game key", !verifies(rewritten.clientJwt!!, gameKeys.public))
        check("re-signed JWT carries the account x5u", x5uOf(rewritten.clientJwt!!) == identity.publicKeyBase64)
        check(
            "re-signed JWT keeps the game's claims",
            JwtClaimReader.displayName(rewritten.clientJwt) == "Alex" &&
                JwtClaimReader.xuid(rewritten.clientJwt) == "2535400000000000"
        )
        check("algorithm is ES384", algorithmOf(rewritten.clientJwt!!) == "ES384")

        // An unreadable original must still produce something signable.
        val noJwt = loginPacket(2168, listOf(fakeChainJwt(gameKeys)), null)
        val fallback = LoginRewrite.serverSideLogin(noJwt, identity)
        check("login without a client JWT is still rewritten", fallback.clientJwt != null)
        check("fallback JWT verifies with the account key", verifies(fallback.clientJwt!!, identity.keyPair.public))
        check("fallback JWT is a valid payload", LoginRewrite.looksLikePayload(LoginRewrite.payloadOf(fallback.clientJwt)))

        val junk = loginPacket(2168, listOf(fakeChainJwt(gameKeys)), "not-a-token")
        val fromJunk = LoginRewrite.serverSideLogin(junk, identity)
        check("junk client JWT falls back instead of throwing", verifies(fromJunk.clientJwt!!, identity.keyPair.public))

        check("empty chain is not valid", !AccountIdentity(emptyList(), gameKeys).isValid())
        check("account identity is valid", identity.isValid())
        check("identity describes itself", identity.describe().contains("TestPlayer"))
        check("auth payload is FULL", identity.authPayload().authType == AuthType.FULL)
        check("chain identity key is readable", identity.chainIdentityKey == identity.publicKeyBase64)
    }

    // -------------------------------------------------- wire-level round trip test

    private fun codecRoundTripChecks() {
        val codec = Bedrock_v2168.CODEC
        val helper = codec.createHelper()
        val identity = testIdentity()
        val gameKeys = MitmHandshake.generatePair()
        val original = loginPacket(
            2168,
            listOf(fakeChainJwt(gameKeys)),
            signWith(gameKeys, """{"extraData":{"displayName":"Alex"}}""")
        )

        val rewritten = LoginRewrite.serverSideLogin(original, identity)
        val buffer = Unpooled.buffer()
        codec.tryEncode(helper, buffer, rewritten)

        val decoded = codec.tryDecode(
            helper,
            buffer,
            codec.getPacketDefinition(LoginPacket::class.java).id
        ) as? LoginPacket

        check("rewritten login encodes and decodes", decoded != null)
        if (decoded != null) {
            check("decoded protocol version matches", decoded.protocolVersion == 2168)
            check("decoded chain matches the account chain", chainOf(decoded) == identity.chain)
            check("decoded client JWT still verifies", verifies(decoded.clientJwt!!, identity.keyPair.public))
            check(
                "decoded JWT keeps the display name",
                JwtClaimReader.displayName(decoded.clientJwt) == "Alex"
            )
        }

        // The handshake we send the game must also be wire-valid.
        val relay = RelaySideKeyPair()
        val handshake = MitmHandshake.clientSideHandshake(relay, MitmHandshake.newSalt())
        val handshakeBuffer = Unpooled.buffer()
        codec.tryEncode(helper, handshakeBuffer, handshake)
        val decodedHandshake = codec.tryDecode(
            helper,
            handshakeBuffer,
            codec.getPacketDefinition(ServerToClientHandshakePacket::class.java).id
        ) as? ServerToClientHandshakePacket
        check("handshake encodes and decodes", decodedHandshake != null)
        check(
            "decoded handshake still carries the relay key",
            decodedHandshake != null &&
                JwtClaimReader.handshakeServerKey(decodedHandshake.jwt)?.encoded
                    ?.contentEquals(relay.publicKey.encoded) == true
        )
    }

    private fun payloadChecks() {
        check("payload detector accepts an object", LoginRewrite.looksLikePayload("""{"a":1}"""))
        check("payload detector rejects a token", !LoginRewrite.looksLikePayload("a.b.c"))
        check("payload detector rejects null", !LoginRewrite.looksLikePayload(null))
        check("minimal payload parses as a JWT body", LoginRewrite.looksLikePayload(LoginRewrite.minimalPayload()))
        val payload = LoginRewrite.payloadOf(signWith(MitmHandshake.generatePair(), """{"extraData":{"displayName":"Zed"}}"""))
        check("payload of a token is readable", payload != null && payload.contains("Zed"))
        check("payload of junk is null", LoginRewrite.payloadOf("junk") == null)
        check("public key header is base64", runCatching {
            Base64.getDecoder().decode(LoginRewrite.publicKeyHeader(MitmHandshake.generatePair()))
        }.isSuccess)
    }

    // ------------------------------------------------------------------- helpers

    private fun testIdentity(): AccountIdentity {
        val pair = MitmHandshake.generatePair()
        val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
        val chainJwt = signWith(
            pair,
            """{"identityPublicKey":"$publicKey","extraData":{"XUID":"2535400000000000","displayName":"TestPlayer"}}"""
        )
        return AccountIdentity(
            chain = listOf(chainJwt),
            keyPair = pair,
            displayName = "TestPlayer",
            xuid = "2535400000000000"
        )
    }

    /** A chain JWT that binds [keyPair]'s public key, signed by it (a stand-in for Mojang's). */
    private fun fakeChainJwt(keyPair: KeyPair): String {
        val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        return signWith(keyPair, """{"identityPublicKey":"$publicKey","extraData":{"displayName":"Alex"}}""")
    }

    private fun signWith(keyPair: KeyPair, payloadJson: String): String {
        val jws = JsonWebSignature()
        jws.algorithmHeaderValue = AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384
        jws.key = keyPair.private
        jws.payload = payloadJson
        jws.setHeader("x5u", Base64.getEncoder().encodeToString(keyPair.public.encoded))
        return jws.compactSerialization
    }

    private fun verifies(jwt: String, publicKey: java.security.PublicKey): Boolean = try {
        JsonWebSignature().apply {
            compactSerialization = jwt
            key = publicKey
        }.verifySignature()
    } catch (_: Exception) {
        false
    }

    private fun chainOf(packet: LoginPacket): List<String> =
        (packet.authPayload as? CertificateChainPayload)?.chain.orEmpty()

    private fun loginPacket(protocol: Int, chain: List<String>, clientJwt: String?): LoginPacket =
        LoginPacket().apply {
            protocolVersion = protocol
            authPayload = CertificateChainPayload(chain, AuthType.FULL)
            this.clientJwt = clientJwt
        }

    private fun x5uOf(jwt: String): String? =
        com.proxy.mcbedrock.net.JwtScan.stringField(
            com.proxy.mcbedrock.net.JwtScan.split(jwt)?.headerJson,
            "x5u"
        )

    private fun algorithmOf(jwt: String): String? =
        com.proxy.mcbedrock.net.JwtScan.stringField(
            com.proxy.mcbedrock.net.JwtScan.split(jwt)?.headerJson,
            "alg"
        )
}
