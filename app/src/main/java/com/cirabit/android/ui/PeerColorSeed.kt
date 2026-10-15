package com.cirabit.android.ui

import com.cirabit.android.model.CirabitMessage
import java.util.Locale

/**
 * Stable, presentation-neutral identity used to derive a peer hue.
 *
 * ViewModels may expose this value, but only the UI theme resolves it to a rendered color.
 */
@JvmInline
value class PeerColorSeed(val value: String)

fun meshPeerColorSeed(peerID: String): PeerColorSeed =
    PeerColorSeed("noise:${peerID.lowercase(Locale.ROOT)}")

fun nostrPeerColorSeed(pubkeyHex: String): PeerColorSeed =
    PeerColorSeed("nostr:${pubkeyHex.lowercase(Locale.ROOT)}")

fun peerColorSeedForMessage(message: CirabitMessage): PeerColorSeed {
    val value = when {
        message.senderPeerID?.startsWith("nostr:") == true ||
            message.senderPeerID?.startsWith("nostr_") == true -> {
            "nostr:${message.senderPeerID.lowercase(Locale.ROOT)}"
        }
        message.senderPeerID?.length == 16 || message.senderPeerID?.length == 64 -> {
            "noise:${message.senderPeerID.lowercase(Locale.ROOT)}"
        }
        else -> message.sender.lowercase(Locale.ROOT)
    }

    return PeerColorSeed(value)
}
