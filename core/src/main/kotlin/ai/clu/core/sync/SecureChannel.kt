package ai.clu.core.sync

import ai.clu.core.util.randomBytes
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    fun publicFrom(bytes: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    fun privateFrom(bytes: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))

    fun ecdh(priv: PrivateKey, pub: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").apply { init(priv); doPhase(pub, true) }.generateSecret()

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256"); mac.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { mac.update(it) }
        return mac.doFinal()
    }

    /** HKDF-SHA256 (RFC 5869). */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int = 32): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0); var pos = 0; var i = 1
        while (pos < length) {
            t = hmac(prk, t, info.toByteArray(), byteArrayOf(i.toByte()))
            val n = minOf(t.size, length - pos); System.arraycopy(t, 0, out, pos, n); pos += n; i++
        }
        return out
    }

    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").apply { parts.forEach { update(it) } }.digest()

    /** Short authentication string shown on both devices during pairing (6 digits). */
    fun sas(sharedKey: ByteArray, transcript: ByteArray): String {
        val h = hmac(sharedKey, "clu-sas".toByteArray(), transcript)
        val v = ((h[0].toLong() and 0xff) shl 24 or ((h[1].toLong() and 0xff) shl 16) or ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)) % 1_000_000
        return "%06d".format(v)
    }
}

/** Device long-term identity key, persisted protected by the platform (Android Keystore wraps it). */
interface KeyVault {
    fun protect(plain: ByteArray): ByteArray
    fun unprotect(sealed: ByteArray): ByteArray
}

object PlainKeyVault : KeyVault {
    override fun protect(plain: ByteArray) = plain
    override fun unprotect(sealed: ByteArray) = sealed
}

class ChannelException(msg: String) : IOException(msg)

/**
 * Length-prefixed AES-256-GCM frames with per-direction keys and strictly increasing nonces (replay/reorder
 * protection). Max frame 8 MiB.
 */
class SecureChannel(input: InputStream, output: OutputStream, sendKey: ByteArray, recvKey: ByteArray) {
    private val inp = DataInputStream(input.buffered())
    private val out = DataOutputStream(output.buffered())
    private val sendK = SecretKeySpec(sendKey, "AES")
    private val recvK = SecretKeySpec(recvKey, "AES")
    private var sendCounter = 0L
    private var recvCounter = -1L

    @Synchronized
    fun send(plain: ByteArray) {
        val nonce = ByteBuffer.allocate(12).putInt(0x434c5500).putLong(sendCounter++).array()
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, sendK, GCMParameterSpec(128, nonce))
        val ct = c.doFinal(plain)
        out.writeInt(12 + ct.size); out.write(nonce); out.write(ct); out.flush()
    }

    fun receive(): ByteArray {
        val len = inp.readInt()
        if (len < 28 || len > 8 * 1024 * 1024) throw ChannelException("bad frame length $len")
        val nonce = ByteArray(12); inp.readFully(nonce)
        val ct = ByteArray(len - 12); inp.readFully(ct)
        val counter = ByteBuffer.wrap(nonce, 4, 8).long
        if (counter <= recvCounter) throw ChannelException("replayed or reordered frame")
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, recvK, GCMParameterSpec(128, nonce))
        val plain = try { c.doFinal(ct) } catch (e: GeneralSecurityException) { throw ChannelException("frame authentication failed") }
        recvCounter = counter
        return plain
    }

    companion object {
        /** Raw (unencrypted) handshake frames. */
        fun writeRaw(out: OutputStream, bytes: ByteArray) { val d = DataOutputStream(out); d.writeInt(bytes.size); d.write(bytes); d.flush() }
        fun readRaw(input: InputStream): ByteArray {
            val d = DataInputStream(input); val n = d.readInt()
            if (n < 0 || n > 64 * 1024) throw ChannelException("bad handshake frame")
            return ByteArray(n).also { d.readFully(it) }
        }
        fun nonce(): ByteArray = randomBytes(16)
    }
}
