package com.taskra.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

internal const val LAN_TLS_HOST = "chengyi-thinkpad-t420.taild0ff49.ts.net"
internal const val LAN_SYNC_PORT = 38129

/** 不接受公有地址或任意域名，避免把凭据发送到意料之外的主机。 */
internal fun privateLanIpv4(raw: String): Long? {
    val parts = raw.split('.')
    if (parts.size != 4 || parts.any { !it.matches(Regex("[0-9]{1,3}")) || (it.length > 1 && it[0] == '0') }) return null
    val octets = parts.map { it.toInt() }
    if (octets.any { it !in 0..255 }) return null
    if (!(octets[0] == 10 || (octets[0] == 172 && octets[1] in 16..31) || (octets[0] == 192 && octets[1] == 168))) return null
    return octets.fold(0L) { result, octet -> (result shl 8) or octet.toLong() }
}

internal fun sameIpv4Subnet(first: Long, second: Long, prefix: Int): Boolean {
    if (prefix !in 1..32) return false
    val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
    return (first and mask) == (second and mask)
}

internal class SyncLanHints(private val context: Context) {
    private val prefs = context.getSharedPreferences("sync_lan", Context.MODE_PRIVATE)

    fun remember(address: String?) {
        if (address != null && privateLanIpv4(address) != null) prefs.edit().putString("address", address).apply()
        // 服务端明确停用局域网入口时，丢弃旧地址，防止持续尝试过期路由。
        else if (address == null) prefs.edit().remove("address").apply()
    }

    fun matchingWifiAddress(): String? {
        val candidate = prefs.getString("address", null) ?: return null
        val numeric = privateLanIpv4(candidate) ?: return null
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return manager.allNetworks.firstNotNullOfOrNull { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@firstNotNullOfOrNull null
            val properties = manager.getLinkProperties(network) ?: return@firstNotNullOfOrNull null
            if (properties.linkAddresses.any { link ->
                    val address = link.address as? Inet4Address ?: return@any false
                    val local = privateLanIpv4(address.hostAddress ?: "") ?: return@any false
                    sameIpv4Subnet(local, numeric, link.prefixLength)
                }) candidate else null
        }
    }
}
