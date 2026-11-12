package com.vknxworks.tapoctrl.tapo

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.vknxworks.tapoctrl.net.LanAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.CRC32

/** A device found on the network. Model, type and MAC are known only when it answered UDP discovery. */
data class Found(
    val ip: String,
    val model: String? = null,
    val type: String? = null,
    val mac: String? = null,
    /** "KLAP" for current firmware, "AES" for the older securePassthrough firmware. */
    val encryption: String? = null,
) {
    val legacy: Boolean get() = encryption != null && !encryption.equals("KLAP", ignoreCase = true)
}

/**
 * Finds Tapo devices on the phone's own /24 subnet, two ways, both local only:
 * 1. TP-Link's UDP discovery on port 20002 (broadcast on the subnet). The request carries a throwaway RSA public
 *    key generated on the phone for this scan; no TP-Link or cloud key is involved.
 * 2. A KLAP probe: POST /app/handshake1 to every address with a random 16-byte seed. Devices with current firmware
 *    answer with 48 bytes. Nothing about your account is sent.
 */
object Discovery {
    const val PORT = 20002

    /** This phone's IPv4 address on Wi-Fi or Ethernet, or null when it isn't on a local network. */
    fun localAddress(context: Context): Inet4Address? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return null
        val props = cm.getLinkProperties(net) ?: return null
        return props.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull { LanAddress.isPrivate(it) }
    }

    /** The other 253 addresses of [self]'s /24, in order. */
    fun subnet(self: Inet4Address): List<Inet4Address> {
        val b = self.address
        return (1..254).filter { it != (b[3].toInt() and 0xFF) }.map {
            InetAddress.getByAddress(byteArrayOf(b[0], b[1], b[2], it.toByte())) as Inet4Address
        }
    }

    suspend fun scan(self: Inet4Address, onFound: (Found) -> Unit): List<Found> = coroutineScope {
        val found = java.util.concurrent.ConcurrentHashMap<String, Found>()
        fun add(f: Found) {
            val merged = found.merge(f.ip, f) { a, b ->
                Found(a.ip, a.model ?: b.model, a.type ?: b.type, a.mac ?: b.mac, a.encryption ?: b.encryption)
            }
            if (merged != null) onFound(merged)
        }
        val udp = async(Dispatchers.IO) { runCatching { udpDiscover(self) }.getOrDefault(emptyList()).forEach(::add) }
        val gate = Semaphore(48)
        subnet(self).map { ip ->
            async(Dispatchers.IO) {
                gate.withPermit { if (probeKlap(ip)) add(Found(LanAddress.text(ip), encryption = "KLAP")) }
            }
        }.awaitAll()
        udp.await()
        found.values.sortedBy { ipSortKey(it.ip) }
    }

    fun ipSortKey(ip: String): Long = ip.split('.').fold(0L) { acc, p -> acc * 256 + (p.toLongOrNull() ?: 0) }

    /** True when [ip] answers KLAP handshake1 with 48 bytes. */
    fun probeKlap(ip: Inet4Address): Boolean = try {
        val seed = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val r = LanHttp.post(ip, "/app/handshake1", seed, null, connectMs = 700, readMs = 1500)
        r.code == 200 && r.body.size == 48
    } catch (e: IOException) {
        false
    } catch (e: TapoException) {
        false
    }

    private suspend fun udpDiscover(self: Inet4Address): List<Found> = withContext(Dispatchers.IO) {
        val packet = discoveryPacket(throwawayPublicKeyPem(), SecureRandom().nextInt())
        val b = self.address
        val broadcast = InetAddress.getByAddress(byteArrayOf(b[0], b[1], b[2], 255.toByte()))
        val out = mutableListOf<Found>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 500
            repeat(3) {
                socket.send(DatagramPacket(packet, packet.size, broadcast, PORT))
                val end = System.currentTimeMillis() + 1500
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < end) {
                    val dp = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(dp)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val from = dp.address as? Inet4Address ?: continue
                    if (!LanAddress.isPrivate(from)) continue
                    parseDiscoveryReply(buf.copyOf(dp.length), LanAddress.text(from))?.let { out += it }
                }
            }
        }
        out.distinctBy { it.ip }
    }

    /** A fresh RSA public key in PEM form, used only to fill the discovery request; the private key is dropped. */
    private fun throwawayPublicKeyPem(): String {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(kp.public.encoded)
        return "-----BEGIN PUBLIC KEY-----\n$b64\n-----END PUBLIC KEY-----\n"
    }

    /**
     * The 16-byte TDP header (version 2, type 0, op 1, payload length, flags 17, 0, a random serial, CRC32 of the
     * whole packet computed with 0x5A6B7C8D in the CRC field) followed by the JSON payload.
     */
    fun discoveryPacket(publicKeyPem: String, serial: Int): ByteArray {
        val payload = buildJsonObject { putJsonObject("params") { put("rsa_key", publicKeyPem) } }.toString().toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(16 + payload.size)
        buf.put(2).put(0).putShort(1).putShort(payload.size.toShort()).put(17).put(0).putInt(serial).putInt(0x5A6B7C8D)
        buf.put(payload)
        val bytes = buf.array()
        val crc = CRC32().apply { update(bytes) }.value.toInt()
        ByteBuffer.wrap(bytes).putInt(12, crc)
        return bytes
    }

    /** The reply is the same header followed by {"result": {...}, "error_code": 0}. */
    fun parseDiscoveryReply(data: ByteArray, fromIp: String): Found? {
        if (data.size <= 16) return null
        val json = try {
            Json.parseToJsonElement(data.copyOfRange(16, data.size).toString(Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            return null
        }
        val r = json["result"] as? JsonObject ?: return null
        fun s(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content
        // The address the reply came from, not what it claims, is where TapoCtrl will connect.
        return Found(
            ip = fromIp,
            model = s(r, "device_model"),
            type = s(r, "device_type"),
            mac = s(r, "mac"),
            encryption = s(r["mgt_encrypt_schm"] as? JsonObject, "encrypt_type"),
        )
    }
}
