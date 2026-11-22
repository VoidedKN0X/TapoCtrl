package com.vknxworks.tapoctrl.tapo

import com.vknxworks.tapoctrl.net.LanAddress
import java.net.Inet4Address
import java.net.InetAddress

/**
 * A network range typed by the user for "Scan a network range" (for example 192.168.1.0/24 reached through a
 * Tailscale subnet route): CIDR notation, at most a /22 (1022 addresses), and entirely inside a private block
 * (10/8, 172.16/12, 192.168/16) or the shared 100.64/10 block. Pure; unit-tested.
 */
class ScanRange private constructor(private val network: Long, val prefix: Int) {
    /** The range in normal form, such as "192.168.1.0/24" (also when "192.168.1.77/24" was typed). */
    val text: String get() = "${ipText(network)}/$prefix"

    /** How many addresses are probed. */
    val size: Int get() = hostRange().let { (it.last - it.first + 1).toInt() }

    /** The addresses probed: all but the network and broadcast address, for /30 and larger. */
    fun hosts(): List<Inet4Address> = hostRange().map { addr(it) }

    private fun hostRange(): LongRange {
        val count = 1L shl (32 - prefix)
        return if (prefix >= 31) network until network + count else (network + 1) until (network + count - 1)
    }

    sealed interface Parsed {
        data class Ok(val range: ScanRange) : Parsed
        /** Not "address/prefix". */
        data object Invalid : Parsed
        /** Larger than a /22. */
        data object TooLarge : Parsed
        /** Not inside 10/8, 172.16/12, 192.168/16 or 100.64/10. */
        data object NotPrivate : Parsed
    }

    companion object {
        const val SMALLEST_PREFIX = 22
        private val cidr = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})/(\d{1,2})$""")

        /** The blocks a range may be in: (network, prefix). */
        private val ALLOWED = listOf(
            0x0A000000L to 8, // 10.0.0.0/8
            0xAC100000L to 12, // 172.16.0.0/12
            0xC0A80000L to 16, // 192.168.0.0/16
            0x64400000L to 10, // 100.64.0.0/10 (shared address space, also Tailscale's)
        )

        fun parse(input: String): Parsed {
            val m = cidr.matchEntire(input.trim().replace(" ", "")) ?: return Parsed.Invalid
            val parts = m.groupValues.drop(1).map { it.toIntOrNull() ?: return Parsed.Invalid }
            if (parts.take(4).any { it !in 0..255 }) return Parsed.Invalid
            val prefix = parts[4]
            if (prefix !in 0..32) return Parsed.Invalid
            if (prefix < SMALLEST_PREFIX) return Parsed.TooLarge
            val value = parts.take(4).fold(0L) { acc, p -> (acc shl 8) or p.toLong() }
            val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
            val network = value and mask
            val last = network + (1L shl (32 - prefix)) - 1
            val inside = ALLOWED.any { (block, bits) ->
                val bm = (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
                (network and bm) == block && (last and bm) == block
            }
            if (!inside) return Parsed.NotPrivate
            val range = ScanRange(network, prefix)
            // Belt and braces: every address must pass the check every connection goes through.
            if (range.hosts().any { !LanAddress.isPrivate(it) }) return Parsed.NotPrivate
            return Parsed.Ok(range)
        }

        private fun addr(v: Long): Inet4Address = InetAddress.getByAddress(
            byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()),
        ) as Inet4Address

        private fun ipText(v: Long) = "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"
    }
}
