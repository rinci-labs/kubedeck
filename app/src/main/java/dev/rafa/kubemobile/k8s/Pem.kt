package dev.rafa.kubemobile.k8s

import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * PEM handling for kubeconfig credentials: client certificates, private keys (PKCS#8, PKCS#1 RSA
 * and SEC1 EC) and certificate authorities. Android's default provider rejects PKCS#1, which is
 * what `openssl rsa` and older generators emit, so those blocks are re-wrapped into PKCS#8 here.
 */
object Pem {

    fun decodeBase64(value: String): ByteArray =
        try {
            Base64.decode(value, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            java.util.Base64.getMimeDecoder().decode(value)
        }

    fun normalize(pem: String): String = pem.replace("\r\n", "\n").trim()

    fun certificates(pem: String): List<X509Certificate> {
        if (pem.isBlank()) return emptyList()
        val factory = CertificateFactory.getInstance("X.509")
        return factory.generateCertificates(ByteArrayInputStream(normalize(pem).toByteArray()))
            .map { it as X509Certificate }
    }

    fun privateKey(pem: String): PrivateKey {
        val normalized = normalize(pem)
        val body = extractBody(normalized) ?: error("No PEM private key block found")
        val der = decodeBase64(body)
        if (normalized.contains("BEGIN PRIVATE KEY")) return pkcs8(der)
        if (normalized.contains("BEGIN RSA PRIVATE KEY")) return pkcs8(wrapRsaPkcs1(der))
        if (normalized.contains("BEGIN EC PRIVATE KEY")) return pkcs8(wrapEcSec1(der))
        if (normalized.contains("BEGIN ENCRYPTED PRIVATE KEY")) {
            error("Encrypted private keys are not supported. Decrypt the key before importing.")
        }
        // Blunder through PKCS#8 as a last resort.
        return pkcs8(der)
    }

    private fun extractBody(pem: String): String? {
        val lines = pem.lines()
        // Skip leading blocks such as `EC PARAMETERS` that `openssl ecparam -genkey` emits.
        val start = lines.indexOfFirst { it.startsWith("-----BEGIN") && it.contains("PRIVATE KEY") }
            .takeIf { it >= 0 } ?: lines.indexOfFirst { it.startsWith("-----BEGIN") }
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("-----END") } ?: -1
        if (start < 0 || end <= start) return null
        return lines.subList(start + 1, end).joinToString("\n").trim()
    }

    private fun pkcs8(der: ByteArray): PrivateKey {
        val spec = PKCS8EncodedKeySpec(der)
        for (algorithm in listOf("RSA", "EC", "Ed25519", "DSA")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec)
            } catch (_: Exception) {
                // try the next algorithm
            }
        }
        error("Unsupported private key algorithm")
    }

    private val RSA_OID = byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01)

    /** PKCS#1 RSAPrivateKey -> PKCS#8 PrivateKeyInfo. */
    private fun wrapRsaPkcs1(der: ByteArray): ByteArray {
        val algorithm = derSequence(RSA_OID, derNull())
        return derSequence(derInteger(byteArrayOf(0)), algorithm, derOctetString(der))
    }

    private val EC_PUBLIC_KEY_OID = byteArrayOf(0x06, 0x07, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01)

    /**
     * SEC1 ECPrivateKey -> PKCS#8 PrivateKeyInfo, reusing the embedded named-curve OID. The curve
     * lives inside the explicit `[0] parameters` field, not at the top level of the SEQUENCE.
     */
    private fun wrapEcSec1(der: ByteArray): ByteArray {
        val parameters = derItems(der).firstOrNull { it.isNotEmpty() && it[0] == 0xA0.toByte() }
        val curveOid = parameters?.let { derContent(it) }?.takeIf { it.isNotEmpty() && it[0] == 0x06.toByte() }
            ?: error("EC private key is missing its named curve parameters")
        val algorithm = derSequence(EC_PUBLIC_KEY_OID, curveOid)
        return derSequence(derInteger(byteArrayOf(0)), algorithm, derOctetString(der))
    }

    fun keyStore(certificates: List<X509Certificate>, key: PrivateKey, password: CharArray): KeyStore {
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        store.setKeyEntry("client", key, password, certificates.toTypedArray())
        return store
    }

    fun keyManagerFactory(certificates: List<X509Certificate>, key: PrivateKey): KeyManagerFactory =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore(certificates, key, "kube".toCharArray()), "kube".toCharArray())
        }

    fun trustManager(caPem: String?): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        if (caPem.isNullOrBlank()) {
            factory.init(null as KeyStore?)
        } else {
            val store = KeyStore.getInstance("PKCS12")
            store.load(null, null)
            certificates(caPem).forEachIndexed { index, certificate ->
                store.setCertificateEntry("ca-$index", certificate)
            }
            factory.init(store)
        }
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** Accepts any server certificate. Matches kubectl's `--insecure-skip-tls-verify`. */
    fun insecureTrustManager(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun keyManagers(certPem: String?, keyPem: String?): Array<javax.net.ssl.KeyManager>? {
        if (certPem.isNullOrBlank() || keyPem.isNullOrBlank()) return null
        return keyManagerFactory(certificates(certPem), privateKey(keyPem)).keyManagers
    }

    fun trustManagers(trust: X509TrustManager): Array<TrustManager> = arrayOf(trust)

    /* ---------------------------------- minimal DER writer ---------------------------------- */

    private fun derLength(length: Int): ByteArray = when {
        length < 0x80 -> byteArrayOf(length.toByte())
        length < 0x100 -> byteArrayOf(0x81.toByte(), length.toByte())
        length < 0x10000 -> byteArrayOf(0x82.toByte(), (length shr 8).toByte(), length.toByte())
        else -> byteArrayOf(0x83.toByte(), (length shr 16).toByte(), (length shr 8).toByte(), length.toByte())
    }

    private fun derEncode(tag: Int, content: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + derLength(content.size) + content

    private fun derSequence(vararg parts: ByteArray) = derEncode(0x30, parts.reduce { a, b -> a + b })
    private fun derInteger(value: ByteArray) = derEncode(0x02, value)
    private fun derNull() = byteArrayOf(0x05, 0x00)
    private fun derOctetString(value: ByteArray) = derEncode(0x04, value)

    /** Strips the tag and length header from a single TLV blob. */
    private fun derContent(tlv: ByteArray): ByteArray {
        if (tlv.size < 2) return ByteArray(0)
        val first = tlv[1].toInt() and 0xFF
        val header = if (first and 0x80 == 0) 2 else 2 + (first and 0x7F)
        return if (header > tlv.size) ByteArray(0) else tlv.copyOfRange(header, tlv.size)
    }

    /** Splits a DER SEQUENCE into its raw child TLV blobs. */
    private fun derItems(der: ByteArray): List<ByteArray> {
        var offset = 0
        if (der.isEmpty() || der[0] != 0x30.toByte()) return emptyList()
        offset = 1
        var length = der[offset].toInt() and 0xFF
        offset++
        if (length and 0x80 != 0) {
            val count = length and 0x7F
            length = 0
            repeat(count) {
                length = (length shl 8) or (der[offset].toInt() and 0xFF)
                offset++
            }
        }
        val end = offset + length
        val out = ArrayList<ByteArray>()
        while (offset < end && offset < der.size) {
            val start = offset
            offset++
            var itemLength = der[offset].toInt() and 0xFF
            offset++
            if (itemLength and 0x80 != 0) {
                val count = itemLength and 0x7F
                itemLength = 0
                repeat(count) {
                    itemLength = (itemLength shl 8) or (der[offset].toInt() and 0xFF)
                    offset++
                }
            }
            offset += itemLength
            if (offset > der.size) break
            out += der.copyOfRange(start, offset)
        }
        return out
    }

    fun readFully(stream: InputStream, limit: Int = 1 shl 22): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) error("Content exceeds limit")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
