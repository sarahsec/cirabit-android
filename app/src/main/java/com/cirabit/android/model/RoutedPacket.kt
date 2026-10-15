package com.cirabit.android.model

import com.cirabit.android.protocol.CirabitPacket

/**
 * Represents a routed packet with additional metadata
 * Used for processing and routing packets in the mesh network
 */
data class RoutedPacket(
    val packet: CirabitPacket,
    val peerID: String? = null,           // Who sent it (parsed from packet.senderID)
    val relayAddress: String? = null,     // Address it came from (for avoiding loopback)
    val transferId: String? = null,       // Optional stable transfer ID for progress tracking
    /** Exact fragments admitted during private-media prepare; never rebuild them at commit. */
    val preparedPackets: List<CirabitPacket>? = null,
    // Opaque, process-local ingress identity. Unlike relayAddress, this distinguishes replacement
    // sockets for the same provisional peer and must never be serialized onto the mesh.
    val ingressLinkID: String? = null
)
