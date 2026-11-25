package com.ovpn.autodelay

import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.system.measureTimeMillis

interface VpnConnectorBridge {
    suspend fun connectAndWait(
        configContent: String,
        name: String,
        timeoutMs: Long = 8000L
    ): Boolean

    suspend fun disconnect()
}

class RealDelayEngine(private val vpnBridge: VpnConnectorBridge) {

    suspend fun sortAllByPreCheck(
        profiles: List<OvpnProfileItem>
    ): List<OvpnProfileItem> = coroutineScope {
        val jobs = profiles.map { profile ->
            async(Dispatchers.IO) {
                val ping = tcpPing(profile.host, profile.port, 1500)
                profile.preCheckMs = if (ping < Long.MAX_VALUE) ping else 5000L
                profile
            }
        }
        jobs.awaitAll().sortedBy { it.preCheckMs }
    }

    suspend fun findAndConnectFastest(
        profiles: List<OvpnProfileItem>,
        onLog: (String) -> Unit
    ): OvpnProfileItem? {
        onLog("Sorting all ${profiles.size} configs by initial reachability...")
        val allSorted = sortAllByPreCheck(profiles)

        val tested = mutableListOf<OvpnProfileItem>()

        for ((index, profile) in allSorted.withIndex()) {
            onLog("[${index + 1}/${allSorted.size}] Testing ${profile.name}...")
            vpnBridge.disconnect()
            delay(500)

            val connected = vpnBridge.connectAndWait(
                configContent = profile.rawConfig,
                name = profile.name,
                timeoutMs = 8000L
            )

            if (connected) {
                val realDelay = measureRealHttpDelay()
                profile.realDelayMs = realDelay
                if (realDelay < Long.MAX_VALUE) {
                    tested.add(profile)
                    onLog("  -> OK | Real Delay: ${realDelay}ms")
                } else {
                    onLog("  -> Connected, but no internet.")
                }
            } else {
                onLog("  -> Failed to connect.")
            }
        }

        val fastest = tested.minByOrNull { it.realDelayMs }
        if (fastest != null) {
            onLog("Connecting to fastest: ${fastest.name} (${fastest.realDelayMs}ms)")
            vpnBridge.disconnect()
            delay(500)
            vpnBridge.connectAndWait(fastest.rawConfig, fastest.name, 10000L)
            return fastest
        }

        onLog("No working server found.")
        return null
    }

    private fun tcpPing(host: String, port: Int, timeoutMs: Int): Long {
        return try {
            measureTimeMillis {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                }
            }
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    private suspend fun measureRealHttpDelay(timeoutMs: Int = 4000): Long = withContext(Dispatchers.IO) {
        try {
            var code = 0
            val elapsed = measureTimeMillis {
                val conn = URL("http://www.gstatic.com/generate_204").openConnection() as HttpURLConnection
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.useCaches = false
                code = conn.responseCode
                conn.disconnect()
            }
            if (code == 204 || code == 200) elapsed else Long.MAX_VALUE
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }
}
