package com.example.earpieceai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTransportPolicyTest {
    @Test
    fun privateAndLocalAddressesUseWav() {
        listOf(
            "localhost",
            "assistant.local",
            "10.2.3.4",
            "172.16.0.1",
            "172.31.255.254",
            "192.168.50.51",
            "127.0.0.1",
            "169.254.1.2",
            "::1",
            "[fd12::1]",
            "fe80::1234"
        ).forEach { host ->
            assertTrue("Expected local host: $host", AudioTransportPolicy.isLocalNetworkHost(host))
            assertEquals(AudioTransportFormat.WAV, AudioTransportPolicy.formatForHost(host))
        }
    }

    @Test
    fun publicAddressesUseFlac() {
        listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "192.0.2.10").forEach { host ->
            assertFalse("Expected remote host: $host", AudioTransportPolicy.isLocalNetworkHost(host))
            assertEquals(AudioTransportFormat.FLAC, AudioTransportPolicy.formatForHost(host))
        }
    }
}
