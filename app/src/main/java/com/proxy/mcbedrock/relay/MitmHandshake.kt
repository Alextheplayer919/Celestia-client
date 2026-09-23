package com.proxy.mcbedrock.relay

import org.cloudburstmc.protocol.bedrock.packet.ServerToClientHandshakePacket
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import javax.crypto.Cipher
import javax.crypto.SecretKey

/**
 * The cryptography of a terminated session, wrapped so it can be reasoned about
 * (and tested) without a socket anywhere in sight.
 *
 * Bedrock's login ends with a `ServerToClientHandshake` carrying a JWT whose
 * header holds the server's public key (`x5u`) and whose payload holds a `salt`.
 * Both sides then compute the same secret with ECDH — the client with its own
 * private key and the server's public key, the server with its own private key and
 * the client's public key — and everything afterwards is encrypted with it.
 *
 * That is why a passive relay can read nothing, and why a terminating one can read
 * everything: it is the "client" on the server leg (holding the account key) and the
 * "server" on the client leg (holding a key it generated itself).
 *
 *   game  ──(our generated key + salt)──►  relay  ──(account key + real server key)──►  server
 */
object MitmHandshake {

    /** Just the salt (`generateRandomToken()` is 16 random bytes). */
    fun newSalt(): ByteArray = EncryptionUtils.generateRandomToken()

    /**
     * The handshake the relay sends to the *game* when acting as its server.
     * Signed with the relay's own fresh key, so the game derives a secret the relay
     * can reproduce.
     */
    fun clientSideHandshake(relayKeys: RelaySideKeyPair, salt: ByteArray): ServerToClientHandshakePacket {
        val packet = ServerToClientHandshakePacket()
        packet.jwt = EncryptionUtils.createHandshakeJwt(relayKeys.keyPair, salt)
        return packet
    }

    /**
     * The secret for a leg, derived from our private key and the other side's public
     * key. Same function for both legs; only the keys differ.
     */
    fun deriveSecret(ownPrivateKey: PrivateKey, otherPublicKey: PublicKey, salt: ByteArray): SecretKey =
        EncryptionUtils.getSecretKey(ownPrivateKey, otherPublicKey, salt)

    /** The server leg: our account key against the real server's handshake key. */
    fun serverLegSecret(identity: AccountIdentity, serverHandshakeJwt: String): SecretKey? {
        val serverKey = JwtClaimReader.handshakeServerKey(serverHandshakeJwt) ?: return null
        val salt = JwtClaimReader.handshakeSalt(serverHandshakeJwt) ?: return null
        return deriveSecret(identity.privateKey, serverKey, salt)
    }

    /** The client leg: the key we generated against the game's login key. */
    fun clientLegSecret(relayKeys: RelaySideKeyPair, gamePublicKey: PublicKey, salt: ByteArray): SecretKey =
        deriveSecret(relayKeys.privateKey, gamePublicKey, salt)

    /**
     * `createCipher(useCtr, encrypt, key)` — verified against the library's own
     * bytecode rather than guessed. Vanilla Bedrock uses **CFB8** (the `useCtr` flag
     * false), so the relay must too, or the game and the server would disagree with
     * us and with each other.
     */
    fun encryptCipher(secret: SecretKey): Cipher = EncryptionUtils.createCipher(false, true, secret)

    fun decryptCipher(secret: SecretKey): Cipher = EncryptionUtils.createCipher(false, false, secret)

    /** Generates a throwaway pair, used by tests and by "start fresh" in the UI. */
    fun generatePair(): KeyPair = EncryptionUtils.createKeyPair()
}
