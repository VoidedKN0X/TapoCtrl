package com.vknxworks.tapoctrl.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * The phone's own networks, read with ACCESS_NETWORK_STATE only (no Wi-Fi name, no location). Nothing is sent.
 *
 * With a VPN such as Tailscale on, Android's active network is the VPN, whose address (100.x for Tailscale) is
 * not on the Wi-Fi. So every network the phone has is looked at, and the Wi-Fi (or Ethernet) one that isn't a VPN
 * is used for the Wi-Fi scan.
 */
object LocalNetwork {
    /** This phone's private IPv4 address on Wi-Fi or Ethernet, also while a VPN is on; null when it has none. */
    fun wifiAddress(context: Context): Inet4Address? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        for (net in networks(cm)) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            val props = cm.getLinkProperties(net) ?: continue
            props.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>()
                .firstOrNull { LanAddress.isPrivate(it) && !isShared(it) }
                ?.let { return it }
        }
        return null
    }

    /** True when a VPN (such as Tailscale) is on. */
    fun vpnActive(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return networks(cm).any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }

    /** The active network first, then every other one the phone has (the VPN's underlying Wi-Fi among them). */
    private fun networks(cm: ConnectivityManager): List<Network> {
        // getAllNetworks is deprecated in favour of callbacks, but still returns every network at once, which is
        // all a one-off check before a scan needs.
        @Suppress("DEPRECATION")
        val all = cm.allNetworks.toList()
        return listOfNotNull(cm.activeNetwork) + all.filter { it != cm.activeNetwork }
    }

    /** 100.64/10: a carrier's or Tailscale's address, never a Wi-Fi subnet to scan. */
    private fun isShared(a: Inet4Address): Boolean {
        val b = a.address
        return (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xFF) in 64..127
    }
}
