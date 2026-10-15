package com.cirabit.android.mesh

import com.cirabit.android.model.CirabitMessage
import com.cirabit.android.model.MessageReaction

/**
 * Shared mesh delegate interface for transport-agnostic callbacks.
 */
interface MeshDelegate {
    fun didReceiveMessage(message: CirabitMessage)
    /** Cirabit: emoji reaction update received for an existing message. */
    fun didReceiveMessageReaction(reaction: MessageReaction) {}
    /** Cirabit: a queued private reaction was dropped before it could be sent. */
    fun didDropPendingReaction(reaction: MessageReaction, recipientPeerID: String, reason: String) {}
    fun didUpdatePeerList(peers: List<String>)
    fun didReceiveChannelLeave(channel: String, fromPeer: String)
    fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String)
    fun didReceiveReadReceipt(messageID: String, recipientPeerID: String)
    fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long) {}
    fun didReceiveVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long) {}
    /** Current Noise generation either proved peer state or exhausted its 5-second watchdog. */
    fun didResolvePrivateMediaPolicy(peerID: String) {}
    fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String?
    fun getNickname(): String?
    fun isFavorite(peerID: String): Boolean
}
