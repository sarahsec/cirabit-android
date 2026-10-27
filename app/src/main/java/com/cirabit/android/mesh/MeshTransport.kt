package com.cirabit.android.mesh

import com.cirabit.android.model.RoutedPacket
import com.cirabit.android.protocol.CirabitPacket

/**
 * Transport abstraction used by MeshCore to send packets via a specific medium.
 */
interface MeshTransport {
    val id: String

    /**
     * Broadcasts a packet and reports whether at least one concrete transport write was accepted.
     */
    fun broadcastPacket(routed: RoutedPacket): Boolean

    fun sendPacketToPeer(peerID: String, packet: CirabitPacket): Boolean

    /**
     * Send through an exact transport generation rather than a reusable peer alias.
     * Transports that cannot prove the link identity must decline the operation.
     */
    fun sendPacketToLink(
        relayAddress: String,
        ingressLinkID: String,
        packet: CirabitPacket
    ): Boolean = false

    fun cancelTransfer(transferId: String): Boolean = false

    fun getDeviceAddressForPeer(peerID: String): String? = null

    fun getDeviceAddressToPeerMapping(): Map<String, String> = emptyMap()

    fun getTransportDebugInfo(): String = ""
}
