package com.example.earpieceai

import java.net.InetAddress

enum class AudioTransportFormat(
    val extension: String,
    val mimeType: String
) {
    WAV("wav", "audio/wav"),
    FLAC("flac", "audio/flac")
}

object AudioTransportPolicy {
    fun formatForHost(host: String): AudioTransportFormat {
        return if (isLocalNetworkHost(host)) AudioTransportFormat.WAV else AudioTransportFormat.FLAC
    }

    fun isLocalNetworkHost(host: String): Boolean {
        val normalized = host.trim().removePrefix("[").removeSuffix("]").trimEnd('.').lowercase()
        if (normalized == "localhost" || normalized.endsWith(".localhost") || normalized.endsWith(".local")) {
            return true
        }

        parseIpv4(normalized)?.let { octets ->
            return octets[0] == 10 ||
                octets[0] == 127 ||
                (octets[0] == 169 && octets[1] == 254) ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168)
        }

        if (normalized == "::1" || normalized.startsWith("fc") || normalized.startsWith("fd")) {
            return true
        }
        if (normalized.length >= 3 && normalized.startsWith("fe") && normalized[2] in '8'..'b') {
            return true
        }

        return runCatching {
            InetAddress.getAllByName(normalized).any { address ->
                address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress
            }
        }.getOrDefault(false)
    }

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (index in parts.indices) {
            val value = parts[index].toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            octets[index] = value
        }
        return octets
    }
}
