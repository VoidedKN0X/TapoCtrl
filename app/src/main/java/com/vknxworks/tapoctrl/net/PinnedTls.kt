package com.vknxworks.tapoctrl.net

import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * HTTPS to a camera on your network, which presents a certificate it made itself. TapoCtrl trusts it only by
 * the SHA-256 fingerprint you confirmed when adding the camera (the Ctrl series' pattern, as in PrintCtrl's
 * `Tls`), and nothing else: no certificate authority, no other certificate. Private addresses only, no proxy,
 * no redirects.
 */
object PinnedTls {
    /** SHA-256 of the DER-encoded certificate, as colon-separated upper-case hex. */
    fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

    /** The certificate isn't the one you confirmed. Nothing was sent. */
    class PinMismatch : CertificateException("Certificate changed")

    private class Pinned(private val pin: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { throw CertificateException() }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("No certificate")
            if (!fingerprint(leaf).equals(pin, ignoreCase = true)) throw PinMismatch()
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private fun context(pin: String): SSLContext =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(Pinned(pin)), null) }

    /** The camera's certificate rarely names its address; the pinned certificate is what's checked instead. */
    private fun verifier(pin: String) = HostnameVerifier { _, session ->
        val leaf = try { session.peerCertificates.firstOrNull() as? X509Certificate } catch (_: Exception) { null }
        leaf != null && fingerprint(leaf).equals(pin, ignoreCase = true)
    }

    class Reply(val code: Int, val body: ByteArray)

    /** One POST over HTTPS on port 443, trusting only the certificate with fingerprint [pin]. */
    fun post(
        ip: Inet4Address,
        pin: String,
        path: String,
        body: ByteArray,
        headers: Map<String, String>,
        connectMs: Int = 4000,
        readMs: Int = 10_000,
    ): Reply {
        if (!LanAddress.isPrivate(ip)) throw IOException("Not a local address")
        val url = URL("https", LanAddress.text(ip), 443, path)
        val c = url.openConnection(Proxy.NO_PROXY) as HttpsURLConnection
        try {
            c.sslSocketFactory = context(pin).socketFactory
            c.hostnameVerifier = verifier(pin)
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.connectTimeout = connectMs
            c.readTimeout = readMs
            c.requestMethod = "POST"
            c.doOutput = true
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val bytes = stream?.use { s -> s.readNBytes(MAX_BODY) } ?: ByteArray(0)
            return Reply(code, bytes)
        } finally {
            c.disconnect()
        }
    }

    /** True when [e] (or a cause) is a pinned certificate that changed. */
    fun isPinMismatch(e: Throwable): Boolean =
        generateSequence(e) { it.cause.takeIf { c -> c !== it } }.any { it is PinMismatch }

    private class Captured : CertificateException("captured")

    /**
     * Reads the certificate a camera presents on port 443, without trusting it: the TLS handshake is stopped as
     * soon as the certificate arrives, so nothing (no login, no request) is sent. Its fingerprint is then shown
     * for you to confirm.
     */
    fun fetchLeaf(ip: Inet4Address, timeoutMs: Int = 4000): X509Certificate {
        if (!LanAddress.isPrivate(ip)) throw IOException("Not a local address")
        var chain: Array<out X509Certificate>? = null
        val capture = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, authType: String?) { throw CertificateException() }
            override fun checkServerTrusted(c: Array<out X509Certificate>?, authType: String?) {
                chain = c
                throw Captured()
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(capture), null) }
        val host = LanAddress.text(ip)
        val raw = Socket(Proxy.NO_PROXY)
        try {
            raw.connect(InetSocketAddress(ip, 443), timeoutMs)
            raw.soTimeout = timeoutMs
            val s = ctx.socketFactory.createSocket(raw, host, 443, true) as SSLSocket
            try {
                s.startHandshake()
            } catch (e: IOException) {
                if (chain == null) throw e
            } finally {
                runCatching { s.close() }
            }
        } finally {
            runCatching { raw.close() }
        }
        return chain?.firstOrNull() ?: throw IOException("No certificate")
    }

    private const val MAX_BODY = 1 shl 20
}
