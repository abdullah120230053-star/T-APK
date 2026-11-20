package com.dama.tahany.data

import com.dama.tahany.model.TrustedPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrustedPeerCodecTest {
    @Test
    fun roundTripsNamesWithSeparators() {
        val peer = TrustedPeer("device id/with|chars", "Tahany | Tablet")
        val decoded = TrustedPeerCodec.decode(TrustedPeerCodec.encode(peer))
        assertEquals(peer, decoded)
    }

    @Test
    fun malformedValuesAreIgnored() {
        assertNull(TrustedPeerCodec.decode(""))
        assertNull(TrustedPeerCodec.decode("not-base64"))
        assertNull(TrustedPeerCodec.decode("Zm9v"))
    }
}
