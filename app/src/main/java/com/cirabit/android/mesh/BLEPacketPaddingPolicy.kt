package com.cirabit.android.mesh

import com.cirabit.android.protocol.MessageType

/**
 * iOS-compatible BLE padding policy.
 *
 * Keep this aligned with iOS BLEOutboundPacketPolicy.padsBLEFrame(for:):
 * only Noise frames are padded over BLE.
 */
object BLEPacketPaddingPolicy {
    fun shouldPadForBLE(type: UByte): Boolean {
        return when (MessageType.fromValue(type)) {
            MessageType.NOISE_ENCRYPTED, MessageType.NOISE_HANDSHAKE -> true
            else -> false
        }
    }
}
