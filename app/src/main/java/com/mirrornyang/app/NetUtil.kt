package com.mirrornyang.app

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtil {
    /** 같은 Wi-Fi(사설망)에서 접속할 수 있는 폰의 IPv4 주소들. Wi-Fi(wlan)를 맨 앞에 둔다. */
    fun localIpv4Addresses(): List<String> {
        val result = mutableListOf<Pair<String, String>>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces() ?: return emptyList()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress) {
                        result += nif.name to (addr.hostAddress ?: continue)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return result
            .sortedBy { (name, _) -> if (name.startsWith("wlan")) 0 else if (name.startsWith("ap") || name.startsWith("swlan")) 1 else 2 }
            .map { it.second }
            .distinct()
    }
}
