package com.example.lanshare

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

object LanIp {
    fun address(context: Context? = null): String {
        if (context != null) {
            try {
                @Suppress("DEPRECATION")
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val ip = wifi.connectionInfo.ipAddress
                if (ip != 0) {
                    return listOf(ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
                        .joinToString(".")
                }
            } catch (_: Exception) { }
        }
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                if (!network.isUp || network.isLoopback) continue
                val addresses = network.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                        return address.hostAddress ?: continue
                    }
                }
            }
            "Check Wi-Fi details"
        } catch (_: Exception) {
            "Check Wi-Fi details"
        }
    }
}
