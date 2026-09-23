package com.proxy.mcbedrock.relay

import org.cloudburstmc.protocol.bedrock.packet.LoginPacket
import org.jose4j.jws.AlgorithmIdentifiers
import org.jose4j.jws.JsonWebSignature
import org.jose4j.jwt.JwtClaims
import java.util.Base64

/**
 * Rewriting the login so the real server sees a connection it can authenticate.
 *
 * This is the part that makes the whole approach work, and the part with the
 * clearest costs: the server no longer authenticates the *game*, it authenticates
 * *this app*, using the account that was signed in here. Read
 * docs/mitm-plan.md before changing anything in this file.
 *
 * Two directions:
 *  - [serverSideLogin] — what the relay sends upstream: the saved chain plus a
 *    client JWT re-signed with the account key, so the server can verify it and
 *    hand back a handshake the relay can decrypt;
 *  - [clientJwtPayload] — the payload of the game's own login JWT is kept, because
 *    it already describes the signed-in player (XUID, display name) and therefore
 *    matches the chain we send.
 */
object LoginRewrite {

    /**
     * Builds the login to send to the real server.
     *
     * [original] is the game's login, used for two things: its protocol version
     * (the server must see what the client actually speaks) and its client JWT
     * payload. The chain and the signing key are the account's.
     */
    fun serverSideLogin(original: LoginPacket, identity: AccountIdentity): LoginPacket {
        val rewritten = LoginPacket()
        rewritten.protocolVersion = original.protocolVersion
        rewritten.authPayload = identity.authPayload()
        rewritten.clientJwt = signClientJwt(clientJwtPayload(original.clientJwt), identity)
        return rewritten
    }

    /**
     * The claims to put in the re-signed JWT: the game's own payload when it is
     * readable, otherwise a minimal payload built from the account.
     */
    fun clientJwtPayload(originalClientJwt: String?): String {
        val payload = com.proxy.mcbedrock.net.JwtScan.split(originalClientJwt)?.payloadJson
        return payload?.takeIf { it.startsWith("{") } ?: minimalPayload()
    }

    /**
     * Signs [payloadJson] with the account key, ES384 with the public key in the
     * `x5u` header — the shape the server verifies against the chain's identity key.
     */
    fun signClientJwt(payloadJson: String, identity: AccountIdentity): String {
        val jws = JsonWebSignature()
        jws.algorithmHeaderValue = AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384
        jws.key = identity.privateKey
        jws.payload = payloadJson
        jws.setHeader("x5u", identity.publicKeyBase64)
        return jws.compactSerialization
    }

    /** A payload with the fields a server looks for, when nothing else is available. */
    fun minimalPayload(): String {
        val now = System.currentTimeMillis() / 1000
        val claims = JwtClaims().apply {
            setIssuedAtToNow()
            // jose4j wants its own NumericDate type here, not a bare int.
            expirationTime = org.jose4j.jwt.NumericDate.fromSeconds(now + PAYLOAD_LIFETIME_SECONDS)
            notBefore = org.jose4j.jwt.NumericDate.fromSeconds(now)
            issuer = "Minecraft"
        }
        return claims.toJson()
    }

    /** True when the payload is a JWT body we can re-sign as-is. */
    fun looksLikePayload(payloadJson: String?): Boolean =
        payloadJson != null && payloadJson.trimStart().startsWith("{")

    /**
     * Decodes the base64url payload of a token, for callers that only have the raw
     * string. Returns null when it is not a token.
     */
    fun payloadOf(jwt: String?): String? =
        com.proxy.mcbedrock.net.JwtScan.split(jwt)?.payloadJson

    /** Base64 (not url) encoding of a key pair's public half, as the x5u header wants. */
    fun publicKeyHeader(keyPair: java.security.KeyPair): String =
        Base64.getEncoder().encodeToString(keyPair.public.encoded)

    private const val PAYLOAD_LIFETIME_SECONDS = 24 * 60 * 60
}
