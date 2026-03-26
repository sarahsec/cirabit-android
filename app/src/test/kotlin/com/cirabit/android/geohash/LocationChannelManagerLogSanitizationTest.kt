package com.cirabit.android.geohash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocationChannelManagerLogSanitizationTest {

    @Test
    fun selectedChannelLogMessage_mesh_usesStaticSafeLabel() {
        val message = selectedChannelLogMessage(ChannelID.Mesh)

        assertEquals("Selected channel: mesh", message)
    }

    @Test
    fun selectedChannelLogMessage_location_doesNotIncludeGeohashOrLineBreaks() {
        val injectedGeohash = "abc123\nfake-log-entry"
        val channel = ChannelID.Location(
            GeohashChannel(
                level = GeohashChannelLevel.CITY,
                geohash = injectedGeohash,
            ),
        )

        val message = selectedChannelLogMessage(channel)

        assertEquals("Selected channel: location/city", message)
        assertFalse(message.contains("abc123"))
        assertFalse(message.contains('\n'))
        assertFalse(message.contains('\r'))
    }
}
