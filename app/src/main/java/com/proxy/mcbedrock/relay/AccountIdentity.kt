package com.proxy.mcbedrock.relay

import org.cloudburstmc.protocol.bedrock.data.auth.AuthType
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.util.Base64

/**
 * The account material the relay uses when it speaks to the real server as the
 * client.
 *
 * This is the one piece of the project that is **not** read-only: to terminate the
 * session and be able to decrypt it, the relay has to log in to the server itself
 * (that is what makes the key exchange one it holds a private half of). Everything
 * here exists for exactly that and nothing else:
 *
 *  - [chain] is the Mojang-signed certificate chain for the signed-in account,
 *  - [keyPair] is the key that chain binds to (its public half must match the
 *    chain's identity key, its private half signs the client JWT),
 *  - [displayName] / [xuid] are only used to label the UI.
 *
 * The key never leaves the device, and no copy of the chain or key is ever sent to
 * this project's author: there is no server component to send it to.
 */
data class AccountIdentity(
    val chain: List<String>,
    val keyPair: KeyPair,
    val displayName: String? = null,
    val xuid: String? = null
) {

    val publicKeyBase64: String
        get() = Base64.getEncoder().encodeToString(keyPair.public.encoded)

    val privateKey: PrivateKey get() = keyPair.private

    /** The identity public key the chain binds, if it can be read (display only). */
    val chainIdentityKey: String?
        get() = chain.asReversed().firstNotNullOfOrNull { jwt ->
            JwtClaimReader.identityPublicKey(jwt)
        }

    fun isValid(): Boolean = chain.isNotEmpty()

    fun describe(): String = when {
        displayName != null -> "$displayName (chain of ${chain.size})"
        else -> "chain of ${chain.size}"
    }

    fun authPayload(): CertificateChainPayload = CertificateChainPayload(chain, AuthType.FULL)
}

/**
 * The keypair the relay uses when it acts as the *server* towards the game.
 *
 * Generated fresh per session: the game derives its session key from this public
 * key and the salt we hand it, which is precisely why the relay can read the
 * client leg at all.
 */
class RelaySideKeyPair(val keyPair: KeyPair = EncryptionUtils.createKeyPair()) {

    val publicKey: PublicKey get() = keyPair.public
    val privateKey: PrivateKey get() = keyPair.private

    val publicKeyBase64: String
        get() = Base64.getEncoder().encodeToString(publicKey.encoded)
}

/**
 * Reading the claims that matter out of login/handshake JWTs.
 *
 * Display-only, no verification — the codec does the real validation when it is
 * pointed at a server.
 */
object JwtClaimReader {

    /** `identityPublicKey` from a chain JWT payload (the key the chain binds). */
    fun identityPublicKey(chainJwt: String): String? =
        com.proxy.mcbedrock.net.JwtScan.deepStringField(
            com.proxy.mcbedrock.net.JwtScan.split(chainJwt)?.payloadJson,
            "identityPublicKey"
        )

    /** The server's public key from a `ServerToClientHandshake` JWT (`x5u`). */
    fun handshakeServerKey(handshakeJwt: String): ECPublicKey? {
        val header = com.proxy.mcbedrock.net.JwtScan.split(handshakeJwt)?.headerJson ?: return null
        val x5u = com.proxy.mcbedrock.net.JwtScan.stringField(header, "x5u") ?: return null
        return EncryptionUtils.parseKey(x5u)
    }

    /**
     * The salt carried by a `ServerToClientHandshake` JWT.
     *
     * Measured, not assumed: the library writes it as **standard** base64 with
     * padding (`EncryptionUtils.createHandshakeJwt` produces e.g.
     * `{"salt":"0k2qOWXbIAA8wgUyxXAzow=="}`), so the URL-safe decoder alone is not
     * enough — it rejects `+` and `/`. Both alphabets are accepted here, standard
     * first, because a wrong guess here is a silent "no session key" later.
     */
    fun handshakeSalt(handshakeJwt: String): ByteArray? {
        val payload = com.proxy.mcbedrock.net.JwtScan.split(handshakeJwt)?.payloadJson ?: return null
        val salt = com.proxy.mcbedrock.net.JwtScan.stringField(payload, "salt") ?: return null
        return decodeAnyBase64(salt)
    }

    /** Decodes standard or URL-safe base64, with or without padding. */
    fun decodeAnyBase64(value: String): ByteArray? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        val padded = when (trimmed.length % 4) {
            2 -> "$trimmed=="
            3 -> "$trimmed="
            else -> trimmed
        }
        for (decoder in listOf(Base64.getDecoder(), Base64.getUrlDecoder())) {
            try {
                return decoder.decode(padded)
            } catch (_: IllegalArgumentException) {
                // try the next alphabet
            }
        }
        return null
    }

    /** The account name a login JWT claims, in either of the shapes clients use. */
    fun displayName(loginJwt: String?): String? {
        val payload = com.proxy.mcbedrock.net.JwtScan.split(loginJwt)?.payloadJson ?: return null
        return com.proxy.mcbedrock.net.JwtScan.deepStringField(payload, "displayName")
            ?: com.proxy.mcbedrock.net.JwtScan.deepStringField(payload, "DisplayName")
    }

    /** The XUID a login JWT claims. */
    fun xuid(loginJwt: String?): String? {
        val payload = com.proxy.mcbedrock.net.JwtScan.split(loginJwt)?.payloadJson ?: return null
        return com.proxy.mcbedrock.net.JwtScan.stringField(payload, "XUID")
            ?: com.proxy.mcbedrock.net.JwtScan.stringField(payload, "xuid")
    }

    /** The `x5u` public key a client put in its own login JWT. */
    fun clientKeyFromLogin(login: LoginPacket): ECPublicKey? {
        val header = com.proxy.mcbedrock.net.JwtScan.split(login.clientJwt)?.headerJson ?: return null
        val x5u = com.proxy.mcbedrock.net.JwtScan.stringField(header, "x5u") ?: return null
        return try {
            EncryptionUtils.parseKey(x5u)
        } catch (_: Exception) {
            null
        }
    }
}
