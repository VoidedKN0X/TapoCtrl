package com.vknxworks.tapoctrl.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * TapoCtrl only ever connects to addresses on your own network. Every connection goes through [parse], which
 * accepts only a numeric address (no host names, so no DNS lookup) in a private or link-local range:
 * 10/8, 172.16/12, 192.168/16, 169.254/16, fc00::/7 and fe80::/10.
 */
object LanAddress {
    private val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    /** A private IPv4 address from text such as "192.168.1.20", or null for anything else. */
    fun parse(text: String): Inet4Address? {
        val m = ipv4.matchEntire(text.trim()) ?: return null
        val b = m.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
        if (b.any { it !in 0..255 }) return null
        val addr = InetAddress.getByAddress(b.map { it.toByte() }.toByteArray()) as Inet4Address
        return addr.takeIf { isPrivate(it) }
    }

    fun isPrivate(a: InetAddress): Boolean {
        val b = a.address.map { it.toInt() and 0xFF }
        return when (a) {
            is Inet4Address ->
                b[0] == 10 ||
                    (b[0] == 172 && b[1] in 16..31) ||
                    (b[0] == 192 && b[1] == 168) ||
                    (b[0] == 169 && b[1] == 254)
            is Inet6Address -> (b[0] and 0xFE) == 0xFC || (b[0] == 0xFE && (b[1] and 0xC0) == 0x80)
            else -> false
        }
    }

    /** Text form for display and storage. */
    fun text(a: InetAddress): String = a.hostAddress ?: ""
}
