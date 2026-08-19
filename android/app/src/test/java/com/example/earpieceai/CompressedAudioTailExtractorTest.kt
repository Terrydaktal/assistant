package com.example.earpieceai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompressedAudioTailExtractorTest {
    @Test
    fun selectsLosslessPacketCopyTransportForSupportedCodecs() {
        assertEquals(
            CompressedTailTransport.M4A,
            compressedTailTransportForMime("audio/mp4a-latm", supportsOggMuxer = true)
        )
        assertEquals(
            CompressedTailTransport.MP3,
            compressedTailTransportForMime("audio/mpeg", supportsOggMuxer = true)
        )
        assertEquals(
            CompressedTailTransport.OGG_OPUS,
            compressedTailTransportForMime("audio/opus", supportsOggMuxer = true)
        )
    }

    @Test
    fun opusFallsBackWhenAndroidOggMuxingIsUnavailable() {
        assertNull(compressedTailTransportForMime("audio/opus", supportsOggMuxer = false))
    }

    @Test
    fun unsupportedCodecsUseExistingDecodeAndFlacFallback() {
        assertNull(compressedTailTransportForMime("audio/raw", supportsOggMuxer = true))
        assertNull(compressedTailTransportForMime("audio/vorbis", supportsOggMuxer = true))
    }
}
