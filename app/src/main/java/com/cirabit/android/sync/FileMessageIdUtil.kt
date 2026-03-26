package com.cirabit.android.sync

import java.security.MessageDigest
import java.util.Locale

/**
 * Deterministic ID helper for file/media chat messages.
 * Uses SHA-256 over [senderPeerID | packetTimestampMs | encodedFilePayload]
 * and returns a compact 16-byte (128-bit) lowercase hex ID.
 */
object FileMessageIdUtil {
    fun computeIdHex(
        senderPeerID: String,
        timestampMs: Long,
        encodedFilePayload: ByteArray
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(senderPeerID.lowercase(Locale.US).toByteArray(Charsets.UTF_8))
        for (i in 7 downTo 0) {
            md.update(((timestampMs ushr (i * 8)) and 0xFF).toByte())
        }
        md.update(encodedFilePayload)
        return md.digest().copyOf(16).joinToString("") { "%02x".format(it) }
    }
}
