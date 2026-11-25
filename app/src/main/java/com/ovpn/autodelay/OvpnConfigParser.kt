package com.ovpn.autodelay

import java.io.InputStream
import java.util.zip.ZipInputStream

data class OvpnProfileItem(
    val name: String,
    val rawConfig: String,
    val host: String,
    val port: Int,
    val isTcp: Boolean,
    var preCheckMs: Long = Long.MAX_VALUE,
    var realDelayMs: Long = Long.MAX_VALUE
)

object OvpnConfigParser {

    fun parseFromZip(zipStream: InputStream): List<OvpnProfileItem> {
        val profiles = mutableListOf<OvpnProfileItem>()
        val remoteRegex = Regex("""^remote\s+(\S+)\s+(\d+)""", RegexOption.MULTILINE)
        val protoRegex = Regex("""^proto\s+(\S+)""", RegexOption.MULTILINE)

        ZipInputStream(zipStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".ovpn", ignoreCase = true)) {
                    val originalContent = zis.bufferedReader().readText()
                    val patchedContent = patchTunnelBearConfig(originalContent)
                    val remoteMatch = remoteRegex.find(patchedContent)
                    if (remoteMatch != null) {
                        val host = remoteMatch.groupValues[1]
                        val port = remoteMatch.groupValues[2].toIntOrNull() ?: 443
                        val proto = protoRegex.find(patchedContent)?.groupValues?.get(1)?.lowercase() ?: "udp"
                        val fileName = entry.name.substringAfterLast("/")

                        profiles.add(
                            OvpnProfileItem(
                                name = fileName.removeSuffix(".ovpn"),
                                rawConfig = patchedContent,
                                host = host,
                                port = port,
                                isTcp = proto.startsWith("tcp")
                            )
                        )
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return profiles
    }

    private fun patchTunnelBearConfig(config: String): String {
        var result = config.replace(Regex("^dev\\s+tun0$", RegexOption.MULTILINE), "dev tun")
        if (!result.contains("data-ciphers-fallback")) {
            result += "\ndata-ciphers-fallback AES-256-CBC\n"
        }
        return result
    }
}
