package com.vknxworks.tapoctrl.tapo

import com.vknxworks.tapoctrl.net.LanAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
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
    /** The login version older (securePassthrough) firmware announces ("lv"): 2 means it checks hashes only. */
    val loginVersion: Int? = null,
    /** mgt_encrypt_schm.is_support_https: cameras answer true (they log in over HTTPS on port 443). */
    val https: Boolean? = null,
) {
    val legacy: Boolean get() = encryption != null && !encryption.equals("KLAP", ignoreCase = true) && !camera
    /** Tapo cameras (device type SMART.IPCAMERA): HTTPS on port 443 with their own certificate. */
    val camera: Boolean get() = type?.contains("CAMERA", ignoreCase = true) == true

    /** Two answers from the same address (UDP discovery and the KLAP probe) as one. */
    fun mergedWith(o: Found): Found = Found(
        ip, model ?: o.model, type ?: o.type, mac ?: o.mac, encryption ?: o.encryption, loginVersion ?: o.loginVersion, https ?: o.https,
    )
}

/**
 * Finds Tapo devices, two ways, both local only and both read-only (no device method is ever sent):
 * 1. TP-Link's UDP discovery on port 20002: broadcast on the Wi-Fi subnet when there is one, and the same packet
 *    to each address on its own (unicast), which also crosses a VPN such as Tailscale with a route to the network.
 *    The request carries a throwaway RSA public key generated on the phone for this scan; no TP-Link or cloud key
 *    is involved.
 * 2. A KLAP probe: POST /app/handshake1 to every address with a random 16-byte seed. Devices with current firmware
 *    answer with 48 bytes. Nothing about your account is sent.
 * Android-free (the phone's own network is found in `net/LocalNetwork`), so it is unit-tested on the JVM.
 */
object Discovery {
    const val PORT = 20002

    /** At most this many probes at once. */
    const val CONCURRENCY = 48

    /** How long to wait: short on the phone's own Wi-Fi, longer through a VPN. */
    class Timing(val connectMs: Int, val readMs: Int, val listenMs: Long) {
        companion object {
            val WIFI = Timing(connectMs = 700, readMs = 1500, listenMs = 1500)
            val RANGE = Timing(connectMs = 1500, readMs = 2500, listenMs = 2500)
        }
    }

    /** The other 253 addresses of [self]'s /24, in order. */
    fun subnet(self: Inet4Address): List<Inet4Address> {
        val b = self.address
        return (1..254).filter { it != (b[3].toInt() and 0xFF) }.map {
            InetAddress.getByAddress(byteArrayOf(b[0], b[1], b[2], it.toByte())) as Inet4Address
        }
    }

    /** The broadcast address of [self]'s /24. */
    fun broadcastOf(self: Inet4Address): Inet4Address {
        val b = self.address
        return InetAddress.getByAddress(byteArrayOf(b[0], b[1], b[2], 255.toByte())) as Inet4Address
    }

    /** Scans the phone's own Wi-Fi /24, as before (broadcast and every address). */
    suspend fun scan(self: Inet4Address, onProgress: (Int, Int) -> Unit = { _, _ -> }, onFound: (Found) -> Unit): List<Found> =
        scan(subnet(self), broadcastOf(self), Timing.WIFI, onProgress, onFound)

    /**
     * Scans [targets] (private addresses only; anything else is skipped): unicast UDP discovery to each, a
     * broadcast to [broadcast] when given, and the KLAP probe to each, at most [CONCURRENCY] at a time.
     * [onProgress] gets (addresses probed, total). Cancelling the coroutine stops the scan; a probe already
     * under way ends within its timeout.
     */
    suspend fun scan(
        targets: List<Inet4Address>,
        broadcast: Inet4Address?,
        timing: Timing,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onFound: (Found) -> Unit,
    ): List<Found> = coroutineScope {
        val list = targets.filter { LanAddress.isPrivate(it) }
        val found = java.util.concurrent.ConcurrentHashMap<String, Found>()
        fun add(f: Found) {
            val merged = found.merge(f.ip, f) { a, b -> a.mergedWith(b) }
            if (merged != null) onFound(merged)
        }
        val udp = async(Dispatchers.IO) {
            try {
                udpDiscover(list, broadcast, timing.listenMs, ::add)
            } catch (e: IOException) {
                // No UDP (for example no route): the KLAP probe still runs.
            }
        }
        val gate = Semaphore(CONCURRENCY)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        onProgress(0, list.size)
        list.map { ip ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    if (probeKlap(ip, timing.connectMs, timing.readMs)) add(Found(LanAddress.text(ip), encryption = "KLAP"))
                }
                onProgress(done.incrementAndGet(), list.size)
            }
        }.awaitAll()
        udp.await()
        found.values.sortedBy { ipSortKey(it.ip) }
    }

    fun ipSortKey(ip: String): Long = ip.split('.').fold(0L) { acc, p -> acc * 256 + (p.toLongOrNull() ?: 0) }

    /** True when [ip] answers KLAP handshake1 with 48 bytes. */
    fun probeKlap(ip: Inet4Address, connectMs: Int = 700, readMs: Int = 1500): Boolean = try {
        val seed = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val r = LanHttp.post(ip, "/app/handshake1", seed, null, connectMs = connectMs, readMs = readMs)
        r.code == 200 && r.body.size == 48
    } catch (e: IOException) {
        false
    } catch (e: TapoException) {
        false
    }

    /**
     * Sends the discovery packet to [broadcast] (if any) and to each of [targets], three rounds, and passes every
     * answer from a private address to [onFound]. The same packet each time; it asks nothing of the device.
     */
    private suspend fun udpDiscover(targets: List<Inet4Address>, broadcast: Inet4Address?, listenMs: Long, onFound: (Found) -> Unit) =
        withContext(Dispatchers.IO) {
            val packet = discoveryPacket(throwawayPublicKeyPem(), SecureRandom().nextInt())
            DatagramSocket().use { socket ->
                socket.broadcast = broadcast != null
                socket.soTimeout = 300
                val buf = ByteArray(4096)
                fun receiveUntil(end: Long) {
                    while (System.currentTimeMillis() < end) {
                        ensureActive()
                        val dp = DatagramPacket(buf, buf.size)
                        try {
                            socket.receive(dp)
                        } catch (e: SocketTimeoutException) {
                            continue
                        }
                        val from = dp.address as? Inet4Address ?: continue
                        if (!LanAddress.isPrivate(from)) continue
                        parseDiscoveryReply(buf.copyOf(dp.length), LanAddress.text(from))?.let(onFound)
                    }
                }
                repeat(3) {
                    ensureActive()
                    if (broadcast != null) {
                        try {
                            socket.send(DatagramPacket(packet, packet.size, broadcast, PORT))
                        } catch (e: IOException) {
                            // A VPN may not take broadcasts; the unicast packets below still go.
                        }
                    }
                    for ((i, ip) in targets.withIndex()) {
                        ensureActive()
                        try {
                            socket.send(DatagramPacket(packet, packet.size, ip, PORT))
                        } catch (e: IOException) {
                            // That address can't be reached from here; try the next one.
                        }
                        // A short pause every 32 packets, then read what came back, so a tunnel isn't flooded.
                        if (i % 32 == 31) receiveUntil(System.currentTimeMillis() + 20)
                    }
                    receiveUntil(System.currentTimeMillis() + listenMs)
                }
            }
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
            loginVersion = ((r["mgt_encrypt_schm"] as? JsonObject)?.get("lv") as? JsonPrimitive)?.content?.toIntOrNull(),
            https = ((r["mgt_encrypt_schm"] as? JsonObject)?.get("is_support_https") as? JsonPrimitive)?.content?.toBooleanStrictOrNull(),
        )
    }
}
