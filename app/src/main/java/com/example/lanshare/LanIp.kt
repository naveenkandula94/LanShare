
package com.example.lanshare

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface

object LanIp {

    /**
     * Finds a usable local IPv4 address.
     *
     * Prefer Wi-Fi interfaces, then fall back to another
     * active local network interface.
     */
    @Suppress("UNUSED_PARAMETER")
    fun address(context: Context? = null): String {

        return try {

            val interfaces = mutableListOf<NetworkInterface>()

            val enumeration =
                NetworkInterface.getNetworkInterfaces()

            while (enumeration.hasMoreElements()) {
                interfaces.add(enumeration.nextElement())
            }

            // Prefer normal Wi-Fi interfaces.
            val orderedInterfaces = interfaces.sortedBy {

                when {
                    it.name.equals("wlan0", ignoreCase = true) -> 0
                    it.name.startsWith("wifi", ignoreCase = true) -> 1
                    it.name.startsWith("eth", ignoreCase = true) -> 2
                    else -> 3
                }
            }

            for (network in orderedInterfaces) {

                if (!network.isUp || network.isLoopback) {
                    continue
                }

                val addresses = network.inetAddresses

                while (addresses.hasMoreElements()) {

                    val address = addresses.nextElement()

                    if (
                        address is Inet4Address &&
                        !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress &&
                        address.isSiteLocalAddress
                    ) {

                        return address.hostAddress
                            ?: continue
                    }
                }
            }

            "Check Wi-Fi details"

        } catch (e: Exception) {

            "Check Wi-Fi details"
        }
    }
}
